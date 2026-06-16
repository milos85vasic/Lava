package rutracker

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sort"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"digital.vasic.recovery/pkg/breaker"
)

// §11.4.85 STRESS + CHAOS coverage for the bounded-retry-INSIDE-the-breaker
// logic on the rutracker.org client (maxAttempts=3, 500ms backoff, transient =
// network/timeout-or-5xx, terminal never retried — see doWithRetry / Fetch in
// client.go). This is the circuit-breaker class exemplar: a recovered transient
// must NOT move the breaker toward OPEN (GetFailures stays 0, state stays
// Closed), because doWithRetry returns nil on eventual success so breaker.Execute
// sees a success.
//
// Anti-Bluff (§6.J): the SUT is the REAL rutracker.Client (NewClient) hitting a
// REAL httptest.Server. NO live calls. Primary assertions are on the
// USER-VISIBLE outcome (recovered HTTP 200 body / a clean error) AND the
// user-affecting breaker state (Closed ⇒ rutracker stays usable; Open ⇒ user is
// locked out for 10s). Secondary on counts/latency.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2): break the retry by making doWithRetry
// single-attempt (replace its loop with `transient, err := attempt(); _ =
// transient; return err`). TestStress_ConcurrentFlakyUpstream and
// TestChaos_TransientKeepsBreakerClosed then FAIL: the flaky upstream's 503s are
// surfaced instead of retried, so (a) not all calls recover to HTTP 200, and (b)
// each surfaced 503 ticks the breaker's failure counter → GetFailures() != 0 and
// the breaker eventually trips OPEN. Revert → pass. (Confirmed live below in the
// deliverable.)

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

// flakyUpstream serves an intermittent 503 → 200 fault. Every `failEvery`-th hit
// (shared atomic, evenly spaced) returns 503; the rest return a 200 HTML body.
// With the in-breaker retry (3 attempts), a caller drawing one 503 retries onto
// a 200 and recovers WITHOUT the breaker counting a failure.
func flakyUpstream(t *testing.T, failEvery int64) (*httptest.Server, *int64, *int64) {
	t.Helper()
	var hits, fails int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&hits, 1)
		if failEvery > 0 && n%failEvery == 0 {
			atomic.AddInt64(&fails, 1)
			http.Error(w, "rutracker flaky", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=UTF-8")
		_, _ = w.Write([]byte("<html><body>ok</body></html>"))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits, &fails
}

// TestStress_ConcurrentFlakyUpstream — N>=50 concurrent Fetch() calls against an
// upstream that intermittently 503s (every 9th hit). With the in-breaker retry,
// every call MUST recover to a user-visible HTTP 200 body, and the breaker MUST
// stay Closed with 0 failures (a recovered transient is not a breaker failure).
// Asserts: no deadlock, no goroutine leak, all succeed, breaker Closed/0.
// Records p50/p95.
func TestStress_ConcurrentFlakyUpstream(t *testing.T) {
	const concurrency = 60
	srv, hits, fails := flakyUpstream(t, 9)

	// One shared client (matches production: one breaker fields all rutracker
	// traffic). The shared breaker is exactly what the test inspects.
	c := NewClient(srv.URL)
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
			body, status, err := c.Fetch(ctx, "/forum/index.php", "")
			elapsed := time.Since(t0)
			mu.Lock()
			lats = append(lats, elapsed)
			if err != nil && errFirst == nil {
				errFirst = err
			} else if err == nil && (status != http.StatusOK || len(body) == 0) && errFirst == nil {
				errFirst = fmt.Errorf("recovered call returned status=%d bodyLen=%d", status, len(body))
			}
			mu.Unlock()
			if err == nil && status == http.StatusOK && len(body) > 0 {
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
		t.Fatal("DEADLOCK: concurrent Fetch() calls did not complete within 25s")
	}

	if errFirst != nil {
		t.Fatalf("not all concurrent calls recovered: first error = %v (hits=%d fails=%d breaker=%s/%d)",
			errFirst, atomic.LoadInt64(hits), atomic.LoadInt64(fails), c.breaker.GetState(), c.breaker.GetFailures())
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d", got, concurrency)
	}
	// USER-AFFECTING breaker invariant: recovered transients keep the breaker
	// Closed (rutracker stays usable) with 0 consecutive failures.
	if st := c.breaker.GetState(); st != breaker.StateClosed {
		t.Fatalf("breaker state = %s, want Closed (recovered transients must not trip it)", st)
	}
	if f := c.breaker.GetFailures(); f != 0 {
		t.Fatalf("breaker GetFailures() = %d, want 0 (a recovered retry is not a breaker failure)", f)
	}

	c.http.CloseIdleConnections()
	after := settleGoroutines()
	if after > before+5 {
		t.Errorf("goroutine leak: before=%d after=%d (delta=%d)", before, after, after-before)
	}

	t.Logf("STRESS rutracker: concurrency=%d ok=%d upstream_hits=%d upstream_503s=%d breaker=%s/%d p50=%s p95=%s goroutines before=%d after=%d",
		concurrency, atomic.LoadInt64(&okCount), atomic.LoadInt64(hits), atomic.LoadInt64(fails),
		c.breaker.GetState(), c.breaker.GetFailures(), percentile(lats, 0.50), percentile(lats, 0.95), before, after)
}

// TestChaos_TransientKeepsBreakerClosed — inject a transient fault (503 on the
// FIRST hit of each fresh client, then 200) under concurrency; assert every call
// recovers to HTTP 200 AND each call's breaker stays Closed with 0 failures.
// A separate client+breaker per call isolates the breaker invariant per call.
func TestChaos_TransientKeepsBreakerClosed(t *testing.T) {
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
				w.Header().Set("Content-Type", "text/html; charset=UTF-8")
				_, _ = w.Write([]byte("<html><body>ok</body></html>"))
			}))
			defer srv.Close()

			c := NewClient(srv.URL)
			body, status, err := c.Fetch(ctx, "/forum/index.php", "")
			if err != nil {
				firstErr.CompareAndSwap(nil, err)
				return
			}
			if status != http.StatusOK || len(body) == 0 {
				firstErr.CompareAndSwap(nil, fmt.Errorf("status=%d bodyLen=%d", status, len(body)))
				return
			}
			// The 503 was retried away INSIDE the breaker, so breaker.Execute saw
			// a success: state Closed, 0 failures.
			if c.breaker.GetState() != breaker.StateClosed {
				firstErr.CompareAndSwap(nil, fmt.Errorf("breaker = %s, want Closed", c.breaker.GetState()))
				return
			}
			if c.breaker.GetFailures() != 0 {
				firstErr.CompareAndSwap(nil, fmt.Errorf("breaker failures = %d, want 0", c.breaker.GetFailures()))
				return
			}
			atomic.AddInt64(&okCount, 1)
		}()
	}
	wg.Wait()

	if v := firstErr.Load(); v != nil {
		t.Fatalf("transient 503->200 did not recover with breaker Closed/0: %v", v)
	}
	if got := atomic.LoadInt64(&okCount); got != concurrency {
		t.Fatalf("okCount = %d, want %d", got, concurrency)
	}
	t.Logf("CHAOS rutracker transient: %d/%d calls recovered with breaker Closed and 0 failures", okCount, concurrency)
}

// TestChaos_PersistentFailsCleanly — an upstream that ALWAYS 503s must fail
// cleanly after maxAttempts (no hang, ctx-bounded). After a single such call the
// breaker counts exactly ONE failure (MaxFailures=5, so it stays Closed — not
// yet tripped). Asserts the call returns a non-nil error within a bounded time.
func TestChaos_PersistentFailsCleanly(t *testing.T) {
	var hits int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&hits, 1)
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	t0 := time.Now()
	_, status, err := c.Fetch(ctx, "/forum/index.php", "")
	elapsed := time.Since(t0)

	if err == nil {
		t.Fatalf("persistent 503 returned nil error (status=%d)", status)
	}
	// 3 attempts with 2x500ms backoff ~= 1s; must NOT hang to the ctx deadline
	// or the client's 30s HTTP timeout.
	if elapsed > 5*time.Second {
		t.Fatalf("persistent failure took %s — retry not bounded (possible hang)", elapsed)
	}
	if got := atomic.LoadInt64(&hits); got != maxAttempts {
		t.Errorf("upstream hits = %d, want maxAttempts=%d (all transient attempts exhausted)", got, maxAttempts)
	}
	// One genuine (post-retry) failure reaches the breaker: 1 failure, still Closed.
	if f := c.breaker.GetFailures(); f != 1 {
		t.Errorf("breaker failures = %d, want 1 (one genuine post-retry failure)", f)
	}
	if st := c.breaker.GetState(); st != breaker.StateClosed {
		t.Errorf("breaker state = %s, want Closed (1 < MaxFailures=5)", st)
	}
	t.Logf("CHAOS rutracker persistent: clean failure in %s after %d attempts (breaker=%s/%d err=%v)",
		elapsed, hits, c.breaker.GetState(), c.breaker.GetFailures(), err)
}

// TestChaos_PersistentTripsBreakerOpen — sustained persistent 5xx eventually
// trips the breaker OPEN (after MaxFailures=5 genuine post-retry failures),
// which is the user-visible "rutracker temporarily unavailable (503)" outcome.
// Once OPEN, further calls fail fast with ErrCircuitOpen (no upstream hit) — the
// breaker protecting the user from hammering a dead upstream. Asserts the
// transition and the fast-fail.
func TestChaos_PersistentTripsBreakerOpen(t *testing.T) {
	var hits int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&hits, 1)
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// 5 genuine post-retry failures trip the breaker (MaxFailures=5).
	for i := 0; i < 5; i++ {
		_, _, err := c.Fetch(ctx, "/forum/index.php", "")
		if err == nil {
			t.Fatalf("call %d: expected error from persistent 503", i)
		}
	}
	if st := c.breaker.GetState(); st != breaker.StateOpen {
		t.Fatalf("after 5 failures breaker = %s, want Open", st)
	}

	// User-visible OPEN-breaker contract: the next call fails FAST, with a
	// non-nil error, WITHOUT contacting the (dead) upstream — protecting the
	// user from hammering it. (The breaker surfaces its own open-state error; the
	// route layer maps it to provider.ErrCircuitOpen → HTTP 503.)
	hitsBefore := atomic.LoadInt64(&hits)
	t0 := time.Now()
	_, _, err := c.Fetch(ctx, "/forum/index.php", "")
	elapsed := time.Since(t0)
	if err == nil {
		t.Fatal("OPEN-state call returned nil error; want fast-fail")
	}
	if atomic.LoadInt64(&hits) != hitsBefore {
		t.Errorf("OPEN breaker still hit upstream: hits went %d -> %d (should fail fast)", hitsBefore, atomic.LoadInt64(&hits))
	}
	if elapsed > 100*time.Millisecond {
		t.Errorf("OPEN-state call took %s — should fail fast without upstream contact", elapsed)
	}
	t.Logf("CHAOS rutracker breaker-trip: OPEN after 5 failures, then fast-fails in %s with no upstream hit (err=%v)", elapsed, err)
}
