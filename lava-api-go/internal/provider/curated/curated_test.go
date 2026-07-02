package curated

import (
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// §6.J: asserts RegisterAll wires a REAL, capability-honest provider into the
// registry (on user-visible catalogue metadata — id, kind, caps), not on a call
// count. The single RegisterAll site is what guarantees the embed + server
// paths cannot drift.
func TestRegisterAll_RegistersCuratedProviders(t *testing.T) {
	r := provider.NewRegistry()
	RegisterAll(r)

	p, err := r.Get("thepiratebay")
	if err != nil {
		t.Fatalf("thepiratebay not registered by RegisterAll: %v", err)
	}
	if p.Kind() != "native" {
		t.Errorf("Kind = %q, want native (compiled-in)", p.Kind())
	}
	if !p.SupportsAnonymous() {
		t.Error("SupportsAnonymous must be true for a public anonymous tracker")
	}
	if p.AuthType() != provider.AuthNone {
		t.Errorf("AuthType = %q, want NONE", p.AuthType())
	}
	if !r.Supports("thepiratebay", provider.CapSearch) {
		t.Error("curated thepiratebay must support SEARCH")
	}
	if !r.Supports("thepiratebay", provider.CapMagnetLink) {
		t.Error("curated thepiratebay must support MAGNET_LINK")
	}

	// Every curated provider must be SEARCH + MAGNET_LINK capable, anonymous,
	// and AuthNone — assert across the whole curated set so adding one that
	// breaks the contract fails here.
	// NOTE: torrentdownloads is intentionally absent — it was UNREGISTERED
	// 2026-07-02 (DEAD UPSTREAM: rss.xml → HTTP 522 x3 + origin timeout); the
	// negative assertion below pins that it is NOT served to users.
	for _, id := range []string{"thepiratebay", "yts", "torrentscsv", "bitsearch", "knaben", "nyaa"} {
		p, err := r.Get(id)
		if err != nil {
			t.Fatalf("curated provider %q not registered: %v", id, err)
		}
		if !p.SupportsAnonymous() || p.AuthType() != provider.AuthNone {
			t.Errorf("%q must be anonymous/AuthNone, got anon=%v auth=%q", id, p.SupportsAnonymous(), p.AuthType())
		}
		if !r.Supports(id, provider.CapSearch) || !r.Supports(id, provider.CapMagnetLink) {
			t.Errorf("%q must support SEARCH+MAGNET_LINK", id)
		}
	}

	// torrentdownloads MUST NOT be registered — its upstream is DEAD (rss.xml
	// → HTTP 522 x3 + origin timeout, re-probed 2026-07-02). A registered dead
	// provider returns 0 results for every real user, so it is unregistered in
	// curated.go's RegisterAll. This is the reproduce-first guard: re-adding
	// `r.Register(torrentdownloads.New())` makes r.Get("torrentdownloads")
	// succeed and fails this assertion.
	if _, err := r.Get("torrentdownloads"); err == nil {
		t.Error("torrentdownloads is registered but its upstream is DEAD (522) — it must NOT appear in the curated set / catalogue")
	}
}
