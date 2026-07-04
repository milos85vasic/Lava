# Bugfixes — 2026-07-04 QA cycle (operator autonomous-loop)

## Phase 1 evidence (server-side, before device matrix)

Go API (`lava-api-go`) probed directly on nezha (Linux x86_64, KVM, VPN egress):

| Provider | Auth | /v1/{p}/search status | Root cause |
|---|---|---|---|
| gutenberg | NONE | 200 ✅ | works keyless (dual-stack upstream) |
| rutracker | CAPTCHA_LOGIN | 200 ✅ WITH `Auth-Token: rutracker:cookie:<bb_session>` (401 without) | client must login→persist→send provider session |
| kinozal | FORM_LOGIN | login 200 ✅ (server-side); search needs `Auth-Token` (device TBA) | same provider-session flow |
| nnmclub | FORM_LOGIN | login 401 (Turnstile) | external block — Cloudflare Turnstile (honest, not a code bug) |
| archiveorg | NONE | 502 | IPv4-only upstream stalls through Mullvad VPN egress (per-destination routing needed) |
| yts | NONE | 502 | same egress class |
| thepiratebay | NONE | 502 | same egress class |
| tokyotosho | NONE | 502 | same egress class |
| nyaa | NONE | 502 | DPI-blocked on direct; needs VPN (opposite of archiveorg) |
| torrentscsv | NONE | 502 | egress |
| bitsearch | NONE | 502 | egress |
| knaben | NONE | 502 | egress |

**Key derivation verified:** `base64(rawUUIDBytes)` of first `LAVA_AUTH_ACTIVE_CLIENTS` UUID → gutenberg 200 confirms the `Lava-Auth` middleware accepts it.

**Device matrix (goapi, containerized KVM emulator, API 34):** in progress — rutracker×1080p = PASS clean (marker_download_ok=true, real result id=6867145, video recorded). Remaining cells running.

## Operator symptom classification (working hypothesis, evidence-pending)

"Search does not work, never get any results" — CONFIRMED (goapi probe table above): the 502-egress provider set (archiveorg/yts/thepiratebay/tokyotosho/nyaa/torrentscsv/bitsearch/knaben) returns no rows through the containerized goapi's VPN egress, while rutracker/kinozal/gutenberg return results. UNCONFIRMED: which providers the operator's specific failing search targeted (pending the operator's provider selection at report time). The fix is per-destination egress routing (operator-infra decision per CONTINUATION 2026-07-02), NOT a parser patch and NOT a global bypass (a global bypass breaks rutracker/kinozal which need the VPN exit-IP).
