# Backend search evidence — RuTor via lava-api-go (Go backend)

- Captured (UTC): 2026-06-29T22:29:38Z
- Commit: 899ad47b8ad4092c9eb0aa579418492366a69b05
- Backend: lava-api-go via ./start.sh (podman compose, profile api-go); health https://127.0.0.1:8443/health -> {"status":"alive"}
- Auth: client-auth header `Lava-Auth` = base64(16-byte client UUID); server verifies hex(HMAC-SHA256(uuid, LAVA_AUTH_HMAC_SECRET)) against LAVA_AUTH_ACTIVE_CLIENTS. A VALID header was used. All credentials/secrets REDACTED — none written to any evidence file (leak scan: PASS).

## Headline answer

NO — the Go API did NOT return real RuTor results + download links for 1080p or mp3, because **RuTor is NOT a provider in the lava-api-go backend**. Both target queries returned a deterministic REAL failure:

| Query | Endpoint | HTTP | Verbatim body |
|-------|----------|------|---------------|
| 1080p | GET /v1/rutor/search?query=1080p | 404 | {"error":"unknown_provider","message":"provider \"rutor\" not found"} |
| mp3   | GET /v1/rutor/search?query=mp3   | 404 | {"error":"unknown_provider","message":"provider \"rutor\" not found"} |

The request passed the Lava-Auth middleware (bad header -> 401; these -> 404 from the route handler), so the 404 is the handler reporting no provider id `rutor` is registered.

## Why rutor is not wired
- Live catalogue GET /providers (public) = 13 providers, rutor ABSENT: yts, torrentscsv, bitsearch, knaben, torrentdownloads, tokyotosho, rutracker, nnmclub, gutenberg, thepiratebay, nyaa, kinozal, archiveorg.
- cmd/lava-api-go/main.go registers rutracker, nnmclub, kinozal, archiveorg, gutenberg + curated.RegisterAll (thepiratebay, yts, torrentscsv, bitsearch, knaben, nyaa, torrentdownloads, tokyotosho). No rutor.
- Only rutor refs in Go: a comment in internal/auth/multiprovider.go, a fakeProvider in registry_test.go, a Jackett test. No native rutor source.
- Jackett OFF (LAVA_API_JACKETT_ENABLED default false, unset) -> no jackett indexer named rutor.
- RUTOR_USERNAME/RUTOR_PASSWORD exist in .env but are NOT consumed by any Go provider.

## Control proof (falsifiability / anti-bluff)
Same authed request shape against REGISTERED magnet providers returned real results + real download resources, proving the 404 is "rutor not wired", not broken auth/request/parser:

| Provider | Query | HTTP | Count | Example title | Download type |
|----------|-------|------|-------|---------------|---------------|
| thepiratebay | 1080p | 200 | 100 | House of the Dragon S03E02 1080p HEVC x265-MeGusta | magnet:?xt=urn:btih:... |
| thepiratebay | mp3 | 200 | 100 | Various Artists - 00s Hits - 100 Top Songs (2025) Mp3 320kbps [PMEDIA] | magnet |
| nyaa | mp3 | 200 | 75 | Disney Twisted-Wonderland Original Soundtrack [mp3] | magnet |
| yts | 1080p | 200 | 0 | (empty; yts indexes movie titles, "1080p" matches none) | — |

Full titles + magnet links + infoHash embedded under controlProof in rutor-1080p.json / rutor-mp3.json (public magnets, no creds).

## Conclusion
The API's core search + download-resource feature is REAL and working for registered providers (real titles + real magnet:?xt=urn:btih: links for both 1080p and mp3). For RuTor specifically, lava-api-go has no rutor provider, so /v1/rutor/search returns 404 unknown_provider for both queries. To make RuTor reachable, add+register a native rutor provider in cmd/lava-api-go/main.go (or enable Jackett with a rutor indexer).

## Files
- rutor-1080p.json — verbatim 404 + live catalogue + control proof
- rutor-mp3.json — verbatim 404 + live catalogue + control proof (thepiratebay + nyaa)
- summary.md — this file
