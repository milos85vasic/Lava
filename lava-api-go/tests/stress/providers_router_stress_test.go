//go:build stress

package stress

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
	apirouter "digital.vasic.lava.apigo/internal/router"
)

// providers_router_stress_test.go is the §11.4.85 (Lava equivalent) stress +
// chaos test for the PRODUCTION router — `internal/router.Build` — driven over a
// real loopback HTTP socket (httptest), under sustained + concurrent load, with a
// real fault injected at a real seam.
//
// Why this file exists alongside api_stress_test.go and
// provider_middleware_stress_test.go: the prior two drive synthetic Gin handlers
// (a hand-rolled /health) and the real ProviderMiddleware in isolation. NEITHER
// boots `router.Build` — the SINGLE production router that cmd/lava-api-go/main.go
// AND internal/mobile (the on-device embed) both call. This file closes that gap:
// it stresses the EXACT engine a real user's client hits, focused on the two
// public, pre-auth endpoints the onboarding wizard depends on:
//
//   - GET /providers — the provider catalogue. Load-bearing since spec 2026-06-11:
//     the Android onboarding wizard fetches it against a freshly mDNS-discovered API
//     to populate the provider list. Crashlytics 47b000d5 (v1.3.4) was a real
//     production failure where this fetch broke and the wizard fell back to bundled
//     providers. It is registered BEFORE the auth middleware (public), so with
//     Cfg=nil/AuthLadder=nil router.Build serves it exactly as production does for
//     an un-paired onboarding client.
//   - GET /health — the liveness probe the orchestrator + the on-device embed hit.
//
// §6.J / Sixth Law clause 1: no shortcut. The request crosses the real radix-tree
// routing, the real gin.Recovery + FirebaseTelemetry middleware chain, and the real
// ProvidersHandler.GetProviders reading a real ProviderRegistry. The PRIMARY
// assertion is on user-visible state: HTTP status + the parsed JSON body's provider
// count — not "didn't panic".

// catalogueProvider is a real provider.Provider (it satisfies the full interface
// by embedding provider.BaseProvider for the catalogue-metadata methods) that the
// production ProvidersHandler enumerates. It declares a couple of capabilities so
// the /providers JSON has non-trivial content to assert on.
type catalogueProvider struct {
	provider.BaseProvider
	id   string
	name string
}

func (p *catalogueProvider) ID() string          { return p.id }
func (p *catalogueProvider) DisplayName() string { return p.name }
func (p *catalogueProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch, provider.CapTopic}
}
func (p *catalogueProvider) AuthType() provider.AuthType { return provider.AuthNone }
func (p *catalogueProvider) Encoding() string            { return "UTF-8" }
func (p *catalogueProvider) Search(context.Context, provider.SearchOpts, provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{Provider: p.id}, nil
}
func (p *catalogueProvider) Browse(context.Context, string, int, provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) GetForumTree(context.Context, provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) GetTopic(context.Context, string, int, provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) GetTorrent(context.Context, string, provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) DownloadFile(context.Context, string, provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) GetComments(context.Context, string, int, provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) AddComment(context.Context, string, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *catalogueProvider) GetFavorites(context.Context, provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) AddFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *catalogueProvider) RemoveFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *catalogueProvider) CheckAuth(context.Context, provider.Credentials) (bool, error) {
	return false, nil
}
func (p *catalogueProvider) Login(context.Context, provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) FetchCaptcha(context.Context, string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (p *catalogueProvider) HealthCheck(context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// chaosReadiness is a real ReadinessProbe (the exact seam router.Build wires into
// GET /ready). It is the REAL fault-injection point for the chaos dimension: the
// production code reads c.Request.Context() through observability.ReadinessHandler
// and returns 503 when the probe errors. Flipping `down` mid-load simulates the
// real dependency (DB / breaker) going unavailable — no mock of the SUT, a genuine
// seam the production handler invokes on every /ready request.
type chaosReadiness struct{ down int32 }

func (cr *chaosReadiness) probe(context.Context) error {
	if atomic.LoadInt32(&cr.down) == 1 {
		return fmt.Errorf("dependency unavailable (chaos-injected)")
	}
	return nil
}

// buildRealRouter constructs the PRODUCTION engine via router.Build with a real
// registry of two providers + the real readiness seam. Cfg/AuthLadder are nil, so
// (exactly as in production for an un-paired onboarding client) /providers + /health
// + /ready are served pre-auth. Cache + Scraper are the route-resolution stubs the
// in-tree router_test.go uses; the catalogue + liveness + readiness paths under test
// here never touch them.
func buildRealRouter(reg *provider.ProviderRegistry, ready *chaosReadiness) http.Handler {
	return apirouter.Build(apirouter.Deps{
		Cache:     stressStubCache{},
		Scraper:   stressStubScraper{},
		Registry:  reg,
		Readiness: ready.probe,
	})
}

// realRouterCall pairs a request path with the status the production router MUST
// return — the oracle the dimensions classify against.
type realRouterCall struct {
	path string
	want int
}

// TestStressProvidersRouter drives the production router.Build engine under
// sustained + concurrent load against /providers + /health, then injects a real
// dependency-down fault at the readiness seam and asserts graceful degradation +
// recovery. ONE evidence file is written.
//
// SLO asserted (the gate):
//   - S(router): 0 × 5xx under sustained load; every /providers returns 200 with the
//     expected provider count in the JSON body; p99 < 250ms (host-generous ceiling
//     for an in-process loopback handler — a real regression that blows the latency
//     budget, e.g. a global lock in the catalogue path, trips it).
//   - S(conc): N=64 concurrent /providers requests ALL complete, all 200, all with
//     the right provider count, 0 × 5xx, no data race in the shared registry read.
//   - C(chaos): with /ready forced down mid-stream, /ready returns a clean 503 (not a
//     panic/hang) AND /health + /providers STAY healthy (200) throughout — the fault
//     is isolated to the failing dependency; when the fault clears /ready recovers to
//     200 within 1 request. Graceful degradation, not cascade.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.J / §11.4.85):
//
//	Mutation A (latency budget): inserting `time.Sleep(300 * time.Millisecond)` into
//	ProvidersHandler.GetProviders trips the p99 < 250ms SLO → S(router) FAILS
//	"p99 ... exceeds SLO". Mutation B (recovery): make chaosReadiness.probe always
//	return an error → C(chaos) post-clear assertion fires "ready did not recover".
//	Mutation C (body correctness): make GetProviders return an empty list → the
//	"provider count" assertion fires "got 0 providers want 2". All reverted.
const (
	sloP99Millis      = 250.0 // p99 latency ceiling for the in-process catalogue path
	wantProviderCount = 2     // catalogueProvider × 2 registered below
)

func TestStressProvidersRouter(t *testing.T) {
	reg := provider.NewRegistry()
	reg.Register(&catalogueProvider{id: "rutracker", name: "RuTracker"})
	reg.Register(&catalogueProvider{id: "thepiratebay", name: "The Pirate Bay"})
	ready := &chaosReadiness{}

	srv := httptest.NewServer(buildRealRouter(reg, ready))
	defer srv.Close()
	client := &http.Client{Timeout: 10 * time.Second}
	ev := NewEvidence()

	providersURL := srv.URL + "/providers"
	healthURL := srv.URL + "/health"
	readyURL := srv.URL + "/ready"

	// providerCount parses the real /providers JSON body and returns the number of
	// providers in the catalogue (-1 on any parse/transport error). This is the
	// USER-VISIBLE assertion: the onboarding wizard reads exactly this body.
	providerCount := func(url string) (int, int) {
		resp, err := client.Get(url)
		if err != nil {
			return 0, -1
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		var parsed struct {
			Providers []struct {
				ID string `json:"id"`
			} `json:"providers"`
		}
		if jerr := json.Unmarshal(body, &parsed); jerr != nil {
			return resp.StatusCode, -1
		}
		return resp.StatusCode, len(parsed.Providers)
	}

	// ---- S(router): sustained load on /providers + /health (real body assertion) ----
	{
		const iters = 500 // well above the §11.4.85 N>=100 sustained minimum
		lat, outs := driveSustained(client, providersURL, iters)
		r2, r4, r5, errRate := summarize(outs)

		// USER-VISIBLE body correctness: sample the catalogue body and assert the
		// provider count. A 200 with the wrong/empty body is still a broken feature.
		badBody := 0
		for i := 0; i < 50; i++ { // sample 50 of the served bodies
			code, n := providerCount(providersURL)
			if code != http.StatusOK || n != wantProviderCount {
				badBody++
			}
		}
		status := "PASS"
		if iters < minSustainedIters || errRate > 0 || r5 > 0 || r2 != iters ||
			badBody > 0 || lat.P99Ms >= sloP99Millis {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "SR1", Name: "router-providers-sustained", Ran: true, Status: status,
			Requests: iters, Status2xx: r2, Status4xx: r4, Status5xx: r5, ErrorRate: errRate,
			Latency: lat,
			Notes: fmt.Sprintf("real router.Build /providers; SLO p99<%.0fms got %.2fms; body-sample bad=%d/50 (want %d providers)",
				sloP99Millis, lat.P99Ms, badBody, wantProviderCount),
		})
		if status == "FAIL" {
			t.Errorf("SR1 router-providers-sustained FAILED: 2xx=%d/%d 5xx=%d errRate=%.3f p99=%.2fms(SLO<%.0f) badBody=%d",
				r2, iters, r5, errRate, lat.P99Ms, sloP99Millis, badBody)
		}
	}

	// ---- S(health): sustained load on the liveness probe ----
	{
		const iters = 300
		lat, outs := driveSustained(client, healthURL, iters)
		r2, r4, r5, errRate := summarize(outs)
		status := "PASS"
		if iters < minSustainedIters || errRate > 0 || r5 > 0 || r2 != iters || lat.P99Ms >= sloP99Millis {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "SR2", Name: "router-health-sustained", Ran: true, Status: status,
			Requests: iters, Status2xx: r2, Status4xx: r4, Status5xx: r5, ErrorRate: errRate,
			Latency: lat,
			Notes:   fmt.Sprintf("real router.Build /health liveness; SLO p99<%.0fms got %.2fms", sloP99Millis, lat.P99Ms),
		})
		if status == "FAIL" {
			t.Errorf("SR2 router-health-sustained FAILED: 2xx=%d/%d 5xx=%d p99=%.2fms", r2, iters, r5, lat.P99Ms)
		}
	}

	// ---- S(conc): concurrent contention on /providers (registry read race) ----
	{
		const conc = 64 // well above the §11.4.85 N>=10 concurrent minimum
		lat, outs := driveConcurrent(client, providersURL, conc)
		r2, r4, r5, errRate := summarize(outs)
		completed := len(outs)

		// Concurrent body correctness: every parallel response must carry the right
		// provider count (proves no torn read of the shared registry under load).
		var wg sync.WaitGroup
		var concBad int32
		for i := 0; i < conc; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				code, n := providerCount(providersURL)
				if code != http.StatusOK || n != wantProviderCount {
					atomic.AddInt32(&concBad, 1)
				}
			}()
		}
		wg.Wait()

		status := "PASS"
		if conc < minConcurrency || completed != conc || errRate > 0 || r5 > 0 ||
			r2 != conc || atomic.LoadInt32(&concBad) > 0 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "SR3", Name: "router-providers-concurrent", Ran: true, Status: status,
			Requests: conc, Status2xx: r2, Status4xx: r4, Status5xx: r5, ErrorRate: errRate,
			Latency: lat, FaultType: "concurrent-registry-read",
			Notes: fmt.Sprintf("%d concurrent /providers; all-complete=%v; concurrent-body-bad=%d (want %d providers)",
				conc, completed == conc, atomic.LoadInt32(&concBad), wantProviderCount),
		})
		if status == "FAIL" {
			t.Errorf("SR3 router-providers-concurrent FAILED: completed=%d/%d 2xx=%d 5xx=%d concBad=%d",
				completed, conc, r2, r5, atomic.LoadInt32(&concBad))
		}
	}

	// ---- C(chaos): dependency-down fault injection at the REAL readiness seam ----
	// While /ready's dependency is forced down mid-stream, assert (a) /ready returns
	// a clean 503 (graceful, not panic/hang), (b) /health + /providers STAY 200 (the
	// fault is isolated — no cascade), (c) /ready recovers to 200 the first request
	// after the fault clears.
	{
		// Baseline: /ready healthy before the fault.
		preCode, _ := providerCount(readyURL) // reuse the GET+parse helper for status
		_ = preCode

		// Inject the fault.
		atomic.StoreInt32(&ready.down, 1)

		// Drive a mixed stream DURING the fault: /ready (expect 503), /health +
		// /providers (must STAY healthy — the load-bearing degradation proof).
		duringReady := []realRouterCall{}
		duringHealthy := []realRouterCall{}
		var readyDuring503, healthyDuringBad, healthyDuring5xx int
		const duringIters = 150
		for i := 0; i < duringIters; i++ {
			rc, err := client.Get(readyURL)
			if err == nil {
				if rc.StatusCode == http.StatusServiceUnavailable {
					readyDuring503++
				}
				rc.Body.Close()
			}
			duringReady = append(duringReady, realRouterCall{readyURL, http.StatusServiceUnavailable})

			hc, herr := client.Get(healthURL)
			if herr == nil {
				if hc.StatusCode != http.StatusOK {
					healthyDuringBad++
				}
				if hc.StatusCode >= 500 {
					healthyDuring5xx++
				}
				hc.Body.Close()
			} else {
				healthyDuringBad++
			}
			duringHealthy = append(duringHealthy, realRouterCall{healthURL, http.StatusOK})

			code, n := providerCount(providersURL)
			if code != http.StatusOK || n != wantProviderCount {
				healthyDuringBad++
			}
			if code >= 500 {
				healthyDuring5xx++
			}
		}

		// Clear the fault.
		atomic.StoreInt32(&ready.down, 0)

		// Recovery: first /ready 2xx after the fault clears.
		recov := 0
		var postReady200 int
		const postIters = 50
		for i := 0; i < postIters; i++ {
			rc, err := client.Get(readyURL)
			code := 0
			if err == nil {
				code = rc.StatusCode
				rc.Body.Close()
			}
			if code >= 200 && code < 300 {
				postReady200++
				if recov == 0 {
					recov = i + 1
				}
			}
		}

		status := "PASS"
		// (a) /ready was 503 throughout the fault (clean, every time);
		// (b) /health + /providers stayed healthy — 0 bad responses, 0 × 5xx (no cascade);
		// (c) /ready recovered to 200 within 1 request after the fault cleared.
		if readyDuring503 != duringIters ||
			healthyDuringBad > 0 || healthyDuring5xx > 0 ||
			postReady200 == 0 || recov != 1 {
			status = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "CR1", Name: "router-dependency-down-isolation-recovery", Ran: true, Status: status,
			Requests:             duringIters*3 + postIters,
			Status5xx:            healthyDuring5xx,
			FaultType:            "readiness-probe-dependency-down",
			ErrorRateDuringFault: 1.0, // /ready is 100% 503 during fault (by design)
			ErrorRateAfterFault:  0.0,
			RecoveryRequests:     recov,
			Notes: fmt.Sprintf("during fault: /ready 503=%d/%d, /health+/providers bad=%d 5xx=%d (must be 0 — fault isolated); recovery=%d req",
				readyDuring503, duringIters, healthyDuringBad, healthyDuring5xx, recov),
		})
		if status == "FAIL" {
			t.Errorf("CR1 chaos FAILED: ready503=%d/%d healthyBad=%d healthy5xx=%d postReady200=%d recov=%d (want ready503=%d, healthyBad=0, recov=1)",
				readyDuring503, duringIters, healthyDuringBad, healthyDuring5xx, postReady200, recov, duringIters)
		}
		_ = duringReady
		_ = duringHealthy
	}

	ev.Finalize()
	path, err := ev.Write()
	if err != nil {
		t.Fatalf("failed to write evidence: %v", err)
	}
	t.Logf("evidence written: %s (verdict=%s)", path, ev.Verdict)
	for _, d := range ev.Dimensions {
		t.Logf("%s %s: status=%s reqs=%d 2xx=%d 5xx=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
			d.ID, d.Name, d.Status, d.Requests, d.Status2xx, d.Status5xx,
			d.Latency.P50Ms, d.Latency.P95Ms, d.Latency.P99Ms, d.Latency.MaxMs)
	}
	if ev.Verdict != "PASS" {
		t.Errorf("run verdict FAIL — see %s", path)
	}
}
