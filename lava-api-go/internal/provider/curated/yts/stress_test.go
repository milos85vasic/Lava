package yts

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

// §11.4.85 STRESS + CHAOS coverage for the YTS curated FAILOVER-MIRROR provider.
// YTS's resilience is across-mirror failover (Search tries each base URL in
// order; a 5xx/network-dead mirror is skipped, the first healthy mirror's
// result is returned) — see Search / searchOne in client.go. This is the
// failover-mirror class exemplar (distinct from tokyotosho's in-host retry).
//
// Anti-Bluff (§6.J): the SUT is the REAL yts.Client (via NewClientWithMirrors)
// hitting REAL httptest.Servers. NO live calls. Primary assertions are on the
// USER-VISIBLE outcome (recovered SearchResult rows / a clean error), secondary
// on counts/latency.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2): break the failover by making Search
// try only the FIRST base URL (`return c.searchOne(ctx, c.baseURLs[0], query)`).
// TestStress_ConcurrentFlakyMirrors and TestChaos_TransientMirrorRecovers then
// FAIL: the first mirror is the flaky/dead one, the single attempt surfaces its
// 503/network error, and "all N calls recover to downloadable rows" reports the
// errors. Revert → pass. Recorded inline in the test bodies.

// ytsBody is a valid list_movies.json envelope with one movie carrying one
// torrent (40-hex hash) → exactly one downloadable, user-visible SearchItem.
const ytsBody = `{"status":"ok","data":{"movie_count":1,"movies":[{"id":1,"title_long":"Stress Movie (2026)","year":2026,"torrents":[{"hash":"0123456789abcdef0123456789abcdef01234567","quality":"1080p","type":"web","size_bytes":1500000000,"seeds":42,"peers":7,"date_uploaded_unix":1718496000}]}]}}`

func healthyMirror(t *testing.T, hits *int64) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits != nil {
			atomic.AddInt64(hits, 1)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(ytsBody))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func always503Mirror(t *testing.T, hits *int64) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits != nil {
			atomic.AddInt64(hits, 1)
		}
		http.Error(w, "mirror down", http.StatusServiceUnavailable)
	}))
	t.Cleanup(srv.Close)
	return srv
}

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

// TestStress_ConcurrentFlakyMirrors — N>=50 concurrent Search() calls where the
// FIRST mirror flakily 503s and the SECOND is healthy. With failover, every
// call MUST recover to downloadable rows via the healthy mirror. Asserts: no
// deadlock, no goroutine leak, all succeed. Records p50/p95.
func TestStress_ConcurrentFlakyMirrors(t *testing.T) {
	const concurrency = 60
	var flakyN, flaky503, healthyHits int64

	// Intermittent fault on mirror 1: every 3rd hit 503s (the rest 200 — also
	// fine, failover only triggers on the 503). Either way the second mirror
	// guarantees recovery, so this exercises the "first mirror sometimes works,
	// sometimes fails over" path under heavy concurrency.
	flaky := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt64(&flakyN, 1)%3 == 0 {
			atomic.AddInt64(&flaky503, 1)
			http.Error(w, "flaky", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(ytsBody))
	}))
	defer flaky.Close()
	healthy := healthyMirror(t, &healthyHits)

	client := NewClientWithMirrors([]string{flaky.URL, healthy.URL})
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
			if err != nil && errFirst == nil {
				errFirst = err
			} else if err == nil && len(res.Results) == 0 && errFirst == nil {
				errFirst = fmt.Errorf("recovered call returned 0 user-visible rows")
			}
			mu.Unlock()
			if err == nil && len(res.Results) > 0 {
				atomic.AddInt64(&okCount, 1)
			}
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
		t.Fatalf("not all concurrent calls recovered via failover: first error = %v", errFirst)
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d", got, concurrency)
	}

	client.http.CloseIdleConnections()
	after := settleGoroutines()
	if after > before+5 {
		t.Errorf("goroutine leak: before=%d after=%d (delta=%d)", before, after, after-before)
	}

	t.Logf("STRESS yts: concurrency=%d ok=%d flaky_hits=%d flaky_503s=%d healthy_hits=%d p50=%s p95=%s goroutines before=%d after=%d",
		concurrency, atomic.LoadInt64(&okCount), atomic.LoadInt64(&flakyN), atomic.LoadInt64(&flaky503),
		atomic.LoadInt64(&healthyHits), percentile(lats, 0.50), percentile(lats, 0.95), before, after)
}

// TestChaos_TransientMirrorRecovers — inject a transient fault: the first
// mirror is DOWN (always 503), the second is healthy. Under concurrency every
// call MUST fail over and recover to downloadable rows.
func TestChaos_TransientMirrorRecovers(t *testing.T) {
	const concurrency = 50
	var downHits, healthyHits int64
	down := always503Mirror(t, &downHits)
	healthy := healthyMirror(t, &healthyHits)
	client := NewClientWithMirrors([]string{down.URL, healthy.URL})

	var wg sync.WaitGroup
	var okCount int64
	var firstErr atomic.Value
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, err := client.Search(ctx, "chaos", 0)
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
		t.Fatalf("failover did not recover from a dead first mirror: %v", v)
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d", got, concurrency)
	}
	t.Logf("CHAOS yts transient: %d/%d calls failed over (dead_mirror_hits=%d healthy_hits=%d)",
		okCount, concurrency, atomic.LoadInt64(&downHits), atomic.LoadInt64(&healthyHits))
}

// TestChaos_AllMirrorsPersistentFailCleanly — ALL mirrors persistently 503.
// Search must fail cleanly after trying every mirror (no hang, ctx-bounded,
// terminal ErrUnknown surfaced from the last mirror).
func TestChaos_AllMirrorsPersistentFailCleanly(t *testing.T) {
	var h1, h2 int64
	down1 := always503Mirror(t, &h1)
	down2 := always503Mirror(t, &h2)
	client := NewClientWithMirrors([]string{down1.URL, down2.URL})

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	t0 := time.Now()
	res, err := client.Search(ctx, "persistent", 0)
	elapsed := time.Since(t0)

	if err == nil {
		t.Fatalf("all-mirrors-503 returned nil error (res=%v)", res)
	}
	if !errors.Is(err, provider.ErrUnknown) {
		t.Fatalf("want ErrUnknown wrapped, got %v", err)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("all-mirrors failure took %s — failover not bounded (possible hang)", elapsed)
	}
	// Each mirror is tried exactly once (no in-mirror retry for YTS).
	if atomic.LoadInt64(&h1) != 1 || atomic.LoadInt64(&h2) != 1 {
		t.Errorf("mirror hit counts = (%d,%d), want (1,1)", h1, h2)
	}
	t.Logf("CHAOS yts persistent: clean failure in %s after trying %d mirrors (err=%v)", elapsed, 2, err)
}
