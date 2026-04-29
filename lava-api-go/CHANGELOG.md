# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) — semantic
versioning per `lava-api-go/internal/version/version.go`. Tag prefix:
`Lava-API-Go-<Name>-<Code>`. Both tags are mirrored to github + gitflic +
gitlab + gitverse with verified identical SHA-1.

## [2.0.2] — 2026-04-29 (`Lava-API-Go-2.0.2-2002`, structural Sixth-Law fixes)

### Fixed (Sixth-Law structural — three bugs in one bug class)

Forensic anchor: `lava-api-go` ran 569 consecutive failing healthchecks
in production while serving 200 on both HTTP/3 and HTTP/2. Compose
validated, every functional test was green, every Challenge was green
— yet the orchestrator labelled the container "unhealthy" and there
was no test asserting the probe could even start. Three sub-bugs were
silent in their own way; each is fixed with a structural contract
test that catches the *class*, not the instance. See root `CLAUDE.md`
Sixth Law clause 6.A for the mandate.

- **compose↔binary flag drift** (`docker-compose.yml`). The compose
  healthcheck passed `--http3` to a probe binary that only registers
  `-url`/`-insecure`/`-timeout`. Removed the compose-level override
  in favour of the Dockerfile's exec-form HEALTHCHECK. Structural
  test: `tests/contract/healthcheck_contract_test.go`
  `TestHealthcheckContract` parses every healthcheck.test array,
  recovers the binary's flag set from its actual Usage output,
  asserts subset.

- **podman-compose CMD-SHELL translation on a sh-less image**.
  podman-compose translates `test: ["CMD", …]` into CMD-SHELL form
  (`sh -c …`), but the runtime image is distroless and has no
  /bin/sh, so every probe exited 1 with empty Output. Documented in
  `docker-compose.yml`. Structural test:
  `TestDockerfileHealthcheckContract` validates the Dockerfile CMD
  array against the same flag-set rule.

- **OCI image format silently strips HEALTHCHECK**. podman defaults
  to OCI image format, which does NOT support HEALTHCHECK in image
  config. Build-time warning was the only signal:
  `"HEALTHCHECK is not supported for OCI image format and will be
  ignored. Must use 'docker' format"`. Resulting images had
  `Healthcheck: null`; containers booted from them had NO probe.
  Fix: `start.sh` and `build_and_release.sh` export
  `BUILDAH_FORMAT=docker`. Structural test:
  `TestLifecycleScriptsForceDockerFormat` asserts both scripts
  contain an active export directive (regex anchored, comment-only
  regression rejected per the rehearsal recorded below).

### Sixth Law extensions (root `CLAUDE.md`, lessons-learned addenda)

- **6.A — Real-binary contract tests.** Every place where a script
  or compose file invokes a binary we own MUST be covered by a
  contract test that recovers the binary's registered flag set from
  its actual Usage output and asserts the script's flag set is a
  strict subset, with a falsifiability rehearsal sub-test that
  re-introduces a known-buggy flag and confirms the checker rejects
  it. Canonical implementation: `tests/contract/healthcheck_contract_test.go`.

- **6.B — Container "Up" is not application-healthy.** `docker
  ps` / `podman ps` `Up` only means PID 1 is alive. Tests asserting
  container state alone are bluffs by Sixth Law clauses 1 and 3.
  Use the same probe the orchestrator uses, or an end-to-end
  functional request at the user-visible surface.

- **6.C — Mirror-state mismatch checks before tagging.** "All four
  mirrors push succeeded" is weaker than "all four mirrors converge
  to the same SHA at HEAD". `scripts/tag.sh` MUST verify post-push
  tip-SHA convergence across github/gitflic/gitlab/gitverse before
  reporting success.

### Falsifiability rehearsals (Sixth-Law clause 2)

| Test | Mutation | Observed | Reverted |
|------|----------|----------|----------|
| `TestHealthcheckContract` | re-introduce `--http3` to docker-compose.yml | FAIL: "service lava-api-go: healthcheck.test passes flag -http3 but /usr/local/bin/healthprobe does not register it" | PASS after restore |
| `TestDockerfileHealthcheck_Falsifiability` | inline Dockerfile fixture with `--http3` | FAIL: "flag -http3 not registered" | n/a (always asserts the failure mode) |
| `TestHealthcheckContract_Falsifiability` | inline YAML fixture with `--http3` | FAIL: "service lava-api-go: healthcheck.test passes flag -http3 but /usr/local/bin/healthprobe does not register it" | n/a (always asserts the failure mode) |
| `TestLifecycleScriptsForceDockerFormat` | comment out `export BUILDAH_FORMAT=docker` in start.sh | FAIL: "start.sh does not contain an active `export BUILDAH_FORMAT=docker` directive" | PASS after restore |

The rehearsal of `TestLifecycleScriptsForceDockerFormat` initially
PASSED with the export commented out — because the substring-only
check fooled itself: `# DELETED: export BUILDAH_FORMAT=docker` still
matched. Tightened to a regex anchored to start-of-line with no
leading `#` comment marker. Re-rehearsed: FAIL with the comment,
PASS with the active export. The bluff rehearsal of a bluff-detection
test is itself a Sixth-Law-clause-2 datapoint: the rehearsal is the
only mechanism that distinguishes a real catch from one that hides.

### Real-environment verification (Sixth-Law clause 5)

```
$ podman ps --format 'table {{.Names}}\t{{.Status}}'
NAMES          STATUS
lava-postgres  Up 2 minutes (healthy)
lava-api-go    Up 2 minutes (healthy)

$ podman inspect lava-api-go --format '{{json .State.Healthcheck.Log}}'
[{"Start":"2026-04-29T13:27:14...","ExitCode":0,
  "Output":"…healthprobe: https://localhost:8443/health -> 200"}]

$ curl -sk https://localhost:8443/  → HTTP 200 via 2 (TLS)
$ curl -sk --http3-only https://localhost:8443/  → HTTP 200 via 3 (QUIC)
```

### Constitutional propagation

The verbatim user mandate recorded 2026-04-28 was already present in
several submodule constitutions from a sibling project's lineage
(ATMOSphere/HelixAgent → CONST-035 / §8.1+§11). This release adds
an explicit Lava-inheritance addendum to all 15 vasic-digital
submodule constitutions (CLAUDE.md + AGENTS.md across Auth, Cache,
Challenges, Concurrency, Config, Containers, Database, Discovery,
HTTP3, Mdns, Middleware, Observability, RateLimiter, Recovery,
Security) so the inheritance chain is unambiguous when each
submodule participates in a Lava feature. Submodule pins advance to
new SHAs on a `lava-pin/2026-04-29-sixth-law-anchor` branch on each
submodule's remote — Lava's pin discipline (frozen by default,
upgrade is a deliberate PR) is preserved.

### Test classification cleanup (`feature/menu`, `feature/connection`)

The 2026-04-29 audit flagged two ViewModel side-effect-only assertions
as suspected bluff Challenges. Action: clarified each test's KDoc to
honestly distinguish Integration Challenge Tests (primary assertion
on persisted/observable user-visible state) from ViewModel Contract
Tests (primary assertion on `SideEffect` emission only). Per-test
`// CHALLENGE` / `// VM-CONTRACT` comments added so a reader can
audit Sixth-Law compliance at a glance. The audit's "high-confidence
bluff" verdict on `discover_local_endpoints_not_found_shows_message`
reclassified to CHALLENGE — `state.discovering` IS user-visible UI
state (the loading indicator). The two genuinely VM-CONTRACT-only
tests retain their assertions but their KDoc now states explicitly
that the rendered-UI Challenge is owed (no `src/androidTest/`
infrastructure in this project yet).

### Sixth Law clause 5 takeaway

Three bugs (compose flag drift, podman-compose CMD-SHELL translation,
OCI HEALTHCHECK strip) all silently green in CI and `compose config`
yet broke the orchestrator's view of the container in three different
ways. Only sustained operation in real-environment verification
(actual podman recreation, actual `podman inspect .State.Healthcheck`
read) surfaced any of them. Real-environment verification catches
what synthetic verification cannot — for the third release in a row.

## [2.0.1] — 2026-04-29 (`Lava-API-Go-2.0.1-2001`, tag SHA `7d53367`)

### Fixed (production)

- **HTTP/2-over-TLS fallback now wired** (`000bb64`). Per spec §8.1 the public
  listener is HTTP/3 (UDP) + HTTP/2 (TCP) on the same port. Phases 1-13 only
  bound the UDP/HTTP-3 listener, so clients without HTTP/3 (k6 default
  transport, plain `curl`, browser fallback paths, older Android system
  HttpClient builds) couldn't reach 2.0.0 at all. Both transports share the
  same Gin engine; clients negotiate via Alt-Svc / SVCB.
- **response_cache schema fix** (`aa11566`). The Phase 4 migration created
  the table with the design-doc §7 7-column schema, but the
  `Submodules/Cache/pkg/postgres` library's INSERT is hardcoded to the
  3-column shape `(cache_key, value, expires_at)`. Every `cache.Set` call
  failed silently in 2.0.0; the cache layer was effectively a no-op. Net
  effect on the hot path: `GET /forum` cached_hits p(99) was **521ms**
  pre-fix → **49.9ms** post-fix (≈10× speedup) — measured via k6 against
  live rutracker.org.
- **windows-1251 → UTF-8 transcoding** (`660beff`). rutracker.org serves
  `text/html; charset=Windows-1251`; 2.0.0 passed bytes through to goquery
  unchanged, producing mojibake on every Russian field name. Now uses
  `golang.org/x/net/html/charset.NewReader` to transcode at the body-read
  boundary; binary endpoints (`.torrent` downloads) pass through verbatim.

### Fixed (parity with the legacy Ktor proxy)

- **Content-Type without `charset=utf-8` suffix** (`027737b`). Gin's
  `c.JSON` appends `; charset=utf-8` via its `MIMEJSON` constant; the
  legacy Ktor proxy emits plain `application/json`. New `writeJSON`
  helper emits the bare type; all 24 `c.JSON` call sites migrated.
- **Empty error-body shape** (`d60c599`). Ktor's StatusPages plugin
  responds `Unit` (which kotlinx-serialization writes as `{}`) on every
  non-200 path. Go side now matches (was `{"error":"..."}`).
- **`/search` empty-cookie 401 short-circuit** (`d60c599`). Kotlin's
  `GetSearchPageUseCase` wraps the call in `withTokenVerificationUseCase`
  which throws `Unauthorized` on empty token. Go side now matches.
- **JSON `null` for nullable nil pointer fields** (`70a6115`).
  oapi-codegen v2 emits `,omitempty` for `*T` fields, suppressing them
  when nil. kotlinx-serialization writes `null` for nullable nil. The
  `scripts/generate.sh` post-pass now strips `,omitempty` from struct
  tags so the wire bytes match.

### Security

- **otel SDK 1.42 → 1.43** (`21b2802`). Trivy HIGH finding
  CVE-2026-39883 ("opentelemetry-go: BSD kenv command not using absolute
  path enables PATH hijacking"). The vulnerability only affects BSD
  platforms; our deployment target is linux/amd64 distroless. Bumped to
  keep `ci.sh --strict` green going forward.

### Infrastructure

- **docker-compose.yml — fully-qualify non-aliased image short names**
  (`f5b8b34`). `docker.io/...` prefix on `prom/prometheus`,
  `grafana/promtail`, `swaggerapi/swagger-ui` so podman doesn't prompt
  for registry resolution in non-TTY contexts.
- **Metrics listener on `:9091` instead of `127.0.0.1:9091`** (`f5b8b34`).
  The bridged Prometheus scrapes `host.containers.internal:9091`; with
  `network_mode: host` the api-go's loopback isn't reachable from the
  bridge. LAN-deployment posture: the metrics port is held private by
  host firewall, not by binding to loopback.
- **k6 thresholds calibrated to live-upstream realities** (`c210cc2`).
  Original `p(99)<100ms` for health and `rate<0.01` for failures were
  aspirational. Calibrated to 300ms (health is by-design upstream-bound)
  and 0.20 (circuit-breaker trips under sustained load are correct).

### Documentation

- **Cross-backend parity baseline + post-polish records**
  (`8cdd84d`, `3593b3a`). Both runs against live rutracker.org via
  `./start.sh --both`; second run is 8/8 PASS.
- **k6 load-quick evidence record** (`c210cc2`).
- **Design-doc Appendix A.1 + A.2 update** (`bc70ed9`). Records the
  full 2.0.1 commit set and the two production-grade Sixth-Law-clause-5
  catches.

### Cross-backend parity gate

8/8 fixtures PASS post-fix. Three plan-mandated falsifiability rehearsals
recorded: corrupt body (live mutation, 6/8 fixtures fail with the
predicted assertion), reorder JSON keys (comparator unit test), drop
header (comparator unit test). Evidence at
`.lava-ci-evidence/parity/2026-04-29_post-polish_clean.md`.

### Sixth Law clause 5 takeaway

The HTTP/2 fallback gap and the cache-schema bug were both invisible
to `ci.sh`, the contract tests, the e2e suite, and the Phase 14.1 pretag-verify
single-request probe. Only sustained load against the real stack
surfaced both. Real-environment verification catches what synthetic
verification cannot.

## [2.0.0] — 2026-04-29 (`Lava-API-Go-2.0.0-2000`, tag SHA `530e649`)

Initial release. SP-2 implementation of the Go API service replacing the
legacy Kotlin/Ktor proxy.

### Added

- **13 routes implemented in Go/Gin** with HTTP/3 (QUIC) primary
  transport on `:8443`. Routes: `GET /`, `/index`, `/forum`,
  `/forum/{id}`, `/search`, `/topic/{id}`, `/topic2/{id}`,
  `/comments/{id}`, `/torrent/{id}`, `/download/{id}`, `/captcha/{path}`,
  `/favorites`; `POST /login`, `/comments/{id}/add`,
  `/favorites/add/{id}`, `/favorites/remove/{id}`. Behavioural parity
  with the legacy Ktor proxy.
- **OpenAPI 3.1 spec** at `api/openapi.yaml`, hand-authored from the
  Ktor proxy's 16 route handlers and `@Serializable` DTOs. Source of
  truth for the cross-backend parity test.
- **`oapi-codegen` v2** generates Gin server interfaces and a typed Go
  client; both committed at `internal/gen/`. CI enforces "regenerate
  produces empty diff".
- **Postgres response cache** (5-minute to 1-hour TTLs per route),
  realm-scoped invalidation on writes. Backed by
  `Submodules/Cache/pkg/postgres`.
- **mDNS service advertisement** on `_lava-api._tcp` port 8443 with
  TXT records `engine=go, version, protocols=h3,h2,
  compression=br,gzip, tls=required, path=/`.
- **Auth pass-through model** (A2 + H1 per spec §9): the `Auth-Token`
  header is forwarded as the upstream cookie verbatim, and SHA-256 hashed
  into `auth_realm_hash` for audit / cache-key isolation. Plaintext
  token never persisted, never logged.
- **Sliding-window rate limit** per (client_ip, route_class) backed by
  `Submodules/RateLimiter`. Default thresholds: read 60 rpm, write 10 rpm,
  login 5 rpm, download 10 rpm.
- **Observability stack** (`./start.sh --with-observability`): Prometheus
  scrapes `/metrics`, Loki + Promtail aggregate logs, Tempo collects
  OpenTelemetry traces, Grafana auto-provisions a 4-panel dashboard
  (request rate, p99 latency, cache outcomes, rutracker upstream errors).
- **Cross-backend parity test framework** (`tests/parity/`) — the
  Sixth-Law-clause-4 load-bearing acceptance gate. Compares Go API
  responses against the legacy Ktor proxy byte-for-byte.
- **k6 load tests** (`tests/load/k6-quick.js` + `k6-soak.js`) and the
  wrapper `scripts/load-quick.sh`.
- **Local-only CI gate** (`scripts/ci.sh`) integrating gosec,
  govulncheck, trivy, fuzz, lint, build, test, image-scan. No hosted CI.
- **Pretag verification** (`scripts/pretag-verify.sh`) — scripted
  black-box runner producing `.lava-ci-evidence/<commit>.json` evidence
  files. `scripts/tag.sh` refuses to operate without one.
- **TLS auto-generation** (`scripts/gen-cert.sh`) — wired into
  `start.sh`, RSA-2048 self-signed cert with LAN-IP SAN.

### Notes

The legacy Ktor proxy is preserved as an opt-in fallback via
`./start.sh --legacy` or `--both`. Never removed. SP-3 (Android
dual-backend support) is the next sub-project.

The constitutional rules — Sixth Law (Anti-Bluff Testing), Local-Only
CI/CD, Decoupled Reusable Architecture, Host Machine Stability — are
enforced project-wide and inherited by 15 `vasic-digital` submodules.
