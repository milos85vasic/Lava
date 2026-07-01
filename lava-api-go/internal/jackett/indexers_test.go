package jackett

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// fakeJackett is a behaviorally-equivalent test double of a Jackett sidecar
// (Third Law / §6.J). It reproduces the production-relevant auth split that a
// REAL Jackett enforces:
//
//   - MANAGEMENT endpoint /api/v2.0/indexers answers apikey-only requests with
//     HTTP 302 → /UI/Login when the dashboard is password protected
//     (requireCookie=true). It returns 200 ONLY when the dashboard session
//     cookie is present.
//   - The dashboard login (POST /UI/Dashboard, form field "password") issues the
//     session cookie when the password matches adminPassword; a wrong password
//     is rejected with 403.
//   - TORZNAB feeds authenticate by apikey alone and are exercised elsewhere
//     (torznab_test.go) — this double covers the management/discovery surface.
//
// The PRIOR fake (a plain httptest server that returned the indexer JSON
// unconditionally) accepted apikey for the management call too. That divergence
// from real Jackett let a cookie-less client pass the discovery test while
// failing in production with a 302 — the canonical §6.J bluff-fake. This double
// closes that gap: the discovery test now must traverse the real cookie path.
type fakeJackett struct {
	adminPassword string // password the dashboard login accepts
	requireCookie bool   // true = password-protected dashboard (302 w/o cookie)
	indexersJSON  []byte

	mu               sync.Mutex
	loginHits        int    // POST /UI/Dashboard count
	gotLoginPassword string // password presented at the last login POST
	mgmtNoCookieHits int    // management requests that arrived WITHOUT the cookie
	mgmtCookieHits   int    // management requests that arrived WITH the cookie
	lastMgmtPath     string // path of the last (cookie'd) management request
	lastMgmtQuery    string // raw query of the last (cookie'd) management request
}

const (
	fakeJackettCookieName  = "Jackett"
	fakeJackettCookieValue = "session-token-xyz"
)

func (f *fakeJackett) hasSessionCookie(r *http.Request) bool {
	ck, err := r.Cookie(fakeJackettCookieName)
	return err == nil && ck.Value == fakeJackettCookieValue
}

func (f *fakeJackett) server(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()

	// Dashboard login — issues the session cookie when the password matches.
	mux.HandleFunc(dashboardLoginPath, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		_ = r.ParseForm()
		f.mu.Lock()
		f.loginHits++
		f.gotLoginPassword = r.PostFormValue(loginPasswordField)
		ok := f.gotLoginPassword == f.adminPassword
		f.mu.Unlock()
		if !ok {
			// Wrong admin password → reject (no Set-Cookie).
			w.WriteHeader(http.StatusForbidden)
			return
		}
		http.SetCookie(w, &http.Cookie{
			Name:  fakeJackettCookieName,
			Value: fakeJackettCookieValue,
			Path:  "/",
		})
		w.WriteHeader(http.StatusOK)
	})

	// Management: configured-indexers enumeration.
	mux.HandleFunc(indexersPath, func(w http.ResponseWriter, r *http.Request) {
		if f.requireCookie && !f.hasSessionCookie(r) {
			f.mu.Lock()
			f.mgmtNoCookieHits++
			f.mu.Unlock()
			// apikey alone is NOT enough for management — redirect to login.
			http.Redirect(w, r, "/UI/Login", http.StatusFound)
			return
		}
		f.mu.Lock()
		f.mgmtCookieHits++
		f.lastMgmtPath = r.URL.Path
		f.lastMgmtQuery = r.URL.RawQuery
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(f.indexersJSON)
	})

	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// TestListIndexers_ParsesConfiguredIndexers wires a REAL *Client at a
// behaviorally-equivalent fake Jackett whose MANAGEMENT API requires the
// dashboard session cookie (password-protected dashboard). The test asserts
// ListIndexers parses the indexer set end-to-end VIA THE COOKIE LOGIN PATH —
// only the network boundary is faked; the production request-build, cookie
// login, retry, and JSON-decode path all run for real.
//
// Anti-bluff: the primary assertions are on the PARSED indexer data (ids,
// names, caps) — user-visible facts the /v1/providers catalogue surfaces — and
// on the auth sequence (a 302 occurred without the cookie, a login happened, the
// successful request carried the cookie).
//
// Bluff-Audit:
//
//	Test:     TestListIndexers_ParsesConfiguredIndexers
//	Mutation: in getManagement, delete the `if isRedirect(status) { login + retry }`
//	          block so a cookie-less client never authenticates.
//	Observed: "ListIndexers: jackett: list indexers: jackett: management request
//	          status 302" (the cookie path no longer runs; the parse never reached).
//	Reverted: yes.
func TestListIndexers_ParsesConfiguredIndexers(t *testing.T) {
	const adminPassword = "test-admin-pw"
	fake := &fakeJackett{
		adminPassword: adminPassword,
		requireCookie: true,
		indexersJSON:  loadIndexersFixture(t),
	}
	srv := fake.server(t)

	const apiKey = "test-jackett-apikey"
	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: apiKey, AdminPassword: adminPassword})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}

	indexers, err := cli.ListIndexers(context.Background())
	if err != nil {
		t.Fatalf("ListIndexers: %v", err)
	}

	// Primary assertion: the parsed catalogue data.
	if len(indexers) != 2 {
		t.Fatalf("expected 2 indexers, got %d: %+v", len(indexers), indexers)
	}
	if indexers[0].ID != "1337x" || indexers[0].Name != "1337x" {
		t.Errorf("indexer[0] = {%q,%q}, want {1337x,1337x}", indexers[0].ID, indexers[0].Name)
	}
	if indexers[1].ID != "rutracker" || indexers[1].Name != "RuTracker" {
		t.Errorf("indexer[1] = {%q,%q}, want {rutracker,RuTracker}", indexers[1].ID, indexers[1].Name)
	}
	if len(indexers[0].Caps) != 3 {
		t.Errorf("indexer[0].Caps len = %d, want 3", len(indexers[0].Caps))
	}

	// Auth-sequence assertions: the cookie path actually ran.
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if fake.mgmtNoCookieHits != 1 {
		t.Errorf("expected exactly 1 cookie-less management hit (the initial 302), got %d", fake.mgmtNoCookieHits)
	}
	if fake.loginHits != 1 {
		t.Errorf("expected exactly 1 dashboard login, got %d", fake.loginHits)
	}
	if fake.gotLoginPassword != adminPassword {
		t.Errorf("login presented password %q, want %q", fake.gotLoginPassword, adminPassword)
	}
	if fake.mgmtCookieHits != 1 {
		t.Errorf("expected exactly 1 successful cookie'd management hit, got %d", fake.mgmtCookieHits)
	}

	// Request-shape assertions (secondary): the production path hit the documented
	// Jackett endpoint with the configured filter + the apikey.
	if fake.lastMgmtPath != "/api/v2.0/indexers" {
		t.Errorf("management path = %q, want /api/v2.0/indexers", fake.lastMgmtPath)
	}
	if !strings.Contains(fake.lastMgmtQuery, "configured=true") {
		t.Errorf("query %q missing configured=true", fake.lastMgmtQuery)
	}
	if !strings.Contains(fake.lastMgmtQuery, "apikey="+apiKey) {
		t.Errorf("query %q missing apikey", fake.lastMgmtQuery)
	}
}

// TestListIndexers_UnprotectedJackettNeedsNoCookie covers the OTHER real-Jackett
// deployment: a dashboard with no admin password configured. The management API
// returns 200 to an apikey request directly, so ListIndexers must succeed WITHOUT
// performing a login. Proves the cookie path is taken only when needed.
func TestListIndexers_UnprotectedJackettNeedsNoCookie(t *testing.T) {
	fake := &fakeJackett{
		requireCookie: false, // unprotected dashboard
		indexersJSON:  loadIndexersFixture(t),
	}
	srv := fake.server(t)

	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	indexers, err := cli.ListIndexers(context.Background())
	if err != nil {
		t.Fatalf("ListIndexers: %v", err)
	}
	if len(indexers) != 2 {
		t.Fatalf("expected 2 indexers, got %d", len(indexers))
	}
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if fake.loginHits != 0 {
		t.Errorf("unprotected Jackett should need no login, got %d login hits", fake.loginHits)
	}
	if fake.mgmtCookieHits != 1 {
		t.Errorf("expected 1 direct management hit, got %d", fake.mgmtCookieHits)
	}
}

// TestListIndexers_WrongAdminPasswordFails proves the admin password is actually
// checked: a protected dashboard + wrong password → login rejected → ListIndexers
// returns an error (the catalogue degrades to native providers per spec §5).
func TestListIndexers_WrongAdminPasswordFails(t *testing.T) {
	fake := &fakeJackett{
		adminPassword: "correct-pw",
		requireCookie: true,
		indexersJSON:  loadIndexersFixture(t),
	}
	srv := fake.server(t)

	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: "k", AdminPassword: "wrong-pw"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	if _, err := cli.ListIndexers(context.Background()); err == nil {
		t.Fatal("expected error when admin password is wrong, got nil")
	}
}

// TestListIndexers_NonOKStatusIsError asserts a non-redirect, non-200 from the
// upstream surfaces as an error (so the startup enumeration can RecordNonFatal +
// continue serving native providers, per spec §5).
func TestListIndexers_NonOKStatusIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	if _, err := cli.ListIndexers(context.Background()); err == nil {
		t.Fatal("expected error on HTTP 500, got nil")
	}
}

func loadIndexersFixture(t *testing.T) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "indexers_configured.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	return b
}
