package middleware

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// TestProviderMiddleware_ConcurrentSameProvider drives N concurrent requests
// through the SAME real Gin engine + SAME real ProviderMiddleware + SAME real
// registry, all targeting the same provider, and asserts EVERY request receives
// the correct 200 with the correctly-resolved provider ID.
//
// This is the no-shared-state-race assertion: gin.Context is per-request, and
// ProviderMiddleware stores the resolved provider under a per-request context
// key (c.Set), so two simultaneous requests must NOT cross-contaminate each
// other's resolved provider. A regression that stored the provider in a shared
// field instead of c.Set would produce wrong resolved_id under contention.
//
// Run with -race (go test -race ./internal/middleware/...) for the data-race
// detector to additionally prove no unsynchronized shared write occurs.
//
// FALSIFIABILITY (Sixth Law clause 2 / §6.J): change ProviderMiddleware to
// abort with 500 unconditionally — every concurrent request then fails the
// "want 200" assertion below.
func TestProviderMiddleware_ConcurrentSameProvider(t *testing.T) {
	r := buildRouter(newTestRegistry(), provider.CapSearch)

	const concurrency = 200
	var wg sync.WaitGroup
	type result struct {
		code int
		id   string
		body string
	}
	results := make([]result, concurrency)

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			w := httptest.NewRecorder()
			req, _ := http.NewRequest(http.MethodGet, "/v1/kinozal/search", nil)
			r.ServeHTTP(w, req)
			res := result{code: w.Code, body: w.Body.String()}
			var parsed map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &parsed); err == nil {
				if v, ok := parsed["resolved_id"].(string); ok {
					res.id = v
				}
			}
			results[idx] = res
		}(i)
	}
	wg.Wait()

	for i, res := range results {
		if res.code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want 200; body=%s", i, res.code, res.body)
		}
		if res.id != "kinozal" {
			t.Fatalf("request %d: resolved_id = %q, want kinozal (shared-state cross-contamination); body=%s",
				i, res.id, res.body)
		}
	}
}

// TestProviderMiddleware_ConcurrentMixed drives concurrent requests across a mix
// of known/unknown providers and supported/unsupported capabilities through ONE
// engine, asserting every request lands on its correct status (200/404/501) with
// no flaky misclassification under contention.
//
// The engine here mounts THREE provider groups (one per required capability) so
// a single registry serves a known+capable path (200), a known+incapable path
// (501), and an unknown path (404) simultaneously.
//
// FALSIFIABILITY: drop the `!reg.Supports` guard in ProviderMiddleware — the
// 501-expecting requests then misclassify as 200, failing this test.
func TestProviderMiddleware_ConcurrentMixed(t *testing.T) {
	reg := newTestRegistry() // kinozal: SEARCH + TOPIC

	gin.SetMode(gin.TestMode)
	r := gin.New()
	// /search requires SEARCH (kinozal supports → 200).
	sg := r.Group("/v1/:provider/search")
	sg.Use(ProviderMiddleware(reg, provider.CapSearch))
	sg.GET("", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"resolved_id": Current(c).ID()})
	})
	// /browse requires BROWSE (kinozal does NOT support → 501).
	bg := r.Group("/v1/:provider/browse")
	bg.Use(ProviderMiddleware(reg, provider.CapBrowse))
	bg.GET("", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"resolved_id": Current(c).ID()})
	})

	type call struct {
		path string
		want int
	}
	// Three request classes interleaved.
	classes := []call{
		{"/v1/kinozal/search", http.StatusOK},
		{"/v1/kinozal/browse", http.StatusNotImplemented},
		{"/v1/ghost/search", http.StatusNotFound},
	}

	const perClass = 100 // >= 100 mixed requests per class (300 total)
	var wg sync.WaitGroup
	type outcome struct {
		want int
		got  int
		body string
	}
	outcomes := make([]outcome, 0, perClass*len(classes))
	var mu sync.Mutex

	for _, cl := range classes {
		for i := 0; i < perClass; i++ {
			wg.Add(1)
			go func(c call) {
				defer wg.Done()
				w := httptest.NewRecorder()
				req, _ := http.NewRequest(http.MethodGet, c.path, nil)
				r.ServeHTTP(w, req)
				mu.Lock()
				outcomes = append(outcomes, outcome{want: c.want, got: w.Code, body: w.Body.String()})
				mu.Unlock()
			}(cl)
		}
	}
	wg.Wait()

	if len(outcomes) != perClass*len(classes) {
		t.Fatalf("expected %d outcomes, got %d", perClass*len(classes), len(outcomes))
	}
	for i, o := range outcomes {
		if o.got != o.want {
			t.Fatalf("mixed request %d: status = %d, want %d (misclassification under contention); body=%s",
				i, o.got, o.want, o.body)
		}
	}
}
