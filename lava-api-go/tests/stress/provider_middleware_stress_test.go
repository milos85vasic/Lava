//go:build stress

package stress

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/provider"
)

// stressProvider is a real provider.Provider declaring SEARCH (and TOPIC) but
// NOT BROWSE, so the supported/unsupported capability gate can be exercised
// under load. Only the methods the middleware path touches return meaningful
// values; the rest are honest ErrUnsupported (never invoked by the stress path).
//
// Embeds provider.BaseProvider for the catalogue-metadata methods
// (Kind/SupportsAnonymous/BaseURLs) added to the Provider interface by the
// 2026-06-11 dynamic-provider-discovery spec — so it still satisfies the current
// interface that reg.Register requires.
type stressProvider struct {
	provider.BaseProvider
	id string
}

func (p *stressProvider) ID() string          { return p.id }
func (p *stressProvider) DisplayName() string { return "Stress" }
func (p *stressProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch, provider.CapTopic}
}
func (p *stressProvider) AuthType() provider.AuthType { return provider.AuthNone }
func (p *stressProvider) Encoding() string            { return "UTF-8" }
func (p *stressProvider) Search(context.Context, provider.SearchOpts, provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{Provider: p.id}, nil
}
func (p *stressProvider) Browse(context.Context, string, int, provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) GetForumTree(context.Context, provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) GetTopic(context.Context, string, int, provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) GetTorrent(context.Context, string, provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) DownloadFile(context.Context, string, provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) GetComments(context.Context, string, int, provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) AddComment(context.Context, string, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *stressProvider) GetFavorites(context.Context, provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) AddFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *stressProvider) RemoveFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *stressProvider) CheckAuth(context.Context, provider.Credentials) (bool, error) {
	return false, nil
}
func (p *stressProvider) Login(context.Context, provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) FetchCaptcha(context.Context, string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (p *stressProvider) HealthCheck(context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// buildProviderEngine constructs a real Gin engine whose /v1/:provider routes
// are gated by the REAL middleware.ProviderMiddleware against a REAL
// provider.ProviderRegistry — the exact production dispatch chain (the same
// ProviderMiddleware + Registry that router.Build mounts on the v1 group).
//
// Two routes mirror two §6.E capability requirements:
//   - /v1/:provider/search requires CapSearch  (registered provider supports → 200)
//   - /v1/:provider/browse requires CapBrowse  (registered provider lacks    → 501)
//
// gin.Recovery() is installed so that any panic in the chain surfaces as a 500,
// which the stress assertions treat as a misclassification (never a hang).
func buildProviderEngine() http.Handler {
	gin.SetMode(gin.TestMode)
	reg := provider.NewRegistry()
	reg.Register(&stressProvider{id: "rutracker"})
	reg.Register(&stressProvider{id: "kinozal"})

	r := gin.New()
	r.Use(gin.Recovery())

	sg := r.Group("/v1/:provider/search")
	sg.Use(middleware.ProviderMiddleware(reg, provider.CapSearch))
	sg.GET("", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"provider": middleware.Current(c).ID()})
	})

	bg := r.Group("/v1/:provider/browse")
	bg.Use(middleware.ProviderMiddleware(reg, provider.CapBrowse))
	bg.GET("", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"provider": middleware.Current(c).ID()})
	})
	return r
}

// stressCall pairs a request URL with the status the production middleware MUST
// return for it. This is the oracle the stress dimension classifies against.
type stressCall struct {
	suffix string // appended to server URL
	want   int    // required HTTP status
	class  string // human label for evidence
}

// TestStressProviderMiddleware drives N>=100 mixed requests across known/unknown
// providers and supported/unsupported capabilities through the REAL
// ProviderMiddleware + REAL Gin router over a REAL loopback socket. EVERY request
// must receive its correct status (200/404/501); a single misclassification (or
// any 5xx) FAILS the dimension. Latency p50/p95/p99 are recorded; evidence JSON
// is written via the shared harness convention.
//
// FALSIFIABILITY (Sixth Law clause 2 / §6.J):
//   - Coverage break: delete the `!reg.Supports` guard in ProviderMiddleware →
//     the 501-expecting `browse` requests misclassify as 200 → this FAILS with
//     "misclassified ... want 501 got 200".
//   - Guard break: make Current() return a zero provider instead of panicking →
//     handler dispatch differs; classification drifts.
func TestStressProviderMiddleware(t *testing.T) {
	srv := httptest.NewServer(buildProviderEngine())
	defer srv.Close()
	client := &http.Client{Timeout: 10 * time.Second}
	ev := NewEvidence()

	// The mixed request matrix: known+capable (200), known+incapable (501),
	// unknown provider on a capable route (404), unknown provider on an
	// incapable route (404 — unknown is checked before capability).
	matrix := []stressCall{
		{"/v1/rutracker/search", http.StatusOK, "known+SEARCH"},
		{"/v1/kinozal/search", http.StatusOK, "known+SEARCH"},
		{"/v1/rutracker/browse", http.StatusNotImplemented, "known+!BROWSE"},
		{"/v1/kinozal/browse", http.StatusNotImplemented, "known+!BROWSE"},
		{"/v1/ghost/search", http.StatusNotFound, "unknown+SEARCH"},
		{"/v1/phantom/browse", http.StatusNotFound, "unknown+BROWSE"},
	}

	const total = 600 // 100 per matrix entry — well above the 100 minimum
	outs := make([]reqOutcome, 0, total)
	lats := make([]time.Duration, 0, total)
	misclassified := 0
	unexpected5xx := 0 // a 500 is NEVER expected by this matrix (501 IS expected and correct)
	var firstMis string
	classCounts := map[string]int{}

	start := time.Now()
	for i := 0; i < total; i++ {
		call := matrix[i%len(matrix)]
		t0 := time.Now()
		resp, err := client.Get(srv.URL + call.suffix)
		d := time.Since(t0)
		code := 0
		if err == nil {
			code = resp.StatusCode
			resp.Body.Close()
		}
		outs = append(outs, reqOutcome{dur: d, code: code, err: err})
		lats = append(lats, d)
		classCounts[call.class]++
		if code == http.StatusInternalServerError {
			unexpected5xx++ // gin.Recovery() turned a panic into 500 — a real fault
		}
		if code != call.want {
			misclassified++
			if firstMis == "" {
				firstMis = fmt.Sprintf("%s [%s]: got %d want %d (err=%v)",
					call.suffix, call.class, code, call.want, err)
			}
		}
	}
	lat := computeLatency(lats, time.Since(start))
	r2, r4, r5, errRate := summarize(outs)

	status := "PASS"
	// Correctness oracle: every request must land on its expected status
	// (200/404/501). 501 is an EXPECTED status here, so it is NOT a fault —
	// only an unexpected 500 (panic-recover) is. minSustained floor applies.
	if misclassified > 0 || unexpected5xx > 0 || total < minSustainedIters {
		status = "FAIL"
	}
	ev.Add(DimensionResult{
		ID: "P1", Name: "provider-middleware-mixed-dispatch", Ran: true, Status: status,
		Requests: total, Status2xx: r2, Status4xx: r4, Status5xx: r5,
		ErrorRate: errRate, Latency: lat, FaultType: "mixed-known-unknown-capable-incapable",
		Notes: fmt.Sprintf("%d req across %d classes %v; misclassified=%d; first=%q",
			total, len(matrix), classCounts, misclassified, firstMis),
	})
	if status == "FAIL" {
		t.Errorf("P1 provider-middleware FAILED: misclassified=%d unexpected5xx=%d first=%q",
			misclassified, unexpected5xx, firstMis)
	}

	// Concurrent dimension: same matrix, N parallel — assert all complete and
	// none misclassify under contention (no shared-state race in the middleware).
	{
		const conc = 300
		type co struct {
			want int
			got  int
		}
		results := make([]co, conc)
		latsC := make([]time.Duration, conc)
		done := make(chan struct{}, conc)
		startC := time.Now()
		for i := 0; i < conc; i++ {
			call := matrix[i%len(matrix)]
			go func(idx int, c stressCall) {
				t0 := time.Now()
				resp, err := client.Get(srv.URL + c.suffix)
				latsC[idx] = time.Since(t0)
				code := 0
				if err == nil {
					code = resp.StatusCode
					resp.Body.Close()
				}
				results[idx] = co{want: c.want, got: code}
				done <- struct{}{}
			}(i, call)
		}
		for i := 0; i < conc; i++ {
			<-done
		}
		latC := computeLatency(latsC, time.Since(startC))
		misC := 0
		unexp5xxC := 0
		var firstMisC string
		c2, c4, c5 := 0, 0, 0
		for i, rr := range results {
			switch {
			case rr.got >= 200 && rr.got < 300:
				c2++
			case rr.got >= 400 && rr.got < 500:
				c4++
			case rr.got >= 500:
				c5++
			}
			if rr.got == http.StatusInternalServerError {
				unexp5xxC++
			}
			if rr.got != rr.want {
				misC++
				if firstMisC == "" {
					firstMisC = fmt.Sprintf("conc req %d: got %d want %d", i, rr.got, rr.want)
				}
			}
		}
		statusC := "PASS"
		// 501 is an expected status under the mixed matrix; only an unexpected
		// 500 (panic) is a fault. Every concurrent request must classify right.
		if conc < minConcurrency || misC > 0 || unexp5xxC > 0 {
			statusC = "FAIL"
		}
		ev.Add(DimensionResult{
			ID: "P2", Name: "provider-middleware-concurrent-dispatch", Ran: true, Status: statusC,
			Requests: conc, Status2xx: c2, Status4xx: c4, Status5xx: c5,
			ErrorRate: float64(c5) / float64(conc), Latency: latC,
			FaultType: "concurrent-mixed-dispatch",
			Notes:     fmt.Sprintf("%d concurrent; misclassified=%d; first=%q", conc, misC, firstMisC),
		})
		if statusC == "FAIL" {
			t.Errorf("P2 concurrent FAILED: misclassified=%d unexpected5xx=%d first=%q", misC, unexp5xxC, firstMisC)
		}
	}

	ev.Finalize()
	path, err := ev.Write()
	if err != nil {
		t.Fatalf("failed to write evidence: %v", err)
	}
	t.Logf("evidence written: %s (verdict=%s)", path, ev.Verdict)
	t.Logf("P1 latency: p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
		lat.P50Ms, lat.P95Ms, lat.P99Ms, lat.MaxMs)
	if ev.Verdict != "PASS" {
		t.Errorf("run verdict FAIL — see %s", path)
	}
}
