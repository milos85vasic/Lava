package v1

// Stress + Chaos: upstream-fault injection against the REAL single-provider
// search read path (SearchHandler.GetSearch), per HelixConstitution §11.4.85
// (Stress + Chaos Test Mandate) and the Anti-Bluff Pact (§6.J / §6.L). This
// test targets the config-driven server-side search DEADLINE that GetSearch
// applies via context.WithTimeout(ctx, h.searchTimeout) (search.go:88), the
// engine-side fix for LVA-083 H2.
//
// WHY THIS DIMENSION IS A GENUINE GAP (assessed 2026-06-26, LVA-7):
//   - search_thundering_herd_test.go drives the read-through cache with a FAST
//     loader (5ms) and IDENTICAL keys — it never exercises the deadline path
//     (a 5ms loader never trips an 18s/150ms timeout) and never injects an
//     upstream FAULT (slow / error / degenerate). It proves cache convergence,
//     not graceful degradation.
//   - jackett_stress_chaos_test.go stresses a DIFFERENT handler
//     (JackettHandler.GetSearch), not the registry-mounted SearchHandler, and
//     does not exercise SearchHandler's config-driven h.searchTimeout at all.
//   - No existing test proves that when the upstream provider is SLOW BEYOND
//     THE DEADLINE, GetSearch returns promptly (bounded by h.searchTimeout)
//     instead of hanging until the provider eventually returns — which is the
//     exact failure mode the deadline was added to prevent (a failover loop of
//     perAttemptTimeout(8s) × N mirrors ≈ 40s > the client's 30s readTimeout,
//     surfacing as "No results" on the device).
//
// This test fills that gap. It drives the REAL SearchHandler.GetSearch (mounted
// by the REAL Register) over the REAL Gin engine, with a SHORT Deps.SearchTimeout
// (the production config knob LAVA_API_SEARCH_TIMEOUT, injected via Deps), under
// a concurrent burst that cycles through four upstream fault modes. Only the
// upstream provider.Search is faked — it IS the external-tracker boundary. Every
// request uses a DISTINCT cache key (the mode is embedded in the query string),
// so the cache never serves a hit that would mask the fault: every request truly
// traverses the deadline-bounded provider call.
//
// ADVERSE CONDITIONS EXERCISED (cycled per request, deterministic by query prefix):
//   - "slow"   : provider sleeps slowUpstream (≫ deadline) but HONORS ctx →
//                the deadline MUST fire, GetSearch MUST return an error status
//                bounded by the deadline (no hang). [the load-bearing case]
//   - "err403" : provider returns provider.ErrForbidden → mapped to HTTP 403.
//   - "err502" : provider returns a generic (non-sentinel) error → HTTP 502.
//   - "empty"  : provider returns a degenerate/garbage-shaped result (nil
//                Results) with nil error → HTTP 200 with no panic (graceful).
//
// PRIMARY (user-visible) ASSERTIONS — §6.AB / Sixth Law clause 3:
//   1. No hang: EVERY request returns, and EVERY request's wall latency is
//      strictly bounded (< maxPerReqLatency) — the deadline caps the slow case.
//   2. No panic: the engine has NO Recovery middleware, so any panic inside the
//      handler under chaos crashes the test (a real, un-swallowed failure).
//   3. Correct degradation status PER fault mode (403 / 502 / 502 / 200).
//   4. No goroutine leak (settle + NumGoroutine within tolerance) — a leaked
//      goroutine per timed-out request would be the canonical deadline-handling
//      bug; this asserts the cancelled provider goroutines actually unwind.
//   5. No data race (run under `go test -race`).
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.J):
//   Mutation: in internal/handlers/v1/search.go GetSearch, replace
//     `searchCtx, searchCancel := context.WithTimeout(c.Request.Context(), h.searchTimeout)`
//     with `searchCtx := c.Request.Context(); searchCancel := func() {}` (remove
//     the deadline). The httptest request context has NO deadline, so the "slow"
//     provider sleeps the FULL slowUpstream before returning.
//   Observed: the bounded-latency assertion fires for every slow request, e.g.
//     "slow request NOT bounded by deadline: latency=4.00s exceeds
//      maxPerReqLatency=2.5s (deadline=150ms); handler hung on slow upstream".
//   Reverted: yes — the committed file reflects unmutated production code.

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/storage"
)

// chaosSearchProvider is the upstream-loader boundary. It embeds
// countingSearchProvider (defined in search_thundering_herd_test.go) to inherit
// the full provider.Provider stub surface, and overrides Search to inject the
// four fault modes selected deterministically by the query prefix. The "slow"
// mode HONORS the context (a well-behaved upstream), so the handler's deadline
// is what bounds the call — exactly the production contract the deadline relies
// on. atomic counters record how many times each mode was actually exercised
// (coverage honesty, recorded as evidence facts).
type chaosSearchProvider struct {
	countingSearchProvider
	slowUpstream time.Duration

	slowCalls  int64
	err403Call int64
	err502Call int64
	emptyCall  int64
}

func (p *chaosSearchProvider) Search(ctx context.Context, opts provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	switch {
	case strings.HasPrefix(opts.Query, "slow"):
		atomic.AddInt64(&p.slowCalls, 1)
		// Well-behaved-but-slow upstream: it would take slowUpstream to answer,
		// but it respects cancellation. The handler's WithTimeout deadline MUST
		// fire first and return ctx.Err() promptly. If the deadline were absent,
		// this select blocks for the full slowUpstream.
		select {
		case <-time.After(p.slowUpstream):
			return p.result, nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	case strings.HasPrefix(opts.Query, "err403"):
		atomic.AddInt64(&p.err403Call, 1)
		return nil, provider.ErrForbidden
	case strings.HasPrefix(opts.Query, "err502"):
		atomic.AddInt64(&p.err502Call, 1)
		return nil, errors.New("upstream returned malformed/garbage response")
	default: // "empty"
		atomic.AddInt64(&p.emptyCall, 1)
		// Degenerate/garbage-shaped but technically-valid result (nil Results):
		// the handler must marshal+return it WITHOUT panicking.
		return &provider.SearchResult{Provider: p.id, Page: 1, Results: nil}, nil
	}
}

// setupChaosDeadlineRouter wires the REAL v1 Register (and thus the REAL
// SearchHandler.GetSearch deadline path) with a SHORT, config-driven
// Deps.SearchTimeout. The fake provider is injected via the group middleware
// (the same nil-registry harness setupTestRouter uses). The cache is the REAL
// SQLite-backed storage.Storage (the production cache, concurrency-safe — NOT
// the in-memory fakeCache, whose unsynchronized map would itself race under the
// concurrent burst). The cache is NOT the surface under test here; the
// deadline-bounded provider call is — distinct keys per request guarantee every
// request misses the cache and traverses the fault path.
func setupChaosDeadlineRouter(t *testing.T, p provider.Provider, timeout time.Duration) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)

	dbPath := filepath.Join(t.TempDir(), "chaos-deadline-cache.db")
	st, err := storage.NewSQLite(dbPath)
	if err != nil {
		t.Fatalf("storage.NewSQLite: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	r := gin.New() // NO Recovery middleware: a handler panic under chaos crashes the test.
	g := r.Group("/v1/:provider")
	g.Use(func(c *gin.Context) {
		c.Set("__provider__", p)
		c.Next()
	})
	Register(g, &Deps{Cache: st, SearchTimeout: timeout}, nil)
	return r
}

// chaosMode pairs a request's query prefix with the HTTP status the handler MUST
// return for that fault, so each test goroutine can assert deterministically.
type chaosMode struct {
	prefix     string
	wantStatus int
}

// TestStress_Chaos_SearchUpstreamFaults drives a concurrent burst of
// fault-injecting requests through the REAL deadline-bounded GetSearch and
// asserts graceful degradation: bounded latency (no hang), correct per-fault
// status, no panic, no goroutine leak. See the file header for the full
// anti-bluff rationale and the falsifiability rehearsal.
func TestStress_Chaos_SearchUpstreamFaults(t *testing.T) {
	const (
		workers          = 32
		perWorker        = 8 // 256 total fault-injecting requests
		deadline         = 150 * time.Millisecond
		slowUpstream     = 4 * time.Second         // ≫ deadline → deadline must win
		maxPerReqLatency = 2500 * time.Millisecond // ≫ deadline, ≪ slowUpstream
	)

	modes := []chaosMode{
		{prefix: "slow", wantStatus: http.StatusBadGateway},   // ctx.DeadlineExceeded → default → 502
		{prefix: "err403", wantStatus: http.StatusForbidden},  // sentinel → 403
		{prefix: "err502", wantStatus: http.StatusBadGateway}, // non-sentinel → 502
		{prefix: "empty", wantStatus: http.StatusOK},          // degenerate-but-valid → 200
	}

	cp := &chaosSearchProvider{slowUpstream: slowUpstream}
	cp.id = "test"
	cp.result = &provider.SearchResult{Provider: "test", Page: 1, TotalPages: 1}
	router := setupChaosDeadlineRouter(t, cp, deadline)

	baseline := settleGoroutinesHandler()

	var (
		wg           sync.WaitGroup
		start        = make(chan struct{}) // release all goroutines at once
		errCh        = make(chan error, workers*perWorker)
		completed    int64
		maxLatencyNs int64 // atomic max observed per-request latency
	)

	recordMax := func(d time.Duration) {
		ns := int64(d)
		for {
			cur := atomic.LoadInt64(&maxLatencyNs)
			if ns <= cur || atomic.CompareAndSwapInt64(&maxLatencyNs, cur, ns) {
				return
			}
		}
	}

	overallStart := time.Now()
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			<-start
			for i := 0; i < perWorker; i++ {
				m := modes[(id+i)%len(modes)] // deterministic, all modes covered
				// Distinct key per request → guaranteed cache miss → the fault
				// path (and the deadline) is exercised every time.
				q := fmt.Sprintf("%s-%d-%d", m.prefix, id, i)
				req := httptest.NewRequest(http.MethodGet, "/v1/test/search?query="+q+"&page=1", nil)
				rec := httptest.NewRecorder()

				reqStart := time.Now()
				router.ServeHTTP(rec, req) // panic here crashes the test (no Recovery)
				latency := time.Since(reqStart)
				recordMax(latency)

				// PRIMARY 1: no hang — every request bounded by the deadline,
				// even the slow-upstream one. This is the load-bearing assertion
				// the falsifiability mutation targets.
				if latency > maxPerReqLatency {
					errCh <- fmt.Errorf("worker %d iter %d (%s): request NOT bounded by deadline: "+
						"latency=%s exceeds maxPerReqLatency=%s (deadline=%s); handler hung on upstream",
						id, i, m.prefix, latency, maxPerReqLatency, deadline)
					return
				}
				// PRIMARY 3: correct degradation status per fault mode.
				if rec.Code != m.wantStatus {
					errCh <- fmt.Errorf("worker %d iter %d (%s): status=%d body=%q, want %d",
						id, i, m.prefix, rec.Code, rec.Body.String(), m.wantStatus)
					return
				}
				atomic.AddInt64(&completed, 1)
			}
		}(w)
	}
	close(start) // fire the chaos burst
	wg.Wait()
	close(errCh)
	overall := time.Since(overallStart)

	for e := range errCh {
		t.Errorf("chaos degradation failure: %v", e)
	}

	totalReqs := int64(workers * perWorker)
	if got := atomic.LoadInt64(&completed); got != totalReqs {
		t.Errorf("not all requests degraded gracefully: completed=%d/%d", got, totalReqs)
	}

	// Coverage honesty (§6.J): every fault mode MUST have actually been hit, or
	// the green is a no-signal bluff (a mode that never ran asserts nothing).
	slowN := atomic.LoadInt64(&cp.slowCalls)
	err403N := atomic.LoadInt64(&cp.err403Call)
	err502N := atomic.LoadInt64(&cp.err502Call)
	emptyN := atomic.LoadInt64(&cp.emptyCall)
	if slowN == 0 || err403N == 0 || err502N == 0 || emptyN == 0 {
		t.Errorf("fault-mode coverage gap: slow=%d err403=%d err502=%d empty=%d (each must be >0)",
			slowN, err403N, err502N, emptyN)
	}

	// PRIMARY 4: no goroutine leak — cancelled slow-provider goroutines must
	// unwind after the deadline fires.
	after := settleGoroutinesHandler()
	const tolerance = 25
	if after > baseline+tolerance {
		t.Errorf("goroutine leak: baseline=%d after=%d (tolerance=%d); "+
			"deadline-cancelled provider goroutines did not unwind", baseline, after, tolerance)
	}

	maxLatency := time.Duration(atomic.LoadInt64(&maxLatencyNs))
	t.Logf("chaos search faults: %d reqs (slow=%d err403=%d err502=%d empty=%d); "+
		"deadline=%s slowUpstream=%s; max-per-req-latency=%s (bound=%s); overall=%s; "+
		"goroutines baseline=%d after=%d",
		totalReqs, slowN, err403N, err502N, emptyN, deadline, slowUpstream,
		maxLatency, maxPerReqLatency, overall, baseline, after)

	writeHandlerEvidence(t, "stress-chaos-search-upstream-faults.json", map[string]any{
		"test":                        "TestStress_Chaos_SearchUpstreamFaults",
		"clause":                      "HelixConstitution §11.4.85 (Stress + Chaos) + §6.J/§6.L Anti-Bluff",
		"surface":                     "GET /v1/{provider}/search via REAL Register→SearchHandler.GetSearch with config-driven Deps.SearchTimeout deadline; only upstream provider.Search faked",
		"dimension":                   "upstream-fault injection under concurrent load (slow-beyond-deadline / error / degenerate)",
		"workers":                     workers,
		"calls_per_worker":            perWorker,
		"total_requests":              totalReqs,
		"deadline_ms":                 deadline.Milliseconds(),
		"slow_upstream_ms":            slowUpstream.Milliseconds(),
		"max_per_req_latency_ms":      maxPerReqLatency.Milliseconds(),
		"observed_max_latency_ms":     maxLatency.Milliseconds(),
		"latency_bounded_by_deadline": maxLatency <= maxPerReqLatency,
		"overall_wall_ms":             overall.Milliseconds(),
		"fault_mode_calls":            map[string]int64{"slow": slowN, "err403": err403N, "err502": err502N, "empty": emptyN},
		"all_degraded_gracefully":     atomic.LoadInt64(&completed) == totalReqs,
		"goroutines_baseline":         baseline,
		"goroutines_after":            after,
		"goroutine_leak":              after > baseline+tolerance,
		"race_detector":               "run under `go test -race`",
		"captured_at":                 time.Now().UTC().Format(time.RFC3339),
	})
}
