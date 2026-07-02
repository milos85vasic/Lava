package mobile

import (
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// §6.J registration-parity / "embed must not silently miss a provider" guard.
//
// This drives the REAL embed-path factory (newProductionScraperDeps — the exact
// function the on-device api-app uses to build its provider registry) and
// asserts the curated public-tracker providers are present. It is the
// load-bearing proof for Defect B: the on-device GET /providers catalogue
// exposes MORE than the bundled natives, which is the user-visible thing the
// "only 4 providers in onboarding" report was about.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: remove `curated.RegisterAll(registry)` from newProductionScraperDeps.
//	Observed: "embed path did not register curated provider thepiratebay:
//	          provider: not found" (registry.Get error) + the count assertion
//	          "embed registry has 5 providers, want >= 6".
//	Reverted: yes.
func TestEmbedPath_RegistersCuratedProviders(t *testing.T) {
	_, registry := newProductionScraperDeps()

	p, err := registry.Get("thepiratebay")
	if err != nil {
		t.Fatalf("embed path did not register curated provider thepiratebay: %v", err)
	}
	if !registry.Supports("thepiratebay", provider.CapSearch) {
		t.Error("embed thepiratebay missing SEARCH")
	}
	if !registry.Supports("thepiratebay", provider.CapMagnetLink) {
		t.Error("embed thepiratebay missing MAGNET_LINK")
	}
	if !p.SupportsAnonymous() {
		t.Error("embed thepiratebay must be anonymous-capable")
	}

	// yts + torrentscsv are the other curated providers registered via the same
	// RegisterAll.
	if _, err := registry.Get("yts"); err != nil {
		t.Errorf("embed path did not register curated provider yts: %v", err)
	}
	if _, err := registry.Get("torrentscsv"); err != nil {
		t.Errorf("embed path did not register curated provider torrentscsv: %v", err)
	}
	if _, err := registry.Get("bitsearch"); err != nil {
		t.Errorf("embed path did not register curated provider bitsearch: %v", err)
	}
	if _, err := registry.Get("knaben"); err != nil {
		t.Errorf("embed path did not register curated provider knaben: %v", err)
	}
	if _, err := registry.Get("nyaa"); err != nil {
		t.Errorf("embed path did not register curated provider nyaa: %v", err)
	}

	// torrentdownloads MUST NOT be registered on the embed path either — its
	// upstream is DEAD (rss.xml → HTTP 522 x3 + origin timeout, re-probed
	// 2026-07-02), so it was UNREGISTERED in curated.go RegisterAll. On-device
	// the same registration site is used, so the dead provider must not reach
	// the mobile catalogue. Reproduce-first guard: re-registering it in
	// RegisterAll makes registry.Get succeed and fails this assertion.
	if _, err := registry.Get("torrentdownloads"); err == nil {
		t.Error("embed path registered torrentdownloads but its upstream is DEAD (522) — it must NOT appear in the on-device catalogue")
	}

	// The on-device catalogue is now strictly larger than the 5 bundled natives
	// (rutracker, nnmclub, kinozal, archiveorg, gutenberg) — the curated set is
	// additive. 6 curated remain after torrentdownloads was dropped (was 7).
	if got := len(registry.All()); got < 11 {
		t.Errorf("embed registry has %d providers, want >= 11 (5 natives + 6 curated)", got)
	}
}
