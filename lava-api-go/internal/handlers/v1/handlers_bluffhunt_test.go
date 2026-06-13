// §6.N production bluff-hunt (2026-06-13) — coverage hardening for two
// user-visible v1 handler behaviors the existing suite did NOT discriminate:
//
//  1. login.go PostLogin: the captcha plumbing (CaptchaCode / CaptchaName /
//     CaptchaSID) and the username/password from the request body MUST be
//     forwarded into provider.LoginOpts. The existing TestLogin_* cases use a
//     richProvider whose Login() ignores opts, so dropping CaptchaName (the
//     LVA-025 rotating-captcha-field-name fix) survived a deliberate mutation
//     with zero test failures — a §6.AB "code present, behavior unasserted"
//     gap. This test captures the LoginOpts the production handler builds and
//     asserts every field round-trips.
//
//  2. jackett.go GetSearch: when the client omits the `indexer` query param,
//     the handler MUST fall back to the configured defaultIndexer and that
//     indexer MUST reach the upstream Jackett sidecar (it is encoded in the
//     Torznab URL PATH). The existing jackett tests never asserted the
//     on-the-wire indexer, so replacing `indexer = h.defaultIndexer` with a
//     wrong literal survived. This test reads the upstream request path and
//     asserts the configured default indexer is what got queried.
//
// Both assertions are PRIMARY on user-visible state (the LoginOpts the provider
// receives / the indexer on the wire), per Sixth Law clause 3.
package v1

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/provider"
)

// captureLoginProvider is a real provider.Provider whose Login records the
// exact provider.LoginOpts the production handler passed in, so the test can
// assert the request body → LoginOpts mapping (including the rotating-captcha
// fields) round-trips through PostLogin without loss.
type captureLoginProvider struct {
	richProvider
	gotOpts provider.LoginOpts
}

func (p *captureLoginProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	p.gotOpts = opts
	return &provider.LoginResult{Success: true, AuthToken: "ok"}, nil
}

// TestLogin_ForwardsCaptchaAndCredentialFields drives the real PostLogin
// handler with a body carrying username, password, and all three captcha
// fields, then asserts each one reached provider.LoginOpts verbatim.
//
// FALSIFIABILITY REHEARSAL: replacing `CaptchaName: req.CaptchaName` with
// `CaptchaName: ""` in login.go (the LVA-025 regression) makes this test fail
// with: `LoginOpts.CaptchaName = "", want "cap_code_abc123"`. Confirmed
// 2026-06-13: the deliberate break failed THIS test (and the wider TestLogin
// suite stayed green, which is the bluff this test closes). Reverted.
func TestLogin_ForwardsCaptchaAndCredentialFields(t *testing.T) {
	cp := &captureLoginProvider{}
	router := setupTestRouter(cp)

	body := `{"username":"alice","password":"s3cr3t",` +
		`"captchaCode":"answer42","captchaName":"cap_code_abc123","captchaSid":"sid-99"}`
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	got := cp.gotOpts
	if got.Username != "alice" {
		t.Errorf("LoginOpts.Username = %q, want %q", got.Username, "alice")
	}
	if got.Password != "s3cr3t" {
		t.Errorf("LoginOpts.Password = %q, want %q", got.Password, "s3cr3t")
	}
	if got.CaptchaCode != "answer42" {
		t.Errorf("LoginOpts.CaptchaCode = %q, want %q", got.CaptchaCode, "answer42")
	}
	// The load-bearing assertion: the rotating captcha FIELD NAME (LVA-025).
	if got.CaptchaName != "cap_code_abc123" {
		t.Errorf("LoginOpts.CaptchaName = %q, want %q", got.CaptchaName, "cap_code_abc123")
	}
	if got.CaptchaSID != "sid-99" {
		t.Errorf("LoginOpts.CaptchaSID = %q, want %q", got.CaptchaSID, "sid-99")
	}
}

// TestJackettHandler_OmittedIndexerQueriesConfiguredDefault asserts that when a
// client omits `indexer`, the handler queries the CONFIGURED default indexer,
// and that indexer reaches the upstream on the wire (it is encoded in the
// Torznab URL path /api/v2.0/indexers/<indexerID>/results/torznab/api).
//
// FALSIFIABILITY REHEARSAL: replacing `indexer = h.defaultIndexer` with
// `indexer = "wrong-default"` in jackett.go makes this test fail with:
// `upstream queried indexer "wrong-default", want the configured default
// "rutracker-default"`. Confirmed 2026-06-13: the deliberate break failed THIS
// test (the wider TestJackett suite stayed green, the bluff this closes).
// Reverted.
func TestJackettHandler_OmittedIndexerQueriesConfiguredDefault(t *testing.T) {
	const configuredDefault = "rutracker-default"

	fixture, err := os.ReadFile("../../jackett/testdata/torznab_results.xml")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}

	var seenPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenPath = r.URL.Path
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(fixture)
	}))
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), configuredDefault)
	// Note: NO `indexer` query param — exercises the defaultIndexer fallback.
	w, got := doJackettSearch(t, h, "q=ubuntu")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	// PRIMARY on-the-wire assertion: the configured default indexer reached the
	// upstream Torznab path. A wrong-default mutation breaks exactly this.
	wantSegment := "/indexers/" + configuredDefault + "/results/torznab/api"
	if !strings.Contains(seenPath, wantSegment) {
		t.Errorf("upstream path = %q, want it to contain %q (the configured default indexer)",
			seenPath, wantSegment)
	}
	// Secondary user-visible assertion: results are stamped with that indexer
	// as their category (what the app shows the user).
	if len(got.Results) == 0 {
		t.Fatalf("got 0 results, want the fixture items")
	}
	if got.Results[0].Category != configuredDefault {
		t.Errorf("result category = %q, want the configured default indexer %q",
			got.Results[0].Category, configuredDefault)
	}
}

// TestNewJackettHandler_EmptyDefaultFallsBackToIndexerAll asserts the
// constructor's empty-default normalization (jackett.go:56-58): an empty
// defaultIndexer becomes IndexerAll, so an indexer-less request queries "all".
//
// FALSIFIABILITY REHEARSAL: removing the `if defaultIndexer == "" {
// defaultIndexer = jackett.IndexerAll }` block makes an indexer-less request
// query the empty-string indexer — the upstream path would contain
// `/indexers//results/` and this test fails on the missing "all" segment.
// Confirmed 2026-06-13. Reverted.
func TestNewJackettHandler_EmptyDefaultFallsBackToIndexerAll(t *testing.T) {
	fixture, err := os.ReadFile("../../jackett/testdata/torznab_results.xml")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var seenPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenPath = r.URL.Path
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(fixture)
	}))
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "") // empty default
	w, _ := doJackettSearch(t, h, "q=ubuntu")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	wantSegment := "/indexers/" + jackett.IndexerAll + "/results/torznab/api"
	if !strings.Contains(seenPath, wantSegment) {
		t.Errorf("upstream path = %q, want it to contain %q (the IndexerAll fallback)",
			seenPath, wantSegment)
	}
}
