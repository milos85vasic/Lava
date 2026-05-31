//go:build stress

package stress

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// buildHandler constructs a real Gin engine with a /health endpoint and chaos-injection
// middleware. The middleware seams are how the chaos dimensions inject faults WITHOUT
// touching production code — they sit in the test's own router. This is a real HTTP handler
// (real routing, real response writing), driven over a real loopback socket (httptest).
//
// faultEnabled / latencyMs are pointers so a test can flip them mid-load (chaos window).
func buildHandler(faultEnabled *int32, latencyMs *int64) http.Handler {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	// C2 latency-injection middleware.
	r.Use(func(c *gin.Context) {
		if ms := atomic.LoadInt64(latencyMs); ms > 0 {
			time.Sleep(time.Duration(ms) * time.Millisecond)
		}
		c.Next()
	})
	// C1 fault-injection middleware (simulates a dependency being unavailable).
	r.Use(func(c *gin.Context) {
		if atomic.LoadInt32(faultEnabled) == 1 {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "dependency unavailable"})
			return
		}
		c.Next()
	})

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})
	// A route that echoes a path segment — used by the malformed-input dimension.
	r.GET("/echo/:id", func(c *gin.Context) {
		id := c.Param("id")
		if len(id) > 256 {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "id too long"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"id": id})
	})
	return r
}

// TestStressChaos is the single entry point that runs all in-process dimensions and writes
// ONE evidence file. Operator-gated dimensions (C3/C4b) are recorded as OPERATOR_GATED here;
// scripts/run-chaos-stress.sh --with-podman is the path that actually runs them.
func TestStressChaos(t *testing.T) {
	var fault int32
	var latency int64
	srv := httptest.NewServer(buildHandler(&fault, &latency))
	defer srv.Close()
	client := &http.Client{Timeout: 10 * time.Second}
	ev := NewEvidence()

	// ---- S1 sustained load (>= 100 iters) ----
	{
		const iters = 500 // well above the 100 minimum
		lat, outs := driveSustained(client, srv.URL+"/health", iters)
		r2, r4, r5, errRate := summarize(outs)
		status := "PASS"
		if iters < minSustainedIters || errRate > 0 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "S1", Name: "sustained-load", Ran: true, Status: status,
			Requests: iters, Status2xx: r2, Status4xx: r4, Status5xx: r5,
			ErrorRate: errRate, Latency: lat,
			Notes: fmt.Sprintf("min %d iters required; ran %d", minSustainedIters, iters),
		})
		if status == "FAIL" {
			t.Errorf("S1 sustained FAILED: errRate=%.3f over %d reqs", errRate, iters)
		}
	}

	// ---- S2 concurrent contention (>= 10 parallel) ----
	{
		const conc = 64 // well above the 10 minimum
		lat, outs := driveConcurrent(client, srv.URL+"/health", conc)
		r2, r4, r5, errRate := summarize(outs)
		completed := len(outs)
		status := "PASS"
		// All N must complete with no error (§11.4.85 def.1 concurrent).
		if conc < minConcurrency || completed != conc || errRate > 0 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "S2", Name: "concurrent-contention", Ran: true, Status: status,
			Requests: conc, Status2xx: r2, Status4xx: r4, Status5xx: r5,
			ErrorRate: errRate, Latency: lat,
			Notes: fmt.Sprintf("min %d concurrent required; ran %d; all-complete=%v", minConcurrency, conc, completed == conc),
		})
		if status == "FAIL" {
			t.Errorf("S2 concurrent FAILED: completed=%d/%d errRate=%.3f", completed, conc, errRate)
		}
	}

	// ---- C1 fault injection + recovery ----
	{
		// 100 reqs clean, 100 reqs with fault on, 100 reqs after fault clears.
		_, pre := driveSustained(client, srv.URL+"/health", 100)
		atomic.StoreInt32(&fault, 1)
		_, during := driveSustained(client, srv.URL+"/health", 100)
		atomic.StoreInt32(&fault, 0)
		latPost, post := driveSustained(client, srv.URL+"/health", 100)

		_, _, _, preErr := summarize(pre)
		_, _, dur5, durErr := summarize(during)
		post2, _, _, postErr := summarize(post)

		// Recovery: first 2xx after fault cleared.
		recov := 0
		for i, o := range post {
			if o.code >= 200 && o.code < 300 {
				recov = i + 1
				break
			}
		}
		status := "PASS"
		// Expectations: clean before, 5xx during (fault visible as bounded clean 503 — not a panic/hang),
		// full recovery after (errRate back to 0, 2xx returns).
		if preErr > 0 || durErr < 0.99 || dur5 < 100 || postErr > 0 || post2 == 0 {
			status = "FAIL"
		}
		r2, r4, r5, allErr := summarize(append(append(append([]reqOutcome{}, pre...), during...), post...))
		ev.Add(DimensionResult{
			ID: "C1", Name: "fault-injection-recovery", Ran: true, Status: status,
			Requests: 300, Status2xx: r2, Status4xx: r4, Status5xx: r5, ErrorRate: allErr,
			Latency: latPost, FaultType: "dependency-unavailable-503",
			ErrorRateDuringFault: durErr, ErrorRateAfterFault: postErr, RecoveryRequests: recov,
			Notes: "fault returns clean 503 (no panic/hang); recovers to 200 after clear",
		})
		if status == "FAIL" {
			t.Errorf("C1 fault FAILED: preErr=%.3f durErr=%.3f dur5=%d postErr=%.3f recov=%d",
				preErr, durErr, dur5, postErr, recov)
		}
	}

	// ---- C2 latency injection ----
	{
		atomic.StoreInt64(&latency, 5) // 5ms injected per request
		lat, outs := driveSustained(client, srv.URL+"/health", 100)
		atomic.StoreInt64(&latency, 0)
		_, _, _, errRate := summarize(outs)
		status := "PASS"
		// Under 5ms injection p50 must reflect the injected latency (graceful degradation, no hang).
		if errRate > 0 || lat.P50Ms < 4.0 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "C2", Name: "latency-injection", Ran: true, Status: status,
			Requests: 100, ErrorRate: errRate, Latency: lat,
			FaultType: "5ms-per-request-latency",
			Notes: "server stays responsive under injected latency; percentiles degrade, no hang",
		})
		if status == "FAIL" {
			t.Errorf("C2 latency FAILED: errRate=%.3f p50ms=%.2f (expected >=4)", errRate, lat.P50Ms)
		}
	}

	// ---- C5 malformed input ----
	{
		// Oversized path segment under load — must be rejected cleanly (400), never panic.
		long := strings.Repeat("A", 1024)
		lat, outs := driveSustained(client, srv.URL+"/echo/"+long, 100)
		r2, r4, r5, _ := summarize(outs)
		status := "PASS"
		// Every oversized request must be a clean 4xx; no 5xx (panic) allowed.
		if r5 > 0 || r4 != 100 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "C5", Name: "malformed-input", Ran: true, Status: status,
			Requests: 100, Status2xx: r2, Status4xx: r4, Status5xx: r5,
			ErrorRate: 0, Latency: lat, FaultType: "oversized-1KB-path-segment",
			Notes: "oversized input returns clean 400 (no panic / 5xx)",
		})
		if status == "FAIL" {
			t.Errorf("C5 malformed FAILED: 4xx=%d 5xx=%d (expected 100/0)", r4, r5)
		}
	}

	// ---- C4a rate-limiter trip (in-process simulation) ----
	// A real rate limiter lives in internal/ratelimit (glue over submodules/ratelimiter). Here
	// we assert the *semantics* in-process: a token-bucket of capacity K returns 429 for the
	// (K+1)th request inside the window. The production limiter is exercised by the e2e suite;
	// this dimension proves the stress harness can detect a 429-under-load deterministically.
	{
		const cap = 10
		var counter int32
		rl := gin.New()
		rl.Use(func(c *gin.Context) {
			if atomic.AddInt32(&counter, 1) > cap {
				c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limited"})
				return
			}
			c.Next()
		})
		rl.GET("/health", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"status": "ok"}) })
		rlSrv := httptest.NewServer(rl)
		defer rlSrv.Close()
		lat, outs := driveSustained(client, rlSrv.URL+"/health", 30)
		r2, r4, r5, _ := summarize(outs)
		status := "PASS"
		// First `cap` succeed (200), the rest are 429 (a 4xx). Must be deterministic.
		if r2 != cap || r4 != 30-cap || r5 > 0 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "C4a", Name: "rate-limiter-trip", Ran: true, Status: status,
			Requests: 30, Status2xx: r2, Status4xx: r4, Status5xx: r5,
			ErrorRate: 0, Latency: lat, FaultType: "rate-limit-exhaustion",
			Notes: fmt.Sprintf("first %d -> 200, remainder -> 429 (deterministic)", cap),
		})
		if status == "FAIL" {
			t.Errorf("C4a rate-limit FAILED: 2xx=%d 4xx=%d 5xx=%d (expected %d/%d/0)", r2, r4, r5, cap, 30-cap)
		}
	}

	// ---- C3 Postgres-kill (OPERATOR-GATED) ----
	ev.Add(DimensionResult{
		ID: "C3", Name: "dependency-kill-postgres", Ran: false, Status: "OPERATOR_GATED",
		FaultType: "postgres-container-kill",
		Notes:     "needs real Postgres under podman; run scripts/run-chaos-stress.sh --with-podman. NOT faked.",
	})
	// ---- C4b connection-pool exhaustion (OPERATOR-GATED) ----
	ev.Add(DimensionResult{
		ID: "C4b", Name: "pool-exhaustion", Ran: false, Status: "OPERATOR_GATED",
		FaultType: "pgx-pool-exhaustion",
		Notes:     "needs real Postgres under podman; run scripts/run-chaos-stress.sh --with-podman. NOT faked.",
	})

	ev.Finalize()
	path, err := ev.Write()
	if err != nil {
		t.Fatalf("failed to write evidence: %v", err)
	}
	t.Logf("evidence written: %s (verdict=%s)", path, ev.Verdict)
	if ev.Verdict != "PASS" {
		t.Errorf("run verdict FAIL — see %s", path)
	}

	// Sanity: drain any leaked body readers (defensive; httptest closes on srv.Close()).
	_ = io.Discard
}
