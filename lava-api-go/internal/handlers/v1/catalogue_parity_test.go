package v1

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/curated"
)

// TestCatalogueRouteParity_EveryAdvertisedProviderIsRoutable guards the
// catalogue ↔ per-provider-route parity invariant:
//
//	Every provider the engine advertises in GET /providers MUST resolve through
//	the real /v1/:provider/... router. No catalogue entry may 404 ("unknown
//	provider") on its own per-provider route.
//
// Why it matters (device-independent, real user impact): the Android onboarding
// wizard fetches GET /providers, renders the provider list from that body, and
// then issues /v1/{id}/search (and other per-provider calls) against the same
// engine. If the catalogue advertises an id that the router cannot resolve, the
// user picks a provider that is visibly listed yet every subsequent call returns
// HTTP 404 "unknown_provider" — a provider that "exists" in the UI but is dead
// on use. That is exactly the §6.E (Capability Honesty) / §6.G (End-to-End
// Provider Operational Verification) failure class. The catalogue handler
// iterates reg.All(); the route middleware resolves reg.Get(id). Production
// (router.Build) wires both over ONE registry — this test mirrors that exact
// wiring and proves the two views cannot diverge for the real curated set.
//
// The probe is fully OFFLINE and DETERMINISTIC: for each advertised id it hits a
// route whose capability the provider does NOT declare (curated providers do
// SEARCH + MAGNET_LINK, never FORUM_TREE/FAVORITES/COMMENTS). The provider
// middleware runs BEFORE the handler, so:
//   - unknown id            -> 404 unknown_provider      (the bug we forbid)
//   - known id, unsupported -> 501 unsupported_capability (proves it resolves)
//
// Asserting 501 (and explicitly NOT 404) on an unsupported route proves the id
// is routable through the real router WITHOUT making any network call into a
// real curated handler. Reaching 501 means the middleware found the provider in
// the routing registry — i.e. the catalogue id and the route registry agree.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — actually run, not just claimed:
//
//	Mutation: temporarily give the catalogue handler a SEPARATE registry that
//	          additionally holds a bogus provider id "ghostprovider" which is NOT
//	          registered in the routing registry (simulating catalogue ↔ route
//	          drift — the exact bug class this test guards).
//	Observed: catalogue_parity_test.go RED with
//	          'catalogue advertises provider "ghostprovider" but GET
//	           /v1/ghostprovider/forum returned 404 unknown_provider: the
//	           catalogue and the per-provider router DISAGREE (catalogue<->route
//	           drift) — a user would see "ghostprovider" in the list and every
//	           call to it would 404'.
//	Reverted: yes (committed test wires ONE shared registry, like production).
func TestCatalogueRouteParity_EveryAdvertisedProviderIsRoutable(t *testing.T) {
	gin.SetMode(gin.TestMode)

	// ONE registry feeds BOTH the catalogue and the per-provider routes —
	// exactly as internal/router.Build wires production. The real curated
	// providers are the system under test.
	reg := provider.NewRegistry()
	curated.RegisterAll(reg)

	engine := buildCatalogueParityEngine(reg, reg)

	// 1. Fetch the catalogue the client actually renders from.
	advertised := fetchCatalogue(t, engine)
	if len(advertised) == 0 {
		t.Fatalf("GET /providers returned an empty catalogue; expected the curated set")
	}

	// 2. For every advertised provider, prove its per-provider route resolves.
	for _, p := range advertised {
		probeCap, probeURL, ok := unsupportedProbeRoute(p.id, p.capabilities)
		if !ok {
			// A curated provider that declares EVERY route-capability would leave
			// nothing to probe offline. None do today; flag loudly if that
			// changes so the invariant is re-derived rather than silently skipped.
			t.Fatalf("provider %q advertises every route-capability %v; cannot probe routability offline — re-derive the probe", p.id, p.capabilities)
		}

		w := httptest.NewRecorder()
		req, _ := http.NewRequest(http.MethodGet, probeURL, nil)
		engine.ServeHTTP(w, req)

		switch w.Code {
		case http.StatusNotFound:
			t.Errorf("catalogue advertises provider %q but GET %s returned 404 unknown_provider: the catalogue and the per-provider router DISAGREE (catalogue<->route drift) — a user would see %q in the list and every call to it would 404; body=%s",
				p.id, probeURL, p.id, w.Body.String())
		case http.StatusNotImplemented:
			// Resolved to the real provider; capability gating honestly rejected
			// the unsupported %s route. This is the parity-confirming outcome.
		default:
			t.Errorf("probe GET %s for advertised provider %q returned %d, want 501 unsupported_capability (provider lacks %s); a non-404/non-501 here means the offline probe is unsound — body=%s",
				probeURL, p.id, w.Code, probeCap, w.Body.String())
		}
	}
}

// TestCatalogueRouteParity_DetectsCatalogueRouteDrift is the falsifiability
// rehearsal made permanent + executable: it wires the catalogue over a registry
// that advertises a provider id ("ghostprovider") absent from the ROUTING
// registry, and asserts the parity probe catches the drift (the route 404s).
// This proves the parity check above is not a bluff — it actually fails when the
// catalogue and the router disagree.
func TestCatalogueRouteParity_DetectsCatalogueRouteDrift(t *testing.T) {
	gin.SetMode(gin.TestMode)

	routeReg := provider.NewRegistry()
	curated.RegisterAll(routeReg)

	// Catalogue registry = the real curated set PLUS a ghost the router never
	// learns about. This is the divergence the parity invariant forbids.
	catalogueReg := provider.NewRegistry()
	curated.RegisterAll(catalogueReg)
	catalogueReg.Register(&driftGhostProvider{})

	engine := buildCatalogueParityEngine(catalogueReg, routeReg)

	advertised := fetchCatalogue(t, engine)

	ghostSeen := false
	ghostRouteCode := 0
	for _, p := range advertised {
		if p.id != "ghostprovider" {
			continue
		}
		ghostSeen = true
		_, probeURL, ok := unsupportedProbeRoute(p.id, p.capabilities)
		if !ok {
			t.Fatalf("ghost probe URL not derivable for caps %v", p.capabilities)
		}
		w := httptest.NewRecorder()
		req, _ := http.NewRequest(http.MethodGet, probeURL, nil)
		engine.ServeHTTP(w, req)
		ghostRouteCode = w.Code
	}

	if !ghostSeen {
		t.Fatalf("setup error: ghostprovider was not advertised in the catalogue")
	}
	// The whole point: a catalogue id missing from the router 404s on its route.
	// The parity test above turns this same 404 into a failure for the REAL set.
	if ghostRouteCode != http.StatusNotFound {
		t.Fatalf("drift-detection sanity check: advertised-but-unrouted provider returned %d on its route, want 404 — the parity probe would NOT catch catalogue<->route drift (the parity test is a bluff)", ghostRouteCode)
	}
}

// --- helpers (test-local) ---------------------------------------------------

type catalogueEntry struct {
	id           string
	displayName  string
	capabilities []string
}

// buildCatalogueParityEngine mirrors internal/router.Build's catalogue + v1
// wiring (minus the auth chain, which is irrelevant to route resolution): the
// catalogue handler over catalogueReg and the /v1/:provider group over routeReg.
// In production both are the SAME registry; the two-arg shape lets the
// drift-detection test deliberately diverge them.
func buildCatalogueParityEngine(catalogueReg, routeReg *provider.ProviderRegistry) *gin.Engine {
	engine := gin.New()
	engine.GET("/providers", NewProvidersHandler(catalogueReg).GetProviders)
	v1 := engine.Group("/v1/:provider")
	Register(v1, &Deps{}, routeReg)
	return engine
}

func fetchCatalogue(t *testing.T, engine *gin.Engine) []catalogueEntry {
	t.Helper()
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/providers", nil)
	engine.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("GET /providers status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		Providers []struct {
			ID           string   `json:"id"`
			DisplayName  string   `json:"displayName"`
			Capabilities []string `json:"capabilities"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("GET /providers body is not the expected JSON: %v; body=%s", err, w.Body.String())
	}
	out := make([]catalogueEntry, 0, len(resp.Providers))
	for _, p := range resp.Providers {
		out = append(out, catalogueEntry{id: p.ID, displayName: p.DisplayName, capabilities: p.Capabilities})
	}
	return out
}

// unsupportedProbeRoute picks a per-provider route whose capability the provider
// does NOT advertise, so a request to it is rejected by the provider middleware
// with 501 BEFORE any network handler runs. It returns the capability, the
// concrete URL for the given provider id, and whether a probe was found.
//
// The candidate order prefers parameter-free GET routes (forum, favorites) so
// the probe never depends on a path id. Mapping mirrors v1.Register.
func unsupportedProbeRoute(id string, advertised []string) (cap, url string, ok bool) {
	have := make(map[string]bool, len(advertised))
	for _, c := range advertised {
		have[c] = true
	}
	candidates := []struct {
		cap  string
		path string
	}{
		{"FORUM_TREE", "/v1/%s/forum"},
		{"FAVORITES", "/v1/%s/favorites"},
		{"COMMENTS", "/v1/%s/comments/1"},
		{"BROWSE", "/v1/%s/browse/1"},
		{"TOPIC", "/v1/%s/topic/1"},
	}
	for _, c := range candidates {
		if !have[c.cap] {
			return c.cap, fmt.Sprintf(c.path, id), true
		}
	}
	return "", "", false
}

// driftGhostProvider is a minimal Provider used ONLY by the drift-detection
// test: it embeds the package fakeProvider (which supplies every operation +
// Health) and pins an id the routing registry never learns about. It declares
// only SEARCH so the parity probe routes it to /v1/ghostprovider/forum (an
// unsupported, parameter-free route).
type driftGhostProvider struct {
	fakeProvider
}

func (d *driftGhostProvider) ID() string { return "ghostprovider" }
func (d *driftGhostProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch}
}
