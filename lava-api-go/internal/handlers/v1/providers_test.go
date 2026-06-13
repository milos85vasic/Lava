package v1

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/curated"
)

// catalogueFake embeds the package's fakeProvider (which supplies every
// operation + Health method) and overrides only the catalogue-metadata methods,
// so a test can declare a provider's id/kind/caps/auth/anonymous freely.
type catalogueFake struct {
	fakeProvider
	caps []provider.ProviderCapability
	auth provider.AuthType
	kind string
	anon bool
}

func (c *catalogueFake) Capabilities() []provider.ProviderCapability { return c.caps }
func (c *catalogueFake) AuthType() provider.AuthType                 { return c.auth }
func (c *catalogueFake) SupportsAnonymous() bool                     { return c.anon }
func (c *catalogueFake) Kind() string {
	if c.kind != "" {
		return c.kind
	}
	return "native"
}

// TestProvidersHandler_ReturnsCatalogue asserts GET /providers returns the full
// catalogue with per-provider metadata — the user-visible data the client
// renders its provider list + auth UI from (§6.AB: assert on the body, not 200).
func TestProvidersHandler_ReturnsCatalogue(t *testing.T) {
	gin.SetMode(gin.TestMode)
	reg := provider.NewRegistry()
	reg.Register(&catalogueFake{
		fakeProvider: fakeProvider{id: "rutracker"},
		caps:         []provider.ProviderCapability{provider.CapSearch, provider.CapBrowse},
		auth:         provider.AuthCaptchaLogin,
	})
	reg.Register(&catalogueFake{
		fakeProvider: fakeProvider{id: "1337x"},
		caps:         []provider.ProviderCapability{provider.CapSearch, provider.CapMagnetLink, provider.CapTorrentDownload},
		auth:         provider.AuthNone,
		kind:         "jackett",
		anon:         true,
	})

	r := gin.New()
	r.GET("/providers", NewProvidersHandler(reg).GetProviders)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/providers", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("GET /providers status = %d, want 200; body=%s", w.Code, w.Body.String())
	}

	var resp struct {
		Providers []struct {
			ID                string   `json:"id"`
			Kind              string   `json:"kind"`
			Indexer           string   `json:"indexer"`
			Capabilities      []string `json:"capabilities"`
			AuthType          string   `json:"authType"`
			SupportsAnonymous bool     `json:"supportsAnonymous"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not the expected JSON: %v; body=%s", err, w.Body.String())
	}
	if len(resp.Providers) != 2 {
		t.Fatalf("catalogue size = %d, want 2; body=%s", len(resp.Providers), w.Body.String())
	}

	byID := map[string]int{}
	for i, p := range resp.Providers {
		byID[p.ID] = i
	}
	ru, ok := byID["rutracker"]
	if !ok {
		t.Fatalf("native provider 'rutracker' missing from catalogue; body=%s", w.Body.String())
	}
	if resp.Providers[ru].Kind != "native" || resp.Providers[ru].Indexer != "" {
		t.Errorf("rutracker kind=%q indexer=%q, want native/empty", resp.Providers[ru].Kind, resp.Providers[ru].Indexer)
	}
	if resp.Providers[ru].AuthType != "CAPTCHA_LOGIN" {
		t.Errorf("rutracker authType=%q, want CAPTCHA_LOGIN", resp.Providers[ru].AuthType)
	}

	jk, ok := byID["1337x"]
	if !ok {
		t.Fatalf("jackett provider '1337x' missing from catalogue; body=%s", w.Body.String())
	}
	if resp.Providers[jk].Kind != "jackett" {
		t.Errorf("1337x kind=%q, want jackett", resp.Providers[jk].Kind)
	}
	if resp.Providers[jk].Indexer != "1337x" {
		t.Errorf("1337x indexer=%q, want 1337x", resp.Providers[jk].Indexer)
	}
	if !resp.Providers[jk].SupportsAnonymous {
		t.Errorf("1337x supportsAnonymous=false, want true")
	}
	if len(resp.Providers[jk].Capabilities) != 3 {
		t.Errorf("1337x capabilities=%v, want 3 (SEARCH/MAGNET_LINK/TORRENT_DOWNLOAD)", resp.Providers[jk].Capabilities)
	}
}

// TestProvidersHandler_IncludesRealCuratedProvidersEndToEnd is the §6.G Defect-B
// surface: the Android onboarding fetches GET /providers and renders the list
// from the RESPONSE BODY, so it is not enough that curated.RegisterAll puts the
// providers in the registry (the embed test covers that) — the HTTP handler
// must actually serialize each real curated provider, with HONEST capabilities,
// into the body the client parses. This drives the REAL curated.RegisterAll
// through the REAL handler over a real httptest engine and asserts on the
// user-visible body.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//
//	Mutation: in internal/provider/curated/curated.go RegisterAll, replace
//	          `r.Register(torrentscsv.New())` with `_ = torrentscsv.New()` (built
//	          but registered nowhere — a compiling drop).
//	Observed: providers_test.go:179 → "GET /providers body is missing curated
//	          provider \"torrentscsv\" (registry→handler→body drift)".
//	Reverted: yes.
func TestProvidersHandler_IncludesRealCuratedProvidersEndToEnd(t *testing.T) {
	gin.SetMode(gin.TestMode)
	reg := provider.NewRegistry()
	curated.RegisterAll(reg)

	r := gin.New()
	r.GET("/providers", NewProvidersHandler(reg).GetProviders)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/providers", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("GET /providers status = %d, want 200; body=%s", w.Code, w.Body.String())
	}

	var resp struct {
		Providers []struct {
			ID                string   `json:"id"`
			Kind              string   `json:"kind"`
			AuthType          string   `json:"authType"`
			Capabilities      []string `json:"capabilities"`
			SupportsAnonymous bool     `json:"supportsAnonymous"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode body: %v; body=%s", err, w.Body.String())
	}

	byID := make(map[string]struct {
		kind, auth string
		caps       []string
		anon       bool
	})
	for _, p := range resp.Providers {
		byID[p.ID] = struct {
			kind, auth string
			caps       []string
			anon       bool
		}{p.Kind, p.AuthType, p.Capabilities, p.SupportsAnonymous}
	}

	// Every curated public-tracker provider MUST appear in the body with honest
	// SEARCH + MAGNET_LINK caps, anonymous + AuthNone, compiled-in (native).
	for _, id := range []string{"thepiratebay", "yts", "torrentscsv", "bitsearch"} {
		got, ok := byID[id]
		if !ok {
			t.Fatalf("GET /providers body is missing curated provider %q (registry→handler→body drift)", id)
		}
		if got.kind != "native" {
			t.Errorf("%q kind = %q, want native", id, got.kind)
		}
		if got.auth != "NONE" {
			t.Errorf("%q authType = %q, want NONE", id, got.auth)
		}
		if !got.anon {
			t.Errorf("%q supportsAnonymous = false, want true", id)
		}
		hasSearch, hasMagnet := false, false
		for _, c := range got.caps {
			switch c {
			case "SEARCH":
				hasSearch = true
			case "MAGNET_LINK":
				hasMagnet = true
			}
		}
		if !hasSearch || !hasMagnet {
			t.Errorf("%q capabilities = %v, want SEARCH+MAGNET_LINK in the body", id, got.caps)
		}
	}

	// The catalogue body is strictly larger than the bundled natives — the
	// curated set is additive (the "more than 4 providers" user-visible fact).
	if len(resp.Providers) < 3 {
		t.Fatalf("GET /providers returned %d providers, want >= 3 curated", len(resp.Providers))
	}
}
