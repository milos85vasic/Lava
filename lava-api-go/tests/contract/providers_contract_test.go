// providers_contract_test.go is the deferred GET /providers contract gate for
// the dynamic-provider-discovery endpoint (2026-06-11 spec §4.1).
//
// It boots the REAL production router (router.Build) over a REAL provider
// registry holding the native provider adapters — the SAME construction
// cmd/lava-api-go/main.go and internal/mobile use in production — and issues
// real httptest requests through engine.ServeHTTP (no direct-function shortcut,
// Sixth Law clause 1). No Postgres is needed: /providers builds its catalogue
// purely from registry.All(), and the per-provider /search probe used here is
// gated only by the provider middleware (404/501/dispatch), not persistence.
//
// Anti-bluff posture (§6.E Capability Honesty + §6.J):
//   - Primary assertion is on the USER-VISIBLE response body: the parsed
//     providers[] catalogue (ids, kinds, capabilities, authType) the Android
//     client renders — NOT on "status was 200".
//   - The §6.E gate: every provider that DECLARES the SEARCH capability MUST
//     resolve its /v1/{id}/search route to a non-501. A 501 there would mean
//     the catalogue advertised a capability the route cannot serve — the exact
//     "capability declared but not implemented" bluff §6.E forbids.
//
// Falsifiability (Sixth Law clause 2): see the Bluff-Audit block on
// TestProviders_CatalogueAndCapabilityHonesty.
package contract

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/archiveorg"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/gutenberg"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/router"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// providerDTO mirrors the wire shape of one entry in the GET /providers
// catalogue (handlers/v1/providers.go providerDTO). Held locally so the
// contract test asserts on the JSON the client actually parses, independent of
// the unexported handler struct.
type providerDTO struct {
	ID                string   `json:"id"`
	DisplayName       string   `json:"displayName"`
	Kind              string   `json:"kind"`
	Indexer           string   `json:"indexer,omitempty"`
	Capabilities      []string `json:"capabilities"`
	AuthType          string   `json:"authType"`
	Encoding          string   `json:"encoding"`
	BaseURLs          []string `json:"baseUrls"`
	SupportsAnonymous bool     `json:"supportsAnonymous"`
}

type providersResponse struct {
	Providers []providerDTO `json:"providers"`
}

// contractStubCache satisfies the handlers.Cache / v1.Cache interface without
// persistence — the /providers + /search probes only need the routes reachable,
// not a real cache backend (Postgres is exercised by the e2e + integration
// suites). A miss on every Get forces the search handler down the dispatch path.
type contractStubCache struct{}

func (contractStubCache) Get(context.Context, string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}
func (contractStubCache) Set(context.Context, string, []byte, time.Duration) error { return nil }
func (contractStubCache) Invalidate(context.Context, string) error                 { return nil }

// newNativeRegistry builds a registry with the native provider adapters the
// composition root registers. These are the providers the catalogue MUST
// surface. The upstream base URLs are real-shaped but never contacted by this
// test: the /search probe asserts only on the ROUTE outcome (501 vs not), and
// the providers reach their Search() only far enough for the middleware's §6.E
// capability gate — a Search() that then errors on the (unreachable) upstream
// produces a non-501 status, which is exactly what this contract asserts.
func newNativeRegistry() *provider.ProviderRegistry {
	reg := provider.NewRegistry()
	reg.Register(rutracker.NewProviderAdapter(rutracker.NewClient("https://rutracker.example.invalid")))
	reg.Register(archiveorg.NewProviderAdapter(archiveorg.NewClient("https://archive.example.invalid")))
	reg.Register(gutenberg.NewProviderAdapter(gutenberg.NewClient("https://gutendex.example.invalid")))
	return reg
}

func buildProvidersEngine(reg *provider.ProviderRegistry) http.Handler {
	// Cfg nil ⇒ no auth/backoff/protocol middleware is mounted (router.Build
	// guards those on Cfg != nil), so /providers + /v1/{id}/search are reachable
	// without an Auth-Lava header. Jackett stays OFF (Cfg nil ⇒ JackettEnabled
	// false), so the registry holds exactly the native providers.
	return router.Build(router.Deps{
		Cache:    contractStubCache{},
		Registry: reg,
	})
}

// TestProviders_CatalogueListsNativeProviders is the catalogue-shape assertion:
// GET /providers returns 200 with a parseable body, and every native provider
// the registry holds appears as a row with the expected discriminators.
func TestProviders_CatalogueListsNativeProviders(t *testing.T) {
	engine := buildProvidersEngine(newNativeRegistry())

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/providers", nil)
	engine.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("GET /providers returned %d want 200; body=%q", w.Code, w.Body.String())
	}

	var resp providersResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("GET /providers body is not a providers catalogue JSON: %v; body=%q", err, w.Body.String())
	}

	byID := make(map[string]providerDTO, len(resp.Providers))
	for _, p := range resp.Providers {
		byID[p.ID] = p
	}

	// At least the native providers MUST appear (spec §4.1: the catalogue is
	// what the client populates its provider list from).
	for _, want := range []string{"rutracker", "archiveorg", "gutenberg"} {
		dto, ok := byID[want]
		if !ok {
			t.Fatalf("GET /providers catalogue missing native provider %q; got ids=%v", want, idsOf(resp.Providers))
		}
		if dto.Kind != "native" {
			t.Errorf("provider %q kind=%q want \"native\"", want, dto.Kind)
		}
		if dto.DisplayName == "" {
			t.Errorf("provider %q has empty displayName", want)
		}
		if len(dto.Capabilities) == 0 {
			t.Errorf("provider %q declares no capabilities", want)
		}
	}
}

// TestProviders_CatalogueAndCapabilityHonesty is the load-bearing §6.E gate:
// every provider the catalogue says supports SEARCH MUST resolve its
// /v1/{id}/search route to a non-501. A 501 would mean the catalogue lied about
// a capability the route cannot serve.
//
// Bluff-Audit:
//
//	Test:     TestProviders_CatalogueAndCapabilityHonesty
//	Mutation: in handlers/v1/providers.go GetProviders, append
//	          provider.CapForumTree to capStrs unconditionally for every
//	          provider (advertise a capability gutenberg/archiveorg do NOT
//	          declare). The /v1/{id}/forum probe (added below as the symmetric
//	          check) then 501s while the catalogue claims FORUM_TREE.
//	Observed: "provider \"gutenberg\" advertises FORUM_TREE but GET
//	          /v1/gutenberg/forum returned 501 — capability declared, route
//	          cannot serve it (§6.E violation)".
//	Reverted: yes (see the verbatim run in the task report).
func TestProviders_CatalogueAndCapabilityHonesty(t *testing.T) {
	engine := buildProvidersEngine(newNativeRegistry())

	w := httptest.NewRecorder()
	engine.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/providers", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("GET /providers returned %d want 200; body=%q", w.Code, w.Body.String())
	}
	var resp providersResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("GET /providers body not parseable: %v", err)
	}
	if len(resp.Providers) == 0 {
		t.Fatal("GET /providers returned an empty catalogue — no providers to check")
	}

	// Map each capability the catalogue can advertise to the route that serves
	// it, so a declared capability is checked against its real route resolving
	// to a non-501. Only routes whose §6.E gate is purely capability-based are
	// probed (a 404/4xx/5xx other than 501 means "reached the gate / handler",
	// which is what §6.E requires — the gate did NOT short-circuit at 501).
	capRoute := map[string]func(id string) string{
		"SEARCH":     func(id string) string { return "/v1/" + id + "/search?query=x" },
		"BROWSE":     func(id string) string { return "/v1/" + id + "/browse/1" },
		"FORUM_TREE": func(id string) string { return "/v1/" + id + "/forum" },
		"TOPIC":      func(id string) string { return "/v1/" + id + "/topic/1" },
		"COMMENTS":   func(id string) string { return "/v1/" + id + "/comments/1" },
		"FAVORITES":  func(id string) string { return "/v1/" + id + "/favorites" },
	}

	sawSearchProvider := false
	for _, p := range resp.Providers {
		for _, capStr := range p.Capabilities {
			build, probed := capRoute[capStr]
			if !probed {
				continue // capability has no GET route with a pure capability gate
			}
			if capStr == "SEARCH" {
				sawSearchProvider = true
			}
			pw := httptest.NewRecorder()
			engine.ServeHTTP(pw, httptest.NewRequest(http.MethodGet, build(p.ID), nil))
			if pw.Code == http.StatusNotImplemented {
				t.Errorf("provider %q advertises %s but GET %s returned 501 — "+
					"capability declared, route cannot serve it (§6.E violation); body=%q",
					p.ID, capStr, build(p.ID), pw.Body.String())
			}
		}
	}

	// Guard against a vacuous pass: the catalogue MUST contain at least one
	// SEARCH-capable provider for the honesty check to have teeth.
	if !sawSearchProvider {
		t.Fatal("no catalogue provider declared SEARCH — capability-honesty check ran vacuously")
	}
}

func idsOf(ps []providerDTO) []string {
	out := make([]string, 0, len(ps))
	for _, p := range ps {
		out = append(out, p.ID)
	}
	return out
}
