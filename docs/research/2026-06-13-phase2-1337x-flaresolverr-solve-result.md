# Phase-2 1337x via FlareSolverr — live solve result (2026-06-13)

Operator authorized the FlareSolverr/Chromium dependency ("all 3"). The
FlareSolverr Go client (`internal/provider/flaresolverr/`) is built + unit-tested
+ pushed (`60969640`). The remaining question for a 1337x curated provider: can
FlareSolverr actually SOLVE 1337x's Cloudflare challenge so the provider can
fetch real results?

## Decisive live test (real, on this host)
FlareSolverr `ghcr.io/flaresolverr/flaresolverr:latest` brought up on podman
(container `lava-flaresolverr`, port 8191).

| Probe | Result |
|-------|--------|
| `request.get https://1337x.to/search/ubuntu/1/` (maxTimeout 75s) | **status: error — "Error solving the challenge. Timeout after 75.0 seconds"**; solution.status none; 0 bytes HTML |
| CONTROL: `request.get https://example.com/` (non-CF) | status: ok, solution.status 200, 544 bytes HTML, 8.1s |

The control proves FlareSolverr itself is functional on this host. The 1337x
failure is specifically **1337x's current Cloudflare challenge is NOT solvable by
this FlareSolverr build** (it times out at 75s while a non-CF page solves in 8s).

## Honest verdict
**1337x CANNOT be shipped as a working curated provider right now.** A provider
whose only fetch path times out on every request would be a §6.E/§6.L bluff (a
provider that can't actually return results). Per the anti-bluff mandate, it is
NOT added.

- ✅ The FlareSolverr seam (Go client) is DONE + tested + reusable — ready for a
  CF-gated provider the moment FlareSolverr can solve that site (a newer
  FlareSolverr build, a different CF-gated tracker, or 1337x if its challenge
  becomes solvable).
- ⛔ 1337x itself: BLOCKED at the CF-solve layer (FlareSolverr-vs-current-1337x-CF
  limitation, NOT a code gap). Re-test periodically; do not ship until a real
  solve returns a parseable results page.

The 12 shipped curated/native providers (all live-reachable, see
`.lava-ci-evidence/curated-live-reachability/2026-06-13-audit.md`) remain the
honest, working provider set.

Container torn down after the test (§6.M host hygiene — no idle Chromium).
