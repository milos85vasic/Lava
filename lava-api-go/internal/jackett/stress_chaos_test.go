package jackett

// Stress + chaos tests for the Jackett Torznab client + parser, per
// HelixConstitution §11.4.85 (Stress + Chaos Test Mandate) and the project's
// Anti-Bluff Pact (§6.J / §6.L). These tests exercise the REAL production
// code path (NewClient → Search → ParseResults, and Download) against a fake
// Torznab upstream (httptest — a boundary fake, a real HTTP socket). No
// internal business logic is mocked.
//
// STRESS:
//   - sustained: ≥100 sequential Search iterations, per-iteration latency
//     captured and reduced to p50/p95/p99 (evidence JSON).
//   - concurrent: ≥10 parallel Search callers, run under `go test -race`;
//     asserts no data race and no leaked goroutines.
//   - boundary: empty feed, single item, a large 500-item feed.
//
// CHAOS (fault injection — each categorized, none may panic):
//   - malformed / truncated XML
//   - invalid UTF-8 body
//   - upstream HTTP 500 / 503
//   - upstream timeout (slow handler vs. client Timeout)
//   - connection drop mid-body
//   - 302 → magnet redirect edge
//   - missing apikey / config
//
// Evidence is written under .lava-ci-evidence/stress-chaos/jackett/.

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// Evidence helpers
// ---------------------------------------------------------------------------

// evidenceDir walks up from the test's working directory to find the repo's
// .lava-ci-evidence directory, then returns (and ensures) the
// stress-chaos/jackett subdir. This avoids hardcoding an absolute path (§6.R)
// while keeping evidence at the canonical project location.
func evidenceDir(t *testing.T) string {
	t.Helper()
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
			// Fallback: create relative to cwd so the test never fails purely
			// for lack of a pre-existing directory.
			out := filepath.Join("stress-chaos-evidence", "jackett")
			_ = os.MkdirAll(out, 0o755)
			return out
		}
		dir = parent
	}
}

func writeEvidence(t *testing.T, name string, payload any) {
	t.Helper()
	b, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		t.Fatalf("marshal evidence %s: %v", name, err)
	}
	path := filepath.Join(evidenceDir(t), name)
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		t.Fatalf("write evidence %s: %v", name, err)
	}
	t.Logf("evidence written: %s", path)
}

// percentile returns the value at the given percentile (0..1) of a sorted
// duration slice using nearest-rank.
func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	rank := int(p*float64(len(sorted)) + 0.5)
	if rank < 1 {
		rank = 1
	}
	if rank > len(sorted) {
		rank = len(sorted)
	}
	return sorted[rank-1]
}

// settleGoroutines lets transient goroutines (httptest conns, http transport
// idle reapers) wind down, then returns the current count.
func settleGoroutines() int {
	for i := 0; i < 20; i++ {
		runtime.GC()
		time.Sleep(50 * time.Millisecond)
	}
	return runtime.NumGoroutine()
}

// buildFeed constructs a well-formed Torznab feed with n synthetic items.
func buildFeed(n int) []byte {
	var sb strings.Builder
	sb.WriteString(`<?xml version="1.0" encoding="UTF-8"?>`)
	sb.WriteString(`<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>`)
	for i := 0; i < n; i++ {
		fmt.Fprintf(&sb,
			`<item><title>Item %d</title><guid>guid-%d</guid>`+
				`<enclosure url="http://x/dl/%d" length="%d" type="application/x-bittorrent"/>`+
				`<torznab:attr name="seeders" value="%d"/>`+
				`<torznab:attr name="size" value="%d"/></item>`,
			i, i, i, 1000+i, i%500, int64(1024*1024)*int64(i+1))
	}
	sb.WriteString(`</channel></rss>`)
	return []byte(sb.String())
}

// ---------------------------------------------------------------------------
// STRESS
// ---------------------------------------------------------------------------

// TestStress_Sustained_ParseSearch drives ≥100 sequential real Search calls
// against a fake Torznab upstream, capturing per-iteration latency and
// reducing to p50/p95/p99. The primary assertion is on user-visible state:
// every iteration MUST return the full parsed result set (2 items from the
// committed fixture).
func TestStress_Sustained_ParseSearch(t *testing.T) {
	fixture := loadFixture(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c, err := NewClient(Config{BaseURL: srv.URL, APIKey: "test-apikey-not-a-real-secret"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}

	const iterations = 200
	latencies := make([]time.Duration, 0, iterations)
	for i := 0; i < iterations; i++ {
		start := time.Now()
		results, serr := c.Search(context.Background(), "rutracker", "iso")
		elapsed := time.Since(start)
		if serr != nil {
			t.Fatalf("iteration %d: Search error: %v", i, serr)
		}
		if len(results) != 2 {
			t.Fatalf("iteration %d: expected 2 results, got %d", i, len(results))
		}
		// Field-level guard so a silently-empty parse cannot masquerade as success.
		if results[0].Seeders != 421 {
			t.Fatalf("iteration %d: results[0].Seeders = %d, want 421", i, results[0].Seeders)
		}
		latencies = append(latencies, elapsed)
	}

	sorted := append([]time.Duration(nil), latencies...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	p50 := percentile(sorted, 0.50)
	p95 := percentile(sorted, 0.95)
	p99 := percentile(sorted, 0.99)

	t.Logf("sustained: %d iterations p50=%s p95=%s p99=%s min=%s max=%s",
		iterations, p50, p95, p99, sorted[0], sorted[len(sorted)-1])

	writeEvidence(t, "stress-latency-jackett.json", map[string]any{
		"test":                  "TestStress_Sustained_ParseSearch",
		"surface":               "jackett.Client.Search -> ParseResults (real stack, fake Torznab upstream)",
		"iterations":            iterations,
		"all_succeeded":         true,
		"results_per_iteration": 2,
		"latency_ns": map[string]int64{
			"p50": p50.Nanoseconds(),
			"p95": p95.Nanoseconds(),
			"p99": p99.Nanoseconds(),
			"min": sorted[0].Nanoseconds(),
			"max": sorted[len(sorted)-1].Nanoseconds(),
		},
		"latency_human": map[string]string{
			"p50": p50.String(),
			"p95": p95.String(),
			"p99": p99.String(),
			"min": sorted[0].String(),
			"max": sorted[len(sorted)-1].String(),
		},
		"captured_at": time.Now().UTC().Format(time.RFC3339),
	})
}

// TestStress_Concurrent_Search runs ≥10 parallel Search callers, each issuing
// many requests, against a fake upstream. Run under `go test -race` this
// asserts no data race; the goroutine-count check asserts no leaked
// goroutines/connections. Primary assertion: every call returns the full
// parsed result set.
func TestStress_Concurrent_Search(t *testing.T) {
	fixture := loadFixture(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c, err := NewClient(Config{BaseURL: srv.URL, APIKey: "test-apikey-not-a-real-secret"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}

	baseline := settleGoroutines()

	const workers = 16
	const perWorker = 25
	var wg sync.WaitGroup
	errCh := make(chan error, workers*perWorker)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				results, serr := c.Search(context.Background(), "rutracker", fmt.Sprintf("q-%d-%d", id, i))
				if serr != nil {
					errCh <- fmt.Errorf("worker %d iter %d: %w", id, i, serr)
					return
				}
				if len(results) != 2 {
					errCh <- fmt.Errorf("worker %d iter %d: got %d results, want 2", id, i, len(results))
					return
				}
			}
		}(w)
	}
	wg.Wait()
	close(errCh)
	for e := range errCh {
		t.Errorf("concurrent failure: %v", e)
	}

	after := settleGoroutines()
	// Tolerance accounts for the http.Transport's idle-conn reaper + httptest
	// server accept loop. A genuine per-call leak (workers*perWorker = 400)
	// would dwarf this.
	const tolerance = 20
	if after > baseline+tolerance {
		t.Errorf("goroutine leak: baseline=%d after=%d (tolerance=%d)", baseline, after, tolerance)
	}
	t.Logf("concurrent: %d workers x %d calls; goroutines baseline=%d after=%d",
		workers, perWorker, baseline, after)
}

// TestStress_Boundary_FeedSizes exercises empty / single / large (500-item)
// feeds through the real ParseResults path.
func TestStress_Boundary_FeedSizes(t *testing.T) {
	cases := []struct {
		name string
		n    int
	}{
		{"empty", 0},
		{"single", 1},
		{"large_500", 500},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			feed := buildFeed(tc.n)
			results, err := ParseResults(feed)
			if err != nil {
				t.Fatalf("ParseResults(%s) error: %v", tc.name, err)
			}
			if len(results) != tc.n {
				t.Fatalf("ParseResults(%s) = %d items, want %d", tc.name, len(results), tc.n)
			}
			if tc.n > 0 {
				// Spot-check first + last to prove the whole feed was parsed,
				// not just the count.
				if results[0].Title != "Item 0" {
					t.Errorf("first title = %q, want %q", results[0].Title, "Item 0")
				}
				last := results[tc.n-1]
				wantTitle := fmt.Sprintf("Item %d", tc.n-1)
				if last.Title != wantTitle {
					t.Errorf("last title = %q, want %q", last.Title, wantTitle)
				}
				if last.Size != int64(1024*1024)*int64(tc.n) {
					t.Errorf("last size = %d, want %d", last.Size, int64(1024*1024)*int64(tc.n))
				}
			}
		})
	}
}

// ---------------------------------------------------------------------------
// CHAOS
// ---------------------------------------------------------------------------

// chaosOutcome is one categorized fault-injection result for the evidence file.
type chaosOutcome struct {
	Category string `json:"category"`
	Injected string `json:"injected_fault"`
	Surface  string `json:"surface"`
	GotError bool   `json:"got_error"`
	ErrText  string `json:"error_text,omitempty"`
	Panicked bool   `json:"panicked"`
	Degraded string `json:"degraded_as"`
}

// safeCall runs fn, recovering any panic so a panicking fault handler is
// recorded (and fails the test) rather than crashing the whole run.
func safeCall(fn func() (bool, string)) (gotErr bool, errText string, panicked bool) {
	defer func() {
		if r := recover(); r != nil {
			panicked = true
			errText = fmt.Sprintf("PANIC: %v", r)
		}
	}()
	gotErr, errText = fn()
	return
}

// TestChaos_Jackett injects each documented fault and asserts the
// client+parser degrade gracefully: a typed error (or graceful magnet capture
// for the 302 edge), NEVER a panic. Categorized outcomes are written to
// evidence.
func TestChaos_Jackett(t *testing.T) {
	outcomes := make([]chaosOutcome, 0, 8)

	record := func(category, injected, surface, degraded string, fn func() (bool, string)) {
		gotErr, errText, panicked := safeCall(fn)
		if panicked {
			t.Errorf("[%s] handler PANICKED (degradation must be graceful): %s", category, errText)
		}
		outcomes = append(outcomes, chaosOutcome{
			Category: category, Injected: injected, Surface: surface,
			GotError: gotErr, ErrText: errText, Panicked: panicked, Degraded: degraded,
		})
	}

	// 1. Malformed / truncated XML → ParseResults error, no panic.
	record("malformed_xml", "truncated <rss><channel> with no close tags",
		"jackett.ParseResults", "typed-error", func() (bool, string) {
			_, err := ParseResults([]byte("<rss><channel><item><title>x"))
			if err == nil {
				t.Error("malformed XML: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 2. Invalid UTF-8 body → ParseResults error, no panic.
	record("invalid_utf8", "raw 0xff bytes inside a <title>",
		"jackett.ParseResults", "typed-error", func() (bool, string) {
			bad := []byte(`<?xml version="1.0"?><rss><channel><item><title>`)
			bad = append(bad, 0xff, 0xfe, 0xfd)
			bad = append(bad, []byte(`</title></item></channel></rss>`)...)
			_, err := ParseResults(bad)
			if err == nil {
				t.Error("invalid UTF-8: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 3. Upstream HTTP 500 → Search typed error, no panic.
	record("http_500", "upstream always returns 500",
		"jackett.Client.Search", "typed-error", func() (bool, string) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusInternalServerError)
			}))
			defer srv.Close()
			c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
			_, err := c.Search(context.Background(), "all", "x")
			if err == nil {
				t.Error("HTTP 500: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 4. Upstream HTTP 503 → Search typed error, no panic.
	record("http_503", "upstream always returns 503",
		"jackett.Client.Search", "typed-error", func() (bool, string) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusServiceUnavailable)
			}))
			defer srv.Close()
			c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
			_, err := c.Search(context.Background(), "all", "x")
			if err == nil {
				t.Error("HTTP 503: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 5. Upstream timeout (slow handler) → Search error, no panic.
	record("timeout", "upstream sleeps beyond client Timeout",
		"jackett.Client.Search", "typed-error", func() (bool, string) {
			release := make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				<-release // block until the test releases, well past the client timeout
				w.WriteHeader(http.StatusOK)
			}))
			defer srv.Close()
			defer close(release)
			c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k", Timeout: 150 * time.Millisecond})
			_, err := c.Search(context.Background(), "all", "x")
			if err == nil {
				t.Error("timeout: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 6. Connection drop mid-body → Search read error, no panic.
	record("connection_drop", "handler declares large Content-Length then hijacks+closes after partial write",
		"jackett.Client.Search", "typed-error", func() (bool, string) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Length", "100000")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("<rss><channel><item><title>partial"))
				if hj, ok := w.(http.Hijacker); ok {
					conn, _, herr := hj.Hijack()
					if herr == nil {
						_ = conn.(net.Conn).Close() // abrupt drop mid-body
					}
				}
			}))
			defer srv.Close()
			c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
			_, err := c.Search(context.Background(), "all", "x")
			if err == nil {
				t.Error("connection drop: expected error, got nil")
				return false, ""
			}
			return true, err.Error()
		})

	// 7. 302 → magnet redirect edge → graceful magnet capture (NO error, NO follow).
	record("redirect_302_magnet", "download link answers 302 with Location: magnet:",
		"jackett.Client.Download", "graceful-magnet-capture", func() (bool, string) {
			const wantMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=x"
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Location", wantMagnet)
				w.WriteHeader(http.StatusFound)
			}))
			defer srv.Close()
			c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
			dr, err := c.Download(context.Background(), srv.URL+"/dl/x")
			if err != nil {
				t.Errorf("302→magnet: unexpected error: %v", err)
				return true, err.Error()
			}
			if !dr.IsMagnet() || dr.Magnet != wantMagnet {
				t.Errorf("302→magnet: got %+v, want magnet %q", dr, wantMagnet)
			}
			return false, "" // graceful: no error, magnet captured
		})

	// 8. Missing apikey / config → NewClient typed error (ErrMissingConfig), no panic.
	record("missing_config", "NewClient called with empty APIKey",
		"jackett.NewClient", "typed-error(ErrMissingConfig)", func() (bool, string) {
			_, err := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: ""})
			if err != ErrMissingConfig {
				t.Errorf("missing config: err = %v, want ErrMissingConfig", err)
				return err != nil, fmt.Sprintf("%v", err)
			}
			return true, err.Error()
		})

	for _, o := range outcomes {
		t.Logf("chaos[%s] surface=%s gotError=%v panicked=%v degraded=%s err=%q",
			o.Category, o.Surface, o.GotError, o.Panicked, o.Degraded, o.ErrText)
	}

	writeEvidence(t, "chaos-categorized-jackett.json", map[string]any{
		"test":         "TestChaos_Jackett",
		"surface":      "jackett client + parser (real stack, fault-injected boundaries)",
		"total_faults": len(outcomes),
		"any_panicked": anyPanicked(outcomes),
		"outcomes":     outcomes,
		"captured_at":  time.Now().UTC().Format(time.RFC3339),
	})
}

func anyPanicked(o []chaosOutcome) bool {
	for _, x := range o {
		if x.Panicked {
			return true
		}
	}
	return false
}
