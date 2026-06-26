package v1

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/curated"
)

// §6.E CAPABILITY HONESTY — the DECLARED-side invariant.
//
// catalogue_parity_test.go guards the UNDECLARED side: a capability a provider
// does NOT advertise must 501 on its route. This file guards the converse, which
// is the stronger §6.E half: every capability a provider DECLARES in
// GET /providers MUST be backed by a real, served operation. A provider that
// lists a capability in its catalogue while the corresponding operation 404s
// (unknown provider / no route) or 501s (gate rejects it as unsupported) is
// advertising a phantom capability — the exact "capability declared but feature
// returns Not-implemented" failure §6.E forbids ("Capability declared ⇒ feature
// interface returned ⇒ at least one real-stack test exists for the capability").
//
// Why it matters (device-independent, real user impact): the Android client
// renders per-provider affordances (search box, magnet/download button, forum
// browser) from the catalogue's `capabilities` array. If the engine advertises a
// capability the router cannot serve, the user taps an affordance that is
// visibly offered yet every call behind it fails — a feature that "exists" in
// the UI but is dead on use.
//
// THE INVARIANT, per declared capability c on each curated provider p:
//
//   (a) c is ROUTE-BACKED (SEARCH/BROWSE/FORUM_TREE/TOPIC/TORRENT_DOWNLOAD/
//       COMMENTS/FAVORITES — the caps v1.Register mounts a /v1/:provider route
//       for): hitting that route through the PRODUCTION capability gate
//       (middleware.ProviderMiddleware — the same middleware v1.Register mounts
//       per route) MUST reach the handler. Concretely the request must NOT
//       return 404 (unknown_provider / no such route) and must NOT return 501
//       (unsupported_capability). Reaching the handler proves the gate honors
//       the declared capability — i.e. the catalogue and the router agree that
//       the capability is served. OR
//
//   (b) c is a RESULT-EMBEDDED carrier capability (MAGNET_LINK / HTTP_DOWNLOAD):
//       these have NO standalone route by design — a magnet is delivered inside
//       /search result rows, not fetched from its own endpoint. Honesty here
//       means the carrier route (SEARCH) is itself declared + served, so the
//       embedded artifact is actually reachable. OR
//
//   (c) anything else is a PHANTOM capability: declared in the catalogue but
//       neither routable nor a recognized result-embedded carrier ⇒ §6.E
//       violation, reported (never hidden).
//
// The probe runs the REAL middleware.ProviderMiddleware over the REAL curated
// registry (curated.RegisterAll), fully OFFLINE and DETERMINISTIC: a sentinel
// terminal handler (HTTP 418) stands in for the network-bound real handler
// precisely so the test makes ZERO outbound calls while still exercising the
// production §6.E gate. 418 is an unmistakable "the gate passed the request
// through to the handler" marker, distinct from the gate's own 404/501 verdicts.

// capHonestyServed is the sentinel status the terminal handler returns once the
// production capability gate has passed a request through. It is deliberately a
// status no gate branch ever produces (the gate emits only 404 or 501), so any
// non-418 outcome on a declared route is unambiguously a gate rejection.
const capHonestyServed = http.StatusTeapot // 418

// capHonestyRouteBacked maps each capability that v1.Register exposes as a
// dedicated /v1/:provider route to a representative concrete request path
// (%s = provider id). It mirrors v1.Register's capability→route wiring exactly.
// Capabilities absent here (MAGNET_LINK, HTTP_DOWNLOAD, RSS, UPLOAD,
// USER_PROFILE) have NO standalone route by design and are classified below.
var capHonestyRouteBacked = map[provider.ProviderCapability]string{
	provider.CapSearch:          "/v1/%s/search",
	provider.CapBrowse:          "/v1/%s/browse/1",
	provider.CapForumTree:       "/v1/%s/forum",
	provider.CapTopic:           "/v1/%s/topic/1",
	provider.CapTorrentDownload: "/v1/%s/torrent/1",
	provider.CapComments:        "/v1/%s/comments/1",
	provider.CapFavorites:       "/v1/%s/favorites",
}

// capHonestyResultEmbedded maps each result-embedded capability to its carrier
// capability — the route whose response actually delivers the artifact. A
// magnet link / http download URL is embedded in SEARCH result rows; declaring
// it is honest iff the carrier (SEARCH) is itself declared + served.
var capHonestyResultEmbedded = map[provider.ProviderCapability]provider.ProviderCapability{
	provider.CapMagnetLink:   provider.CapSearch,
	provider.CapHTTPDownload: provider.CapSearch,
}

// buildCapabilityGateEngine mounts the PRODUCTION capability gate
// (middleware.ProviderMiddleware) for every route-backed capability over reg,
// each followed by the capHonestyServed sentinel. This is the exact gate
// v1.Register wires per route (internal/handlers/v1/handlers.go: each
// group.GET/POST is chained with middleware.ProviderMiddleware(reg, cap)); the
// sentinel replaces the network handler so the §6.E gate is exercised offline.
func buildCapabilityGateEngine(reg *provider.ProviderRegistry) *gin.Engine {
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	sentinel := func(c *gin.Context) { c.Status(capHonestyServed) }
	g := engine.Group("/v1/:provider")
	g.GET("/search", middleware.ProviderMiddleware(reg, provider.CapSearch), sentinel)
	g.GET("/browse/:id", middleware.ProviderMiddleware(reg, provider.CapBrowse), sentinel)
	g.GET("/forum", middleware.ProviderMiddleware(reg, provider.CapForumTree), sentinel)
	g.GET("/topic/:id", middleware.ProviderMiddleware(reg, provider.CapTopic), sentinel)
	g.GET("/torrent/:id", middleware.ProviderMiddleware(reg, provider.CapTorrentDownload), sentinel)
	g.GET("/comments/:id", middleware.ProviderMiddleware(reg, provider.CapComments), sentinel)
	g.GET("/favorites", middleware.ProviderMiddleware(reg, provider.CapFavorites), sentinel)
	return engine
}

// capHonestyProbe issues req for capability c on provider id through engine and
// returns the resulting status code (or 0 if c is not route-backed).
func capHonestyProbe(engine *gin.Engine, id string, c provider.ProviderCapability) (status int, routeBacked bool) {
	tmpl, ok := capHonestyRouteBacked[c]
	if !ok {
		return 0, false
	}
	w := httptest.NewRecorder()
	r, _ := http.NewRequest(http.MethodGet, fmt.Sprintf(tmpl, id), nil)
	engine.ServeHTTP(w, r)
	return w.Code, true
}

// TestCapabilityHonesty_DeclaredCapabilitiesAreServed asserts that every
// capability advertised by every curated provider is actually served (§6.E).
func TestCapabilityHonesty_DeclaredCapabilitiesAreServed(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	curated.RegisterAll(reg)
	engine := buildCapabilityGateEngine(reg)

	all := reg.All()
	if len(all) == 0 {
		t.Fatalf("curated.RegisterAll produced an empty registry; expected the curated set")
	}

	checkedRouteBacked := 0
	checkedEmbedded := 0
	for _, p := range all {
		id := p.ID()
		declared := p.Capabilities()
		if len(declared) == 0 {
			t.Errorf("provider %q declares ZERO capabilities — it would render as a selectable provider with no usable operation (§6.E)", id)
			continue
		}
		// Index this provider's declared set for the carrier lookup in branch (b).
		declaredSet := make(map[provider.ProviderCapability]bool, len(declared))
		for _, c := range declared {
			declaredSet[c] = true
		}

		for _, c := range declared {
			switch {
			case isRouteBacked(c):
				// (a) route-backed: the gate MUST pass it through (418), never
				// 404 (unknown provider / no route) and never 501 (unsupported).
				status, _ := capHonestyProbe(engine, id, c)
				switch status {
				case capHonestyServed:
					checkedRouteBacked++ // gate honored the declared capability
				case http.StatusNotFound:
					t.Errorf("§6.E VIOLATION: provider %q advertises capability %q but %s returned 404 — the catalogue offers a capability the router cannot resolve (a user taps the affordance and every call dies)",
						id, c, fmt.Sprintf(capHonestyRouteBacked[c], id))
				case http.StatusNotImplemented:
					t.Errorf("§6.E VIOLATION: provider %q advertises capability %q but the production gate returned 501 unsupported_capability on %s — declared-but-not-served (phantom capability)",
						id, c, fmt.Sprintf(capHonestyRouteBacked[c], id))
				default:
					t.Errorf("§6.E probe for provider %q capability %q returned %d, want 418 (gate passed request through); a non-418/404/501 status means the offline gate probe is unsound",
						id, c, status)
				}
			case isResultEmbedded(c):
				// (b) result-embedded carrier: honest iff the carrier (SEARCH)
				// is itself declared (and, being route-backed, served — proven
				// by branch (a) above when the loop reaches the carrier).
				carrier := capHonestyResultEmbedded[c]
				if !declaredSet[carrier] {
					t.Errorf("§6.E VIOLATION: provider %q advertises result-embedded capability %q but does NOT declare its carrier %q — the embedded artifact has no route that delivers it (phantom capability)",
						id, c, carrier)
					continue
				}
				checkedEmbedded++
			default:
				// (c) phantom: declared but neither routable nor a recognized
				// result-embedded carrier.
				t.Errorf("§6.E VIOLATION: provider %q advertises capability %q which is neither route-backed nor a recognized result-embedded carrier — it is declared in the catalogue with no operation that serves it (phantom capability)",
					id, c)
			}
		}
	}

	if checkedRouteBacked == 0 {
		t.Fatalf("no route-backed capability was verified across the curated set; the positive invariant proved nothing — re-derive the probe")
	}
	t.Logf("§6.E OK: verified %d route-backed + %d result-embedded declared capabilities across %d curated providers are served",
		checkedRouteBacked, checkedEmbedded, len(all))
}

// TestCapabilityHonesty_GateDiscriminates is the non-vacuity / falsifiability
// guard for the positive test above: it proves the production gate actually
// REJECTS an undeclared route-backed capability with 501. Without this, a gate
// that passed EVERYTHING through (418) would make the positive assertions
// meaningless. Every curated provider declares only SEARCH + MAGNET_LINK, so
// FORUM_TREE (route-backed, undeclared by all) MUST 501 for each of them.
func TestCapabilityHonesty_GateDiscriminates(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	curated.RegisterAll(reg)
	engine := buildCapabilityGateEngine(reg)

	all := reg.All()
	if len(all) == 0 {
		t.Fatalf("curated.RegisterAll produced an empty registry")
	}

	for _, p := range all {
		id := p.ID()
		declaredSet := make(map[provider.ProviderCapability]bool)
		for _, c := range p.Capabilities() {
			declaredSet[c] = true
		}
		// Find a route-backed capability this provider does NOT declare.
		var undeclared provider.ProviderCapability
		found := false
		for c := range capHonestyRouteBacked {
			if !declaredSet[c] {
				undeclared = c
				found = true
				break
			}
		}
		if !found {
			// A provider declaring every route-backed capability would leave
			// nothing to probe; none do today. Flag loudly rather than skip.
			t.Fatalf("provider %q declares every route-backed capability; cannot prove gate discrimination — re-derive", id)
		}
		status, _ := capHonestyProbe(engine, id, undeclared)
		if status != http.StatusNotImplemented {
			t.Errorf("gate-discrimination check: provider %q on undeclared capability %q returned %d, want 501 — the gate does NOT discriminate, so the DeclaredCapabilitiesAreServed assertions are vacuous",
				id, undeclared, status)
		}
	}
}

// TestCapabilityHonesty_ClassifierCatchesPhantomCapability is the
// reproduce-first rehearsal made permanent + executable. It registers a
// deliberately DISHONEST provider that advertises CapUpload — a capability with
// NO route and NO result-embedded carrier — and asserts the §6.E classifier
// flags it as a phantom capability. This proves TestCapabilityHonesty_
// DeclaredCapabilitiesAreServed is not a bluff: it actually fails when a
// provider declares an unserved capability.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — actually run, not just claimed:
//
//	Mutation: into the curated registry feeding the MAIN test, register a
//	          provider that declares CapUpload (no route, no carrier).
//	Observed: TestCapabilityHonesty_DeclaredCapabilitiesAreServed RED with
//	          '§6.E VIOLATION: provider "phantomupload" advertises capability
//	           "UPLOAD" which is neither route-backed nor a recognized
//	           result-embedded carrier ... (phantom capability)'.
//	Reverted: yes — the dishonest provider lives ONLY in this sub-test; the main
//	          test runs over the unmodified curated.RegisterAll set.
func TestCapabilityHonesty_ClassifierCatchesPhantomCapability(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	curated.RegisterAll(reg)
	reg.Register(&phantomCapabilityProvider{})
	engine := buildCapabilityGateEngine(reg)

	p, err := reg.Get("phantomupload")
	if err != nil {
		t.Fatalf("setup: phantom provider not registered: %v", err)
	}

	// Re-run the exact classification the main test applies, but ONLY for the
	// phantom provider, and assert the phantom capability is caught.
	caught := false
	for _, c := range p.Capabilities() {
		if isRouteBacked(c) {
			status, _ := capHonestyProbe(engine, p.ID(), c)
			// SEARCH is served; not the violation we are demonstrating.
			_ = status
			continue
		}
		if isResultEmbedded(c) {
			continue
		}
		// CapUpload lands here — exactly the phantom branch the main test fails on.
		caught = true
	}
	if !caught {
		t.Fatalf("classifier did NOT flag the phantom CapUpload capability — TestCapabilityHonesty_DeclaredCapabilitiesAreServed would be a bluff (it cannot catch a declared-but-unserved capability)")
	}
}

func isRouteBacked(c provider.ProviderCapability) bool {
	_, ok := capHonestyRouteBacked[c]
	return ok
}

func isResultEmbedded(c provider.ProviderCapability) bool {
	_, ok := capHonestyResultEmbedded[c]
	return ok
}

// phantomCapabilityProvider embeds the package fakeProvider (which implements
// every Provider method) and overrides ID + Capabilities to advertise CapUpload
// — a capability with no route and no result-embedded carrier. Used ONLY by the
// classifier-catches-phantom test to prove the §6.E classifier discriminates.
type phantomCapabilityProvider struct {
	fakeProvider
}

func (d *phantomCapabilityProvider) ID() string { return "phantomupload" }
func (d *phantomCapabilityProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch, provider.CapUpload}
}
