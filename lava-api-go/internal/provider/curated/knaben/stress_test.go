package knaben

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sort"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// §11.4.85 STRESS + CHAOS coverage for the bounded-retry logic added to the
// knaben curated JSON-API provider (maxAttempts=3, 500ms backoff, transient =
// network/timeout-or-5xx, terminal never retried — see fetchHits / fetchHitsOnce
// in client.go).
//
// Anti-Bluff (§6.J): the SUT is the REAL knaben.Client POSTing to a REAL
// httptest.Server. NO live calls — the server is deterministic. Primary
// assertions are on the USER-VISIBLE outcome (recovered SearchItem rows / a
// clean error), secondary on counts/latency.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2): break the retry by replacing the
// c.fetchHits call in Search with a single c.fetchHitsOnce call (drop the retry
// loop). TestStress_ConcurrentFlakyServer and TestChaos_TransientRecovers then
// FAIL: the flaky server's first-hit 503 is surfaced as ErrUnknown instead of
// retried, so "all N concurrent Search() calls eventually succeed" reports the
// 503 errors. Revert → pass.

// stressBody is a /v1 response carrying exactly one valid 40-hex hash so a 200
// yields exactly one downloadable SearchItem (user-visible row).
const stressBody = `{"hits":[{"title":"Stress Sample 1080p","hash":"8e1e7ad6a7198d1bea2d8564f40ec3480c490301","bytes":732912680,"seeders":100,"peers":7,"date":"2026-06-16T00:00:00Z","category":"Apps"}]}`

// flakyServer serves an intermittent 503 → 200 fault. Every `failEvery`-th hit
// (atomic, shared across all concurrent callers) returns 503; the rest return a
// valid body. With the live retry (3 attempts), a caller that draws one 503
// retries and the next draw is overwhelmingly a 200, so the call recovers.
func flakyServer(t *testing.T, failEvery int64) (*httptest.Server, *int64, *int64) {
	t.Helper()
	var hits, fails int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&hits, 1)
		if failEvery > 0 && n%failEvery == 0 {
			atomic.AddInt64(&fails, 1)
			http.Error(w, "flaky upstream", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(stressBody))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits, &fails
}

// settleGoroutines waits for transient goroutines (httptest conns, retry timers)
// to drain, then returns the count. Avoids a flaky leak assertion.
func settleGoroutines() int {
	var n int
	for i := 0; i < 40; i++ {
		runtime.GC()
		time.Sleep(25 * time.Millisecond)
		n = runtime.NumGoroutine()
	}
	return n
}

func percentile(d []time.Duration, p float64) time.Duration {
	if len(d) == 0 {
		return 0
	}
	sort.Slice(d, func(i, j int) bool { return d[i] < d[j] })
	return d[int(float64(len(d)-1)*p)]
}

// TestStress_ConcurrentFlakyServer — N>=50 concurrent Search() calls against a
// server that intermittently 503s (every 9th shared hit). With the bounded
// retry, every call MUST eventually return downloadable rows (3 consecutive 503
// draws — which would defeat the 3-attempt retry — is astronomically unlikely at
// a ~1/9 fault rate). Asserts: no deadlock (ctx-bounded), no goroutine leak, all
// succeed. Records p50/p95.
func TestStress_ConcurrentFlakyServer(t *testing.T) {
	const concurrency = 60
	srv, hits, fails := flakyServer(t, 9)

	// One shared client across all callers (matches production: a single provider
	// instance fields concurrent searches) — so idle keep-alive connections from
	// the pooled Transport do not masquerade as a leak.
	client := NewClient(srv.URL)
	before := settleGoroutines()

	var (
		wg       sync.WaitGroup
		mu       sync.Mutex
		lats     = make([]time.Duration, 0, concurrency)
		okCount  int64
		errFirst error
	)
	start := make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			t0 := time.Now()
			res, err := client.Search(ctx, "stress", 0)
			elapsed := time.Since(t0)
			mu.Lock()
			lats = append(lats, elapsed)
			mu.Unlock()
			if err != nil {
				mu.Lock()
				if errFirst == nil {
					errFirst = err
				}
				mu.Unlock()
				return
			}
			if len(res.Results) == 0 {
				mu.Lock()
				if errFirst == nil {
					errFirst = fmt.Errorf("recovered call returned 0 user-visible rows")
				}
				mu.Unlock()
				return
			}
			atomic.AddInt64(&okCount, 1)
		}()
	}

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	close(start)

	select {
	case <-done:
	case <-time.After(25 * time.Second):
		t.Fatal("DEADLOCK: concurrent Search() calls did not complete within 25s")
	}

	if errFirst != nil {
		t.Fatalf("not all concurrent calls recovered: first error = %v (hits=%d fails=%d)",
			errFirst, atomic.LoadInt64(hits), atomic.LoadInt64(fails))
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d (every call must recover to downloadable rows)", got, concurrency)
	}

	client.http.CloseIdleConnections()
	after := settleGoroutines()
	if after > before+5 {
		t.Errorf("goroutine leak: before=%d after=%d (delta=%d)", before, after, after-before)
	}

	t.Logf("STRESS knaben: concurrency=%d ok=%d server_hits=%d server_503s=%d p50=%s p95=%s goroutines before=%d after=%d",
		concurrency, atomic.LoadInt64(&okCount), atomic.LoadInt64(hits), atomic.LoadInt64(fails),
		percentile(lats, 0.50), percentile(lats, 0.95), before, after)
}

// TestStress_LatencyPercentiles — record p50/p95 over M serial iterations
// against a healthy server; assert p95 is under a sane bound (no per-call hang).
func TestStress_LatencyPercentiles(t *testing.T) {
	const iters = 100
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(stressBody))
	}))
	defer srv.Close()

	client := NewClient(srv.URL)
	ctx := context.Background()
	lats := make([]time.Duration, 0, iters)
	for i := 0; i < iters; i++ {
		t0 := time.Now()
		res, err := client.Search(ctx, "stress", 0)
		if err != nil {
			t.Fatalf("iter %d: %v", i, err)
		}
		if len(res.Results) != 1 {
			t.Fatalf("iter %d: got %d rows, want 1", i, len(res.Results))
		}
		lats = append(lats, time.Since(t0))
	}
	p95 := percentile(lats, 0.95)
	if p95 > time.Second {
		t.Errorf("p95 latency = %s, want < 1s against a healthy local server", p95)
	}
	t.Logf("STRESS knaben latency: iters=%d p50=%s p95=%s", iters, percentile(lats, 0.50), p95)
}

// TestChaos_TransientRecovers — inject a transient fault (503 on attempt 1 then
// 200) under concurrency; assert every call recovers to downloadable rows. Each
// caller gets its own server whose FIRST hit 503s, subsequent hits 200.
func TestChaos_TransientRecovers(t *testing.T) {
	const concurrency = 50
	var wg sync.WaitGroup
	var okCount int64
	var firstErr atomic.Value

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var n int64
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if atomic.AddInt64(&n, 1) == 1 {
					http.Error(w, "transient", http.StatusServiceUnavailable)
					return
				}
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(stressBody))
			}))
			defer srv.Close()

			res, err := NewClient(srv.URL).Search(ctx, "chaos", 0)
			if err != nil {
				firstErr.CompareAndSwap(nil, err)
				return
			}
			if len(res.Results) != 1 {
				firstErr.CompareAndSwap(nil, fmt.Errorf("want 1 row, got %d", len(res.Results)))
				return
			}
			atomic.AddInt64(&okCount, 1)
		}()
	}
	wg.Wait()

	if v := firstErr.Load(); v != nil {
		t.Fatalf("transient 503->200 did not recover under concurrency: %v", v)
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d", got, concurrency)
	}
	t.Logf("CHAOS knaben transient: %d/%d calls recovered from a first-hit 503", okCount, concurrency)
}

// TestChaos_PersistentFailsCleanly — a server that ALWAYS 503s must fail cleanly
// after maxAttempts (no hang, ctx-bounded, terminal ErrUnknown). Asserts the call
// returns within a bounded time and surfaces a non-nil error.
func TestChaos_PersistentFailsCleanly(t *testing.T) {
	var hits int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&hits, 1)
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	t0 := time.Now()
	res, err := NewClient(srv.URL).Search(ctx, "persistent", 0)
	elapsed := time.Since(t0)

	if err == nil {
		t.Fatalf("persistent 503 returned nil error (res=%v)", res)
	}
	if !errors.Is(err, provider.ErrUnknown) {
		t.Fatalf("want ErrUnknown wrapped, got %v", err)
	}
	// 3 attempts with 2x500ms backoff ~= 1s; must NOT hang to ctx deadline.
	if elapsed > 5*time.Second {
		t.Fatalf("persistent failure took %s — retry not bounded (possible hang)", elapsed)
	}
	if got := atomic.LoadInt64(&hits); got != maxAttempts {
		t.Errorf("server hits = %d, want maxAttempts=%d (all transient attempts exhausted)", got, maxAttempts)
	}
	t.Logf("CHAOS knaben persistent: clean failure in %s after %d attempts (err=%v)", elapsed, hits, err)
}

// TestChaos_PersistentRespectsCtxCancel — when the caller's ctx expires mid
// retry-backoff, the call MUST abort promptly (ctx-bounded), not run the full
// retry budget. Asserts the bounded-backoff ctx.Done() path in fetchHits.
func TestChaos_PersistentRespectsCtxCancel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	// Deadline shorter than one backoff so the retry loop aborts on ctx.Done().
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	t0 := time.Now()
	_, err := NewClient(srv.URL).Search(ctx, "cancel", 0)
	elapsed := time.Since(t0)

	if err == nil {
		t.Fatal("expected error when ctx expires mid-retry")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("ctx-cancel not honored: took %s", elapsed)
	}
	t.Logf("CHAOS knaben ctx-cancel: aborted in %s (err=%v)", elapsed, err)
}
