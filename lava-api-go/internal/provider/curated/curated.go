// Package curated aggregates the compiled-in curated public-tracker providers
// and registers them into a provider.ProviderRegistry.
//
// Both startup paths — the on-device embed (internal/mobile) and the standalone
// server (cmd/lava-api-go) — call RegisterAll, so the embedded api-app and the
// server expose the SAME curated provider set and cannot silently drift apart
// (§6.J registration-parity guard: the "module-green-but-app-missing-a-provider"
// class of bug is structurally impossible when there is one registration site).
//
// Each curated provider is a self-contained Go provider.Provider for a popular
// PUBLIC tracker, sourced from that tracker's own anonymous API — no external
// Jackett sidecar, compiled into liblavaapi.so "same as the API itself" (Defect
// B, 2026-06-12 design). This is a curated, growing subset (not a full Jackett
// mirror); every provider listed here is real + tested end-to-end.
package curated

import (
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/curated/bitsearch"
	"digital.vasic.lava.apigo/internal/provider/curated/knaben"
	"digital.vasic.lava.apigo/internal/provider/curated/nyaa"
	"digital.vasic.lava.apigo/internal/provider/curated/thepiratebay"
	"digital.vasic.lava.apigo/internal/provider/curated/tokyotosho"
	"digital.vasic.lava.apigo/internal/provider/curated/torrentscsv"
	"digital.vasic.lava.apigo/internal/provider/curated/yts"
	// NOTE: torrentdownloads is intentionally NOT imported/registered — its
	// upstream is DEAD (see RegisterAll below). Kept as an unregistered package
	// so it can be re-enabled if the upstream returns.
)

// RegisterAll registers every curated provider into r. Adding a curated
// provider means adding exactly one line here — both startup paths pick it up.
func RegisterAll(r *provider.ProviderRegistry) {
	r.Register(thepiratebay.New())
	r.Register(yts.New())
	r.Register(torrentscsv.New())
	r.Register(bitsearch.New())
	r.Register(knaben.New())
	r.Register(nyaa.New())
	// torrentdownloads UNREGISTERED 2026-07-02 — DEAD UPSTREAM. Re-probe of
	// https://www.torrentdownloads.pro/rss.xml returned HTTP 522 (Cloudflare
	// "origin down") x3 and the root host timed out (origin unreachable), so
	// every real user search via this provider returned 0 results — a silent
	// broken provider in the catalogue. Removing the registration hides it from
	// GET /v1/providers. The internal/provider/curated/torrentdownloads package
	// is retained (not deleted): re-add `r.Register(torrentdownloads.New())`
	// (and its import above) once the upstream returns and re-probes healthy.
	r.Register(tokyotosho.New())
}
