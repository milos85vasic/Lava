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

	// The on-device catalogue is now strictly larger than the 5 bundled natives
	// (rutracker, nnmclub, kinozal, archiveorg, gutenberg) — the curated set is
	// additive. This is the "more than the natives" assertion in concrete form.
	if got := len(registry.All()); got < 10 {
		t.Errorf("embed registry has %d providers, want >= 10 (5 natives + 5 curated)", got)
	}
}
