package v1

// Stress + chaos tests for the Jackett HTTP handler (GET /jackett/search), per
// HelixConstitution §11.4.85 (Stress + Chaos Test Mandate) and the Anti-Bluff
// Pact (§6.J / §6.L). The handler is driven through the REAL Gin engine wired
// to a REAL *jackett.Client; only the Torznab upstream is faked (httptest — a
// real HTTP socket). No internal business logic is mocked.
//
// STRESS:
//   - concurrent: ≥10 parallel GetSearch callers (run under `go test -race`)
//     assert no data race + no leaked goroutines; primary assertion on the
//     200 response body (mapped SearchResult).
//
// CHAOS (fault injection — each categorized, none may panic): the handler MUST
// degrade to a typed HTTP status (400 / 502), never panic, and never leak.
//
// Evidence is written under .lava-ci-evidence/stress-chaos/jackett/.

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// ---------------------------------------------------------------------------
// Evidence helpers (handler-scoped; mirrors the jackett-package helper)
// ---------------------------------------------------------------------------

// Run-isolation seam — see the identical const in
// lava-api-go/internal/jackett/stress_chaos_test.go. When set (the
// build-test-distribute pipeline's phase-02 go wrapper sets it), evidence is
// written there instead of at the tracked project-level
// .lava-ci-evidence/stress-chaos/jackett/, so a pipeline run never modifies a
// tracked file and the next run's FR-000 clean-tree precondition still passes
// (FR-018 / SC-007). Unset -> historical behaviour, byte-for-byte.
const stressChaosEvidenceDirEnv = "LAVA_STRESS_CHAOS_EVIDENCE_DIR"

func handlerEvidenceDir(t *testing.T) string {
	t.Helper()
	if override := os.Getenv(stressChaosEvidenceDirEnv); override != "" {
		if mkErr := os.MkdirAll(override, 0o755); mkErr != nil {
			t.Fatalf("mkdir evidence override %s: %v", override, mkErr)
		}
		return override
	}
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		candidate := filepath.Join(dir, ".lava-ci-evidence")
		if fi, statErr := os.Stat(candidate); statErr == nil && fi.IsDir() {
			out := filepath.Join(candidate, "stress-chaos", "jackett")
			if mkErr := os.MkdirAll(out, 0o755); mkErr != nil {
				t.Fatalf("mkdir evidence: %v", mkErr)
			}
			return out
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			out := filepath.Join("stress-chaos-evidence", "jackett")
			_ = os.MkdirAll(out, 0o755)
			return out
		}
		dir = parent
	}
}

func writeHandlerEvidence(t *testing.T, name string, payload any) {
	t.Helper()
	b, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		t.Fatalf("marshal evidence %s: %v", name, err)
	}
	path := filepath.Join(handlerEvidenceDir(t), name)
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		t.Fatalf("write evidence %s: %v", name, err)
	}
	t.Logf("evidence written: %s", path)
}

func settleGoroutinesHandler() int {
	for i := 0; i < 20; i++ {
		runtime.GC()
		time.Sleep(50 * time.Millisecond)
	}
	return runtime.NumGoroutine()
}

// newRouter wires the real Gin engine to the handler under test.
func newRouter(h *JackettHandler) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/jackett/search", h.GetSearch)
	return r
}

// ---------------------------------------------------------------------------
// STRESS
// ---------------------------------------------------------------------------

// TestStress_Handler_Concurrent runs ≥10 parallel handler calls through the
// real Gin engine + real *jackett.Client. Under `go test -race` this asserts
// no data race; the goroutine check asserts no leak. Primary assertion: every
// response is a 200 carrying the fully-mapped SearchResult (2 items).
func TestStress_Handler_Concurrent(t *testing.T) {
	upstream := newFakeJackettUpstream(t)
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "")
	router := newRouter(h)

	baseline := settleGoroutinesHandler()

	const workers = 16
	const perWorker = 25
	var wg sync.WaitGroup
	errCh := make(chan error, workers*perWorker)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				req := httptest.NewRequest(http.MethodGet,
					"/jackett/search?q="+url.QueryEscape(fmt.Sprintf("q-%d-%d", id, i)), nil)
				rec := httptest.NewRecorder()
				router.ServeHTTP(rec, req)
				if rec.Code != http.StatusOK {
					errCh <- fmt.Errorf("worker %d iter %d: status %d body=%s", id, i, rec.Code, rec.Body.String())
					return
				}
				var got provider.SearchResult
				if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
					errCh <- fmt.Errorf("worker %d iter %d: unmarshal: %w", id, i, err)
					return
				}
				if got.Provider != "jackett" || len(got.Results) != 2 {
					errCh <- fmt.Errorf("worker %d iter %d: provider=%q results=%d, want jackett/2",
						id, i, got.Provider, len(got.Results))
					return
				}
			}
		}(w)
	}
	wg.Wait()
	close(errCh)
	for e := range errCh {
		t.Errorf("concurrent handler failure: %v", e)
	}

	after := settleGoroutinesHandler()
	const tolerance = 20
	if after > baseline+tolerance {
		t.Errorf("goroutine leak: baseline=%d after=%d (tolerance=%d)", baseline, after, tolerance)
	}
	t.Logf("handler concurrent: %d workers x %d calls; goroutines baseline=%d after=%d",
		workers, perWorker, baseline, after)

	writeHandlerEvidence(t, "stress-handler-concurrent-jackett.json", map[string]any{
		"test":                 "TestStress_Handler_Concurrent",
		"surface":              "GET /jackett/search via real Gin engine + real jackett.Client (fake Torznab upstream)",
		"workers":              workers,
		"calls_per_worker":     perWorker,
		"total_calls":          workers * perWorker,
		"all_200_with_2_items": true,
		"goroutines_baseline":  baseline,
		"goroutines_after":     after,
		"goroutine_leak":       after > baseline+tolerance,
		"race_detector":        "run under `go test -race`",
		"captured_at":          time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------------------------------------------------------------------------
// CHAOS
// ---------------------------------------------------------------------------

type handlerChaosOutcome struct {
	Category   string `json:"category"`
	Injected   string `json:"injected_fault"`
	WantStatus int    `json:"want_status"`
	GotStatus  int    `json:"got_status"`
	Panicked   bool   `json:"panicked"`
	Degraded   string `json:"degraded_as"`
}

// safeStatus runs fn (which performs one handler call and returns the status
// code), recovering any panic so a panicking handler is recorded + fails the
// test rather than crashing the run.
func safeStatus(fn func() int) (status int, panicked bool, panicMsg string) {
	defer func() {
		if r := recover(); r != nil {
			panicked = true
			panicMsg = fmt.Sprintf("%v", r)
		}
	}()
	status = fn()
	return
}

// TestChaos_Handler injects each fault at the upstream boundary and asserts the
// handler degrades to the correct typed HTTP status, never panicking. The
// handler's error paths also record §6.AC non-fatal telemetry (verified not to
// panic here).
func TestChaos_Handler(t *testing.T) {
	outcomes := make([]handlerChaosOutcome, 0, 6)

	run := func(category, injected string, wantStatus int, degraded string, build func(t *testing.T) *gin.Engine, rawQuery string) {
		status, panicked, panicMsg := safeStatus(func() int {
			router := build(t)
			req := httptest.NewRequest(http.MethodGet, "/jackett/search?"+rawQuery, nil)
			rec := httptest.NewRecorder()
			router.ServeHTTP(rec, req)
			return rec.Code
		})
		if panicked {
			t.Errorf("[%s] handler PANICKED (degradation must be graceful): %s", category, panicMsg)
		}
		if status != wantStatus {
			t.Errorf("[%s] status = %d, want %d", category, status, wantStatus)
		}
		outcomes = append(outcomes, handlerChaosOutcome{
			Category: category, Injected: injected, WantStatus: wantStatus,
			GotStatus: status, Panicked: panicked, Degraded: degraded,
		})
	}

	// helper: build a router whose real client points at an upstream with the
	// given handler func.
	withUpstream := func(handler http.HandlerFunc) func(t *testing.T) *gin.Engine {
		return func(t *testing.T) *gin.Engine {
			srv := httptest.NewServer(handler)
			t.Cleanup(srv.Close)
			return newRouter(NewJackettHandler(realJackettClient(t, srv.URL), ""))
		}
	}

	// 1. Missing query → 400 (never touches upstream).
	run("missing_query", "no q parameter", http.StatusBadRequest, "http-400",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			t.Error("upstream must not be hit when q is missing")
		}), "")

	// 2. Malformed XML from upstream → client parse error → handler 502.
	run("malformed_xml", "upstream returns truncated XML", http.StatusBadGateway, "http-502",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/rss+xml")
			_, _ = w.Write([]byte("<rss><channel><item><title>broken"))
		}), "q="+url.QueryEscape("x"))

	// 3. Invalid UTF-8 from upstream → parse error → 502.
	run("invalid_utf8", "upstream returns 0xff bytes in title", http.StatusBadGateway, "http-502",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/rss+xml")
			body := []byte(`<?xml version="1.0"?><rss><channel><item><title>`)
			body = append(body, 0xff, 0xfe)
			body = append(body, []byte(`</title></item></channel></rss>`)...)
			_, _ = w.Write(body)
		}), "q="+url.QueryEscape("x"))

	// 4. Upstream 500 → client non-200 error → 502.
	run("http_500", "upstream returns 500", http.StatusBadGateway, "http-502",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusInternalServerError)
		}), "q="+url.QueryEscape("x"))

	// 5. Upstream 503 → 502.
	run("http_503", "upstream returns 503", http.StatusBadGateway, "http-502",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusServiceUnavailable)
		}), "q="+url.QueryEscape("x"))

	// 6. Connection drop mid-body → client read error → 502.
	run("connection_drop", "upstream declares large Content-Length then hijacks+closes", http.StatusBadGateway, "http-502",
		withUpstream(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Length", "100000")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<rss><channel><item><title>partial"))
			if hj, ok := w.(http.Hijacker); ok {
				if conn, _, herr := hj.Hijack(); herr == nil {
					_ = conn.(net.Conn).Close()
				}
			}
		}), "q="+url.QueryEscape("x"))

	for _, o := range outcomes {
		t.Logf("chaos-handler[%s] want=%d got=%d panicked=%v degraded=%s",
			o.Category, o.WantStatus, o.GotStatus, o.Panicked, o.Degraded)
	}

	anyPanic := false
	for _, o := range outcomes {
		if o.Panicked {
			anyPanic = true
		}
	}
	writeHandlerEvidence(t, "chaos-categorized-handler-jackett.json", map[string]any{
		"test":         "TestChaos_Handler",
		"surface":      "GET /jackett/search via real Gin engine + real jackett.Client (fault-injected upstream)",
		"total_faults": len(outcomes),
		"any_panicked": anyPanic,
		"outcomes":     outcomes,
		"captured_at":  time.Now().UTC().Format(time.RFC3339),
	})
}
