# Lava — Work Continuation Index

**Purpose:** this single file is the source-of-truth for resuming the
project's work across any CLI session. A fresh agent reads this file
first, locates the active phase, and continues from there. Everything
ahead of HEAD is recorded; everything behind HEAD is in `git log`.

**Maintenance:** every release tag, every phase completion, every
operator directive that changes scope MUST update this file in the
same commit so the index stays trustworthy. Stale state in this file
is itself a §6.J spirit issue — the file claims a guarantee, the
repo has drifted, the agent acts on the claim.

> **Last updated:** 2026-06-09 (**operator decisions logged + single-stream resume**). Operator chose (2026-06-09): **LVA-008 = accept-with-§6.AC-telemetry + distribute**, and **single-stream OWED work** (parallel fleet was hitting server-side rate limits). 
> - **LVA-008 INSTRUMENTED + ACCEPTED (commit `06f599b5`, LVA-073 Completed):** `NavTeardownCrashReporter` (chained uncaught-exception handler, wired into `LavaApplication.onCreate`) tags the known upstream androidx nav-teardown ISE with attributable §6.AC Crashlytics context (`feature=navigation`, `operation=activity-teardown`, `screen=search_input`, `lva_id=LVA-008`, custom key `lva_008_known_upstream_nav_teardown`) BEFORE the process dies — it instruments, never swallows. 6 falsifiable JVM tests (detection+tagging logic is pure JVM, no device). **Distribute now unblocked at the code level**; the actual §6.Z two-stage Firebase re-distribute still needs: the device Challenge gate (BLOCKED on this macOS host per §6.X-debt — no in-container emulator/HVF; needs a Linux x86_64+KVM gate host OR the Genymotion VM), a §6.Y version bump, and operator real-device verification per §6.Z. LVA-008 → In progress (the upstream crash itself remains; it is accepted+instrumented, not fixed).
> - **Rate-limit note:** the wave-10 parallel agents were server-rate-limited mid-task ("not your usage limit"); one produced unverified partial Go telemetry work whose falsifiability rehearsal was inconclusive + SourceHash failed → DISCARDED (not integrated), queued honestly as LVA-072. Proceeding single-stream.
> - **OWED single-stream queue:** LVA-070 (favorite/visited providerId write+read threading — LVA-067 laid the Room column), LVA-071 (inject SseClient for hermetic testing), LVA-072 (cert-rotation §6.AC telemetry, redone properly).
>
> **Last updated (prior):** 2026-06-09 (**autonomous parallel-fleet loop — 3 waves of 6 subagents, ~25 commits past `54eedd92`; pushed + converged github+gitlab**). Each wave: 6 parallel agents (Kotlin in worktree isolation, Go/docs/config shared tree) + main-stream independent re-verification of every claim (no blind trust). All work falsifiability-proven with verbatim captured evidence. **check-constitution.sh fully GREEN (EXIT 0)** after this loop's §6.R fixes. Constitution pin `60e2d66`. **19 LVA items closed this loop** (LVA-6, 25, 26, 28, 29, 30–35, 37, 38–43, 45) — 11 of them REAL shipped bugs.
> - **Wave 9 — TLS zero-downtime rotation + Room providerId migration + SSE/proxy coverage:** LVA-068 (embed TLS cert now ROTATES mid-process via a `GetCertificate` callback + atomic `rotatingCert` holder — re-mints within the expiry margin, same IP-SANs, zero dropped connections; `590dd710`). LVA-067 IN PROGRESS (Room v11→v12 `MIGRATION_11_12` adds a nullable `providerId` column to favorite/visited rows + schema `12.json` + converters + a real-SQLite non-destructive migration test 3/0/0; `badca5f5`) — the persistence column is laid, but threading the source `providerId` through the write path (`ToggleFavoriteUseCase`/`GetTopicUseCase` + `Topic`/`TopicPage` models) + read path (`TopicModel` → `openTopic(id,providerId)`) is honestly OWED → LVA-070, so favorites/visited HTTP_DOWNLOAD still falls back to the active tracker for now (no data loss, no upgrade crash). LVA-069 (SearchResultViewModel SSE raw-JSON parsing 10 tests + onRetryClick 2 tests + malformed-JSON hardening; CLEAN, `e1c983ae`). DelegatingProxySelector coverage 6/0 (`e01e504b`). New queued: LVA-070 (favorite/visited providerId threading), LVA-071 (inject SseClient for hermetic testing). **Loop status:** the high-value autonomous surface is now largely swept — recent waves trend CLEAN + coverage + §6.N hunts finding 0 bluffs. The next milestone (Firebase re-distribute) is operator-gated on LVA-008 (nav-teardown accept-vs-keep-RED) + LVA-5 (token rotation).
> - **Wave 8 + Jackett — LVA-052 topic-tap COMPLETED + TLS expiry + coverage + Jackett validated:** **LVA-052 HTTP_DOWNLOAD is now USER-REACHABLE** from the topic screen (commit `831e7be9`): a `ProviderCapabilitySource` seam + `ResolveProviderDownloadKindUseCase` + a topic-nav `providerId` arg (defaults to the active tracker, back-compat) + `TopicViewModel` HTTP-vs-`.torrent` branch + search_result threading the real `providerId`; real-stack + no-regression tests falsifiability-proven (favorites/visited need a Room providerId column → LVA-067, honestly OWED). LVA-064 (TLS embed cert regenerated when the persisted leaf is EXPIRED — injectable clock seam, `21d8fc23`). LVA-065 (SearchViewModel bug-hunt CLEAN + 3 real coverage gaps: PagingDataLoader prepend, append-error retry, sign-out privacy reduction, `9f634e16`). §6.N wave-8 bluff-hunt: 5/5 SAFE-module tests GENUINE, 0 bluffs (`2507e427`). **Jackett (operator-flagged new release):** validated **v0.24.2040-ls426** (digest `sha256:bae2fdf0...`) boots in the loopback topology, bootstraps its api_key, and serves valid Torznab `<caps>` at the exact `IPTorrentsJackettApi` endpoint — real §6.B probe, parser tests green, validated digest pinned in `.env.example` (`04e5156a`/LVA-066); full real-creds IPTorrents+FlareSolverr e2e operator-gated. New queued: LVA-067/068/069. **Worktree stale-base fix:** instruct worktree agents to `git reset --hard origin/master` first (the isolation branches from session-start HEAD, which lacked this loop's commits — that blocked LVA-052 in wave 7).
> - **Wave 7 — 2 Go fixes + gate-coverage hardening + credential coverage; LVA-052 topic-tap honestly OWED:** LVA-059 (deterministic multi-search SSE provider order via `IDs()` sort, `0a1881cd`), LVA-061 (TLS embed cert regenerated when persisted IP-SANs no longer cover the device LAN IP, `dc6c1179`), LVA-062 (gate-script clause-coverage: created the missing IPv4 scanner hermetic test + `.db`-exemption sub-tests in all 3 §6.R scanners + seqcoll comment-strip branch — all falsifiability-proven, `b5302590`), LVA-063 (+9 falsifiable CredentialsViewModel/CredentialsManager tests, `b51f040a`+`9280d4ab`). **LVA-052 topic-tap remains OWED** — worktree-isolated agents branch from the session-start base (`54eedd92`), so they lacked master's LVA-044/052 SDK layer; the agent correctly refused to ship half-wired dead code. Completing it needs a provider-id threaded through the topic route + 4 openTopic call sites + a favorites/visited Room column (multi-call-site + migration) — do it on the main tree or reset the worktree to master first. New queued: LVA-064 (TLS cert expiry check), LVA-065 (SearchViewModel coverage audit). app/deep-link/nav/connection/rating audit CLEAN.
> - **Wave 6 (+ recovery) — 4 MORE real bugs + LVA-052 cleanly re-applied + 2 §6.R scanner gate fixes:** LVA-054 (P1 — NavigationController `topLevelBackStack.removeLast()` back-stack-pop crash on Android <API35, same SequencedCollection class as LVA-053; fixed + a `scan-no-removelast-seqcoll.sh` guard with comment-stripping wired into check-constitution + 5/5 hermetic test, `d99b10ad`), LVA-057 (multi-search SSE silently dropped unknown requested providers — now emits `provider_error` + counts failed, `e325d9a0`), LVA-060 (P1 — favorite/visited magnet-only torrents reconstructed as un-downloadable BaseTopic; date+magnetLink added to the discriminator, `af160a35`), LVA-058 (§6.R ipv4/hostport/uuid scanners flagged the binary `workable_items.db` SSoT — `.db` exemption added, `3d9eb469`), LVA-056 (IPv4 scanner `tests/` exemption — landed post-FS-recovery, `453fe2b6`), LVA-052 IMPLEMENTED cleanly on master's LVA-044 (ViewModel→UseCase→SDK→disk real-stack proven; topic-tap provider-id nav threading OWED, `9d9d2bd5`+`c2b03749` fake fix), LVA-055 (run-test-pg.sh now runs internal/storage Postgres legs). §6.N.2 production bluff-hunt closed a real CM-WORKABLE-ITEMS-SYNC clause-3 coverage gap (`2883a136`). New queued: LVA-059 (deterministic SSE ordering), LVA-061 (tls IP-SAN cert), LVA-062 (gate-clause coverage audit). **Recovery note:** mid-loop the T7 volume hit a macOS TCC access denial (process-scoped, fixed by app relaunch + Full Disk Access) — zero work lost, everything was committed+pushed per wave. Agent 5 (coverage) hit an account session limit (no result).
> - **Wave 5 — 1 MORE real crash bug + 3 queued cleared + real-Postgres coverage:** LVA-053 (P1 — `PostConverters.removeLast()` desugars to `java.util.List.removeLast` (JDK21 SequencedCollection) absent on Android <API35 → topic-screen `NoSuchMethodError` crash on a `PostBr`+`Hr` post; fixed `removeAt(lastIndex)` + 28 converter tests, `608c3bf9`), LVA-048 (search_input wrote author NAME under AuthorIdKey → name lost, `50de44c1`), LVA-049 (route values not URL-encoded → reserved chars corrupt the route; `Uri.encode` per-value, same commit), LVA-050 (A11y test red on the RELEASE variant — missing ComponentActivity host because `ui-test-manifest` was debugApi-scoped; added testImplementation, green both variants, assertions intact, `96b7255a`). lava-api-go real-Postgres-in-podman integration coverage: `internal/cache` 65.7→97.1%, `internal/storage` 76.4→80.9% (real pgx, container torn down, `c9fdb661`). New queued: **LVA-054** (sweep all `removeLast/removeFirst/getFirst/getLast` SequencedCollection calls — LVA-053 class, may be MORE crashes), **LVA-055** (run-test-pg.sh omits internal/storage). **LVA-052 deferred** (a full impl exists on branch `worktree-agent-ad695086c8735d60a` but re-derived LVA-044 → conflicts; needs clean re-apply on master's LVA-044 + topic-nav provider-id threading; one wave-5 agent died on a socket error — production bluff-hunt not run, re-queue).
> - **Wave 4 — 2 MORE real bugs + LVA-044 feature + §11.4.128 skeleton + clean §6.Q audit + 5/5-genuine bluff-hunt:** LVA-046 (rutracker Login reported FAKE success on wrong credentials — blind `AsAuthResponseDtoSuccess()` union accessor ignored the discriminator, non-pointer `UserDto` decoded WrongCredits with no error → `Success:true AuthToken:''` → every authed call 401s; LVA-032 class; fixed via checked accessor, `2c310607`), LVA-047 (MagnetLinkValidator rejected case-insensitive `urn:btih:` prefix per RFC 8141, `458d0960`), LVA-044 IMPLEMENTED (HTTP_DOWNLOAD capability + `HttpDownloadableTracker`, archiveorg/gutenberg wired, §6.E fwd+rev gate green, real-stack MockWebServer tests — **SDK-reachable; UI consumption OWED → LVA-052**, `badbca92`), LVA-051 (§11.4.128 device-recorder skeleton + deterministic-path hermetic test; device capture OWED — no live device, `507c34c6`). §6.Q + ui/designsystem/navigation audit CLEAN (7 verticalScroll Composables all safe, theme/nav correct). §6.N bluff-hunt: 5/5 production-mutated tests GENUINE, 0 bluffs (`1af5193b`). New queued from wave-4 §6.Q agent's out-of-scope finds: **LVA-048** (search_input writes author NAME under AuthorIdKey — real nav bug), **LVA-049** (route values not URL-encoded), **LVA-050** (A11yContentDescriptionTest teardown RuntimeException), **LVA-052** (HTTP_DOWNLOAD UI wiring).
> - **Wave 3 — 5 MORE real shipped bugs + §6.E reverse-gate + §6.AI-debt:** LVA-038 (brotli middleware emitted `Content-Encoding: br` on bodyless 204/304; `statusAllowsBody` was dead code, `f3b2d269`), LVA-039 (ProviderLogin captcha-retry left stale `serviceUnavailable` banner — banner-sweep gap, `9f895502`), LVA-040 (onboarding anonymous-mode choice never persisted to `provider_configs` → Provider Config shows OFF + search treats provider as credentialed, `8e0720ab`), LVA-041 (`SyncPeriod.DAY` background-sync flex `6.days` should be `6.hours` → WorkManager silently clamps flex to full period, `0cecbe4e`), LVA-042 (SyncOutbox FIFO Third-Law fake divergence + createdAt coverage, `a11aee32`), LVA-043 (§6.E reverse-gate: phantom-capability — exposing an undeclared feature shipped green; now caught, `c117caeb`), LVA-045 (§6.AI-debt: CM-COVENANT-114-* §11.4.128–141 propagation gate in check-constitution + slash-command docs, `c37995a7`). New queued: **LVA-044** (Feature — add `HTTP_DOWNLOAD` capability so archiveorg/gutenberg can surface their working-but-unreachable HTTP download; not a bluff, a functionality gap).
> - **Wave 1 — 5 tickets closed:** LVA-025/026 (Go v1 captcha: dynamic `cap_code_<sid>` field name + propagate upstream Content-Type, `aca3e720`), LVA-028 (nnmclub search publishDate dropped, `a31efce8`), LVA-029 (isLocalHost fc/fd ULA false-positive on public hosts e.g. `fcbarcelona.com`, `4e687769`), §6.AI-debt LAYER-2 (§11.4.140 action-prefix UserPromptSubmit hook → `constitution/scripts/hooks/action_prefix_expand.sh`, hermetic test 8/8, `db69d797`), LVA-6 (codegraph own-org-submodule indexing — verified genuinely done: 4460 submodule files indexed + §6.H 0-leak, `22516444`).
> - **Wave 2 — 3 MORE real shipped bugs + LVA-030 durable:** LVA-032 (rutracker browse/favorites blind-cast Topic→Torrent: `AsForumTopicDtoTorrent` ignored the union discriminator → empty fake-torrent rows; fixed via the never-wired `AsForumTopicDtoTorrentChecked`; +gutenberg cov 82.8→89%, `fab05f2e`), LVA-033 (rutor topic page dropped `Добавлен` publishDate, `45794521`), LVA-034 (Room endpoint-list converter dropped GoApi `key` → list-selected on-device Lava-API endpoint 401s; `Lava-Auth` override dead; fixed via #-sentinel encoded packing, no migration, `488185ed`), LVA-031 (genymotion VM+nav UUID §6.R redaction per e767b701, `6687e23b`), LVA-035 (PagingDataLoader coverage, `a3f7fb83`), LVA-030 (6-submodule §6.R/§6.S/§6.X/§6.AD pointer-blocks committed in-submodule + pushed; 5 pins bumped `7c9843c8`).
> - **Audited CLEAN (no padding):** core/domain + feature/{search,search_input,search_result,category,topic,forum} ViewModels (genuine anti-bluff tests, no Second/Third-Law bluffs); rutor/kinozal/archiveorg/gutenberg search/browse parsers; core/network resolvers + LocalNetworkDiscovery + preferences. Latent gaps flagged (not faked): kinozal topic parser drops fields but the committed fixture is a synthetic stub (needs a real captured `details.php` fixture before a non-bluff fix); CategorySelectionViewModel child-deselect leaves parent group fully-selected (UX, needs product decision).
> - **▶ NEW OPERATOR-GATED ITEM LVA-036:** `llm_orchestrator` github↔gitlab have a PRE-EXISTING non-FF mirror fork (diverged at `d2a2151`, unique non-doc go.mod content each side). LVA-030's pointer commit `a98ae84` landed on gitlab + the working tree (gate passes) but github refused a non-FF push — NOT force-pushed (§6.T.3). Needs an operator content-merge decision. llm_orchestrator parent pin therefore held at `a484f7d` (not bumped).
> - **▶ STILL OPERATOR-GATED:** LVA-008 (C11/C06 nav-teardown — upstream androidx defect, 5 avenues device-falsified; accept-with-§6.AC-telemetry+distribute OR keep-RED) + LVA-5 (rotate Firebase CI token, §6.H).
>
> **Last updated (prior):** 2026-06-09 (**keep-building loop — LVA-013 deeper Third-Law fix closed**). Operator directive "keep-building now" (no distribute; LVA-008 stays DEFERRED awaiting the accept-vs-keep-RED decision). Subagent fleet was server-rate-limited, so this is a solo main-stream slice.
> - **LVA-013 CLOSED (Completed → Fixed.md)** — Third-Law (behavioural-equivalence) bluff in `core/testing` `TestEndpointsRepository.observeAll()`: the fake SEEDED + EMITTED `[Endpoint.Rutracker]` on first observe while the real `EndpointsRepositoryImpl.observeAll()` seeds nothing (`defaultEndpoints` is `emptyList()`) and `.filterNot { it is Endpoint.Rutracker }`s every emission — so production NEVER lists Rutracker (fresh-install first observe = `[]`). Fixed the fake to match (no seed + emission-level filter) AND rewrote **4 phantom-asserting test methods** across 3 files to the real contract: `TestEndpointsRepositoryEquivalenceTest` (×2 — part-3 of the add-no-op test + the renamed `fake_observeAll_never_emits_rutracker_like_real_impl`), `TestInfrastructureContractTest.observe on empty repo emits empty list and never Rutracker`, `DiscoverLocalEndpointsUseCaseTest.discovery adds a new mirror and Rutracker is never listed`. **Bluff-Audit:** mutation reintroducing the onStart seed → core:testing `expected:<[]> but was:<[Rutracker]>` (2 methods) + core:domain `Fresh-install first observe MUST emit []... Got: [Rutracker]`; reverted. GREEN after revert: **core:testing 24/0, core:domain 57/0, feature:connection 10/0**. Ledger gate `CM-WORKABLE-ITEMS-SYNC` OK (13 items, DB↔MD in sync).
> - **LVA-014 + LVA-015 CLOSED (Completed → Fixed.md)** — two MORE Third-Law fake bluffs surfaced by a read-only parallel-agent audit of every `core/testing` fake (the throttle cleared): **LVA-014** `TestSuggestsRepository` was a TODO-throw stub (LVA-012 class) → implemented in-memory (case-insensitive UPSERT `id=lowercase().hashCode()`, newest-first, clear) + `TestSuggestsRepositoryTest` (4 tests). **LVA-015** `TestSearchHistoryRepository.add` used a positional id + always-append + insertion order while the real `SearchHistoryRepositoryImpl` uses content-derived `Filter.id()` + `@Insert REPLACE` UPSERT + `ORDER BY timestamp DESC`; the existing `TestSearchHistoryRepositoryTest` asserted the fake-shape (ids `[0,2]`, oldest-first) — a bluff test. Fixed the fake (replicates `Filter.id()` w/ source-of-truth comment, UPSERT, newest-first) + rewrote the test to the real contract (6 tests, incl. sort-only dedup). Both Bluff-Audited (mutation → `expected:<1> but was:<2>`, reverted). GREEN: core:testing 31/0, feature:menu 17/0, feature:search 9/0. (Audit also confirmed TestSettings/Auth/LocalNetworkDiscovery/Endpoints/Visited/Favorites/Bookmarks fakes are genuinely equivalent — no padding.)
> - **lava-api-go coverage (commit 4775d0c1):** added falsifiable tests for two real 0%-coverage gaps a parallel agent found — `internal/router` Jackett route registration + enabled/disabled/unconfigured discriminators + /health-open-under-full-auth-chain (4 tests); `internal/server` brotli `WriteString` fast-path + `statusAllowsBody` RFC truth table (3 tests). All 4 mutations falsified + reverted; gofmt/vet clean; tests/contract source-hash unaffected (test files excluded from embed manifest).
> - **Two more parallel read-only audits (throttle clear) found 3 tracked items:** **LVA-016** (Bug, ACTIVE bluff) — `RatingViewModelTest`'s local `RealObserveRatingRequestUseCase` re-implements the use case but DROPS the engagement gate, so the "Show dialog" test asserts Show with zero engagement while the real `ObserveRatingRequestUseCaseImpl` would Hide (Second-Law/§6.J). Proper fix wires the real use case (deep tree via shared fakes, now usable post-LVA-012/015) — deferred to a focused cycle, NOT rushed. **LVA-017** (LATENT) feature favorites/topic local fakes' `add()` lacks REPLACE dedup (unexercised). **LVA-018** core/preferences dead getters `getHistorySyncPeriod/getCredentialsSyncPeriod` (zero call sites). The dead-code audit otherwise came back CLEAN (0 shipped `TODO()`, 0 `if(false)`, 0 silent-no-op effect methods).
> - **LVA-016 FIXED — the canonical "green test, broken feature" bluff.** `RatingViewModelTest` re-implemented `ObserveRatingRequestUseCase` but DROPPED the engagement gate, so the "Show dialog" test passed with zero engagement while the real `ObserveRatingRequestUseCaseImpl` would Hide. Fixed: made the impl public (Fifth Law) + wired the REAL use case over the LVA-012/015-fixed shared fakes (ObserveSearchHistory/Visited[→EnrichTopics]/Bookmarks) + seeded real engagement (3 bookmarks) in the 4 Show-path tests. Bluff-Audit: neutralize engagement → those 4 tests FAIL (`TurbineAssertionError: No value produced in 3s`), 2 Hide tests pass; reverted. GREEN feature:rating 6/0, core:domain 57/0.
> - **5-subagent parallel fleet (throttle clear) — ALL verified by the main stream + closed:** **LVA-7** chaos §11.4.85 (Agent A) — `search_thundering_herd_test.go`: 512 concurrent identical-key reqs through the REAL read-through cache, asserts all-200 + post-burst upstream-loads=0 (convergence) + no-leak + `-race`; Bluff-Audit delete `cache.Set`→convergence FAILED. **LVA-019** (Agent B, REAL production bug) — `nnmclub.IsAuthorised` used unquoted CSS attr selectors `a[href*=profile.php]` → cascadia matched NOTHING → dead auth-fallback; fixed to quoted form + test (IsAuthorised 77.8→88.9%); api-source.hash regenerated, sourcehash contract GREEN. Plus kinozal parser edge tests. **LVA-017** (Agent D edits) — feature favorites/topic local fakes `add()` now dedup-by-id (REPLACE) + 2 falsifiability tests (`expected:<[7]> but was:<[7,7]>`). **LVA-018** (Agent C) — core/preferences dead getters removed (4 files). **Agent E** anti-bluff audit of core/network+core/data+un-audited features → ZERO bluffs (clean). Every agent result was re-verified + falsifiability-rehearsed by the main stream before commit (no agent claim trusted blindly).
> - **Second + third parallel fleets (8 bug-hunt agents total) → FIVE more REAL shipped Go bugs found + fixed, all main-stream-verified + falsifiability-rehearsed:** **LVA-020** archiveorg array-valued creator/title/year/date failed the WHOLE search/browse/topic response (flexString tolerant type); **LVA-021** observability `error_class` collapsed to literal "error" for every real typed error → §6.AC telemetry blindness (`%T` fallback); **LVA-022** gutenberg blank format label for charset-suffixed Gutendex MIME keys (prefix-match); **LVA-023** v1 login returned `User.Id` (data-uid) not `User.Token` (cookie) → every authed v1 call 401s (+ first e2e login test); **LVA-024** v1 GetTopic fabricated a bogus `TopicFile{Name:"Size"}` (dropped synthetic file + fixed the bluff test that asserted it). Plus Agent L added 4 falsifiability-proven coverage files (rutracker→90.1%, auth→95%, config→94.9%, middleware→100%). api-source.hash regenerated; sourcehash contract GREEN; go build/vet clean.
> - **Deferred/queued from the fleets:** LVA-025 (v1 captcha answer sent under wrong form-field name — needs LoginOpts/OpenAPI model change), LVA-026 (v1 captcha Content-Type hardcoded image/png). Kotlin tracker bugs still to fix on the main stream: **G1** Kinozal `sizeBytes` hardcoded null (every row), **G2** Nnmclub `publishDate` dropped, **J1** `isLocalHost()` fc/fd false-positive mis-routing. Audit also surfaced latent/conditional items (J2-J6 network, orphaned CredentialsScreen, LavaIcons.AppIcon foot-gun) — to triage. Agents K (core/domain) + the Compose audit came back CLEAN.
> - **CONSTITUTION PIN BUMPED `7734c04` → `60e2d66`** (1.2.0-dev, 16 commits) per operator directive 2026-06-09. New UNIVERSAL clauses §11.4.128–§11.4.141 adopted via new **§6.AI** clause (the ATMosphere audio batch §11.4.135-139 is project-specific, NOT binding on Lava). Mappings: §11.4.131 session-resumption-file ≡ `docs/CONTINUATION.md` (§6.S, this file — the declared canonical path); §11.4.134 ≡ `/code-review ultra`; §11.4.132 risk-ordered-validation + §11.4.129/130 huge-blocker/validate-fix-first release discipline ADOPTED; §11.4.140 action-prefix system LAYER-1 wired into root CLAUDE.md (§6.AJ) + AGENTS.md + QWEN.md (registry `constitution/actions/registry.yaml`); §11.4.128 device-recording + §11.4.141 token-efficiency thin-index + §11.4.140 LAYER-2 hook = OWED via **§6.AI-debt**. No new mandated submodule. **Known pre-existing debt surfaced (LVA-030):** 6 recently-added submodules (doc_processor/helixqa/llm_orchestrator/llm_provider/llms_verifier/vision_engine) lack the §6.R inheritance pointer → full `check-constitution.sh` exits 1 (changed-only pre-push still passes; orthogonal to the bump).
> - **G1/G2/J1 (Kotlin tracker/network bugs):** LVA-027 Kinozal `sizeBytes` hardcoded null FIXED (KinozalSizeParser + end-to-end test, falsifiability-proven). Still Queued: LVA-028 Nnmclub `publishDate` drop, LVA-029 `isLocalHost()` fc/fd false-positive.
> - **Ledger state:** 30 items — 20 closed (11 Fixed + 9 Completed: LVA-009..024, LVA-027, LVA-7). Queued: LVA-6 codegraph-index, LVA-008 C11 (DEFERRED operator-gated), LVA-025/026 (v1 captcha), LVA-028/029 (Kotlin tracker/net), LVA-030 (6-submodule §6.R). LVA-5 operator-blocked (§6.H). LVA-3/4 in-progress. CM-WORKABLE-ITEMS-SYNC OK.
>
> **Last updated (prior):** 2026-06-08 LATE-EVENING (**autonomous parallel-fleet QA session #2 — ~13 commits on `master` past `207adf73`; HEAD ~`21106e26`+LVA**). Ran 1 device-bound main stream + 13 non-device subagents across 5 waves (each on a distinct gitdir/toolchain → zero Android-Gradle/device/parent-index contention). Every PASS carries verbatim captured output; multiple §6.J findings surfaced + handled honestly.
> - **🎯 HelixQA autonomous vision QA NOW WORKING** (the operator's core goal — replace manual testing with AI vision QA): fixed a 3-layer claude-CLI bridge incompatibility in the helixqa submodule (`42f9998`→`aef46e5d`, pushed both mirrors) — (1) `--json`→`--output-format json`, (2) `--image`→in-prompt screenshot path read via Claude Code's Read tool (probe-proven), (3) prompt as `--print`'s value before the variadic `--allowedTools`. On the Genymotion VM the vision backend now GENUINELY analyzes Lava screens + decides actions (`vision-screen-changed PASS`; real rationale "Current screen is the Android home launcher … launching the Lava client app"). Remaining: a launch-dispatch `exit status 127` (**LVA-009**, tracked). Evidence: `.lava-ci-evidence/helixqa/vm-qa-vision2-20260608T182251Z/`. (Each prior bridge layer was a real bluff-or-incompat caught honestly: the agent also found+fixed 2 helixqa bluff tests that asserted the broken `--json`/`--image` flags.)
> - **Jackett sidecar LIVE-validated** (real podman bring-up): Jackett Torznab caps HTTP 200 + XML + FlareSolverr `status:ok`; fixed a real `wget`→`curl` healthcheck bug. **§11.4.85 stress/chaos PASS** (6 dims, falsifiability-proven). **lava-api-go**: golangci-lint **0 issues** (first real run — podman /Volumes mount was broken), coverage raised jackett 90→95% / middleware 76→98% / nnmclub 74→77% (4 new test files, 2 rehearsals). **+17 tracker parser edge-case tests** (rutor/nnmclub/kinozal/archiveorg/gutenberg, all Bluff-Audited).
> - **Pushed + converged (all 4 mirrors at `ba04ef2a`, then 2 more commits ahead):** `5b7ba824` **lava-api-go real failure fixed** — `api-source.hash` left stale by `7ce9c2b2` (embed-source drift); `TestSourceHash_ManifestMatchesLive` RED→GREEN, full `go test ./...` exit 0 (39 pkgs, real Postgres-in-podman e2e). `1a050e16` **§6.R host:port scanner comment-stripping** (false-positives on comment/example URLs fixed; hermetic test added; real literal still caught — falsifiability-proven) + **`run-genymotion-challenges.sh --evidence-dir` absolutized**. `5bc65d66` **submodule pins bumped**: helixqa→`42f9998` (lava-pin branch pushed to GitHub+GitLab — §6.W satisfied) + 5 dep `helix-deps.yaml` (doc_processor→`e446da7b`, llm_orchestrator→`a484f7dd`, llm_provider→`6776eb4b`, llms_verifier→`44b515da`, vision_engine→`5cf7fe7f`; all pushed vasic-digital GitHub+GitLab). `b0627107` stress-chaos jackett evidence refresh. `ba04ef2a` **§11.4.29 snake_case rename PLAN** (operator-gated; surfaced latent `llm_orchestrator` go.mod case bug). `2fe55126` **Jackett+FlareSolverr Torznab sidecar deploy-ready** (compose overlay + one-command validate script + docs) + **C11 device-debug forensics**. `3801c356` **IPTorrents §6.E magnet cold-cache test (JVM 3/3, Bluff-Audited) + §6.G C38 Challenge** (7th-provider coverage; fail-closed skip).
> - **§11.4.85 stress/chaos suite PASS** (real run): 6 dims (sustained-load 500req/0err, 64-way contention, fault-recovery, latency-injection, malformed-input, rate-limiter-trip) all PASS + falsifiability-proven; 2 Postgres dims honestly OPERATOR_GATED.
> - **C11 nav-teardown crash — 4 device cycles, STILL OPEN/RED (no bluffed green):** hypotheses **all FALSIFIED on the Genymotion Pixel 9 VM** — (1) nav-compose 2.9.1→**2.9.8** (latest stable) does NOT fix it; (1b) **no `LenientTeardownRule` can catch it** — the ISE kills the PROCESS in `performDestroyActivity` via `NavControllerImpl`'s host ON_DESTROY observer, outside the JUnit statement chain; (2) **atomic-replace navigation** (`navigateReplacingCurrent` popUpTo) does NOT fix it (search_input still INITIALIZED at destroy). All speculative changes REVERTED (§6.T.1 — no unverified fix shipped). The feature WORKS (real archive.org result row renders pre-teardown); it's a deep androidx nested-NavHost teardown bug. Full forensics in the incident JSON's `systematic_debug_2026_06_08`. **DEFERRED** to a future nav-internals session or upstream androidx fix.
> - **▶ OPERATOR DECISION 2026-06-09: close gates first, THEN distribute** (no Firebase distribute until the device Challenge suite is green against a fresh build + LVA-008 resolved + §6.Y bump; the proper §6.Z/§6.AA two-stage debug→release flow after). Firebase creds present.
> - **▶ LVA-008 (C11+C06 nav-teardown) = UPSTREAM androidx defect — 5 avenues exhausted, DEFERRED.** Device-falsified (all reverted, §6.T.1): nav-version 2.9.1→2.9.8, LenientTeardownRule (process-death, uncatchable), nested-host move (Candidate 2 — crash moved to the OUTER search_input, disproving the nested-host theory), atomic popUpTo replace, AND the operator-approved deep custom-NavHost guard (force-advance INITIALIZED currentBackStack entries on ON_STOP — the phantom entry isn't in the public currentBackStack; it's in NavController internals, reflection-only). Core-flow device gate 2026-06-09 = **4/5 GREEN** (C00/C01/C07/C08 PASS with all this session's new code) — only C06+C11 fail on this one bug. Real user-impact: search-then-rotate/config-change crash. **AWAITING OPERATOR DECISION:** accept-with-§6.AC-telemetry + distribute, OR keep-RED until an upstream androidx fix. Full forensics: incident `systematic_debug_2026_06_08` + the 2026-06-09 updates. Distribute (operator chose gate-first) is blocked on this.
> - **DONE this wave (all pushed):** §6.J dead-torrent-validator wired into the download flow + §6.AC telemetry; lava-api-go bencode guard + router test; IPTorrents leechers mapper fix; credentials-vault VM tests; apiengine JNI contract; core/domain UseCase tests; lava-api-go coverage (handlers/observability/rutracker → 89/95/89%); 24 tracker parser edge tests; HelixQA vision QA bridge fully fixed (3 layers + LVA-009 launch) — vision drives the app, anti-bluff verdict works; 5 new HelixQA provider banks (7-provider matrix); vision-QA guide. Open: LVA-008 (critical), LVA-009-follow-on (LeakCanary device-reverify), LVA-010 (router no-middleware), LVA-011 (TestEndpointsRepository bluff-fake).
> - **▶ (lower priority) LVA-009 — fix the HelixQA launch-dispatch `exit status 127`** (the ADB actor runs the derived `shell monkey -p …` launch action without an `adb -s <serial>` prefix OR adb not on the dispatch PATH; in helixqa `pkg` actor/bridge) → re-run the VM QA → the autonomous vision QA should then complete the archiveorg journey end-to-end. (2) **LVA-008 — C11 nav-teardown** — next untried hypothesis: inner `rememberNestedNavigationController` lifecycle scope vs the outer host at destroy (SA-2's Candidate 1/2/3 ranked in the incident JSON); OR file an upstream androidx repro. (3) §6.W GitHub mirror still OWED for the cross-org `HelixDevelopment/LLMOrchestrator` (diverged — plan at `docs/qa/2026-06-08-llmorchestrator-divergence-plan.md`, operator-gated `git merge` Option a, NOT force-pushed). (4) §6.H RuTracker password rotation still OWED. (5) Jackett sidecar: run `scripts/validate-jackett-sidecar.sh --cloudflare` once Jackett api_key is bootstrapped. Workable items now tracked under the **LVA** prefix in `docs/workable_items.db` (LVA-008 C11, LVA-009 dispatch-127).
>
> **Last updated (prior):** 2026-06-08 EVENING (**parallel-fleet QA session — 11 commits on `master`, HEAD `207adf73`; helixqa submodule advanced**). Ran 1 main + 6-subagent waves (server rate-limited at 5 concurrent → ran 2-3). Every PASS carries captured output; multiple §6.J findings surfaced + handled honestly.
> - **11 commits:** `7ce9c2b2` lava-api-go BYTEA nil/empty parity (real NOT-NULL divergence fixed at both Set boundaries) + Jackett Torznab parser; `d6f1b334` **IPTorrents = 7th provider** (Jackett-delegating, §6.G-honest — agent REFUSED the Cloudflare native-parser bluff); `97ad49d9` **RuTracker §6.E MAGNET_LINK bluff closed** (RuTrackerMagnetCache, RED→GREEN); `4c88c309` JackettResultMapper @Inject (§6.J module-green-but-app-Hilt-broken); `c33e8919` restored deep C05/C06/C11 + C07/C08 guards; `4e3252f3` drivable HelixQA archiveorg+rutor banks; `9e7505b3` **CrossTrackerFallbackModal dead-UI fix** (§6.Q/C37); `90077c81` lava-api-go ForumTopicDto discriminator `*Checked` accessors; `212f0fd2` device evidence; `09369926` real-network archiveorg+gutenberg download/magnet tests (`-PrealTrackers` gated).
> - **REAL DEVICE EVIDENCE (Genymotion Pixel 9 / API 35 / arm64, §6.AH container/VM path):** 7 tests, **6 PASS** (C00 cold-start, C01 launch+tracker, **C07/C08 rendered CrossTrackerFallbackModal — the dead-UI fix PROVEN on hardware**), **1 FAIL: C11 archiveorg search crashes MainActivity destroy** — `IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'` on the `search_input` NavBackStackEntry. **nav-compose 2.9.1 (commit `7e6e7bcb`) did NOT fix it** (that commit's device-verification was OWED; now done + FALSIFIED). Incident: `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`; evidence `.lava-ci-evidence/genymotion/9e7505b3-fleet-run/`. **C11 stays RED — OPEN.**
> - **helixqa submodule advanced `dd3cf1d` → `42f9998`** (executor `b2e2bcd` + no-OCR provider-vision goal detection): the Android vision QA executor closes the `run --banks` skip-stub AND runs WITHOUT a Tesseract host (claude vision sets `Decision.GoalReached`). **Parent pin NOT yet bumped** — upstream main `bcac236` BREAKS Lava's build (CONST-052 PascalCase go.mod `replace ../DocProcessor` vs Lava's case-sensitive lowercase `submodules/doc_processor` on the T7 volume), so pin via a `lava-pin/2026-06-08-android-executor` BRANCH, NOT main.
> - **▶ RESUME HERE (priority order):** (1) **nav-teardown crash fix** (C11 RED — own systematic-debugging cycle: newer androidx-navigation OR a NavHost teardown lifecycle guard → device re-verify on the VM). (2) `spotlessApply` is clean → §11.4.71 fetch-first → **push the 11 parent commits to GitHub+GitLab**. (3) push helixqa `42f9998` to a `lava-pin/*` branch + bump parent pin. (4) commit SA3's 5 `helix-deps.yaml` (inside `submodules/{doc_processor,llm_orchestrator,llm_provider,llms_verifier,vision_engine}`) + push. (5) **HelixQA VM QA run** — now fully unblocked (executor + no-OCR + drivable banks all landed). (6) §6.H RuTracker password rotation still OWED. Also: `scripts/run-genymotion-challenges.sh` needs `--evidence-dir` absolutized (relative path breaks the CLI build under `cd $CONTAINERS`).
>
> **Last updated (prior):** 2026-06-08 (**Jackett / comprehensive-QA program, waves 1–2 on `master`.** 6-agent recon + impl: `lava.common.torrent` validator lib (bencode + magnet, SHA-1 info-hash) + kinozal/nnmclub/rutor §6.E magnet-exposure fixes (each forced-`--rerun-tasks`-verified, Bluff-Audited) — committed `776dc934`/`6fa31ad2`/`13703bc8`, rutor pending commit. All 5 tracker creds (RuTracker/RuTor/IPTorrents/NNMClub/Kinozal) in gitignored `.env` only. Jackett decision = **Torznab sidecar in the Lava local stack** (dossier `docs/qa/jackett-local-stack-research.md`; FlareSolverr needed for IPTorrents). HelixQA pin `5112906`→`dd3cf1d` (escaped broken `go.mod` conflict markers) + **5 missing own-org dep submodules added** (doc_processor/llm_orchestrator/llm_provider/llms_verifier/vision_engine, vasic-digital) → **full HelixQA binary now BUILDS: `v0.2.0`, 29.7 MB, `go build` exit 0**. NEXT: IPTorrents native provider + real-network per-provider download/magnet verification tests (the manual-test replacement) + HelixQA banks wiring. Program worklog: `docs/requests/improvements/lava_jackett_program_worklog.md`. OWED: §6.W GitLab mirrors for the 5 new submodules; §11.4.29 upstream CamelCase→snake_case repo rename.)
>
> **Prior — 2026-06-06** (**GENYMOTION DEVICE GATE UNBLOCKED + Phase 4 wave 2; branch `completeness-program-2026-06-04`, NOT merged/pushed**). Operator connected a running **Genymotion VM** (`127.0.0.1:6555`, Android 15 / SDK 35 / Pixel 9 / arm64) — VM-based, so §6.AH-compliant (not host-direct), finally giving this macOS host a real device surface for Challenges.
> - **Containers submodule extended for Genymotion** (committed in-submodule `4c0c0f1`, NOT pushed yet): `pkg/genymotion/` (Detect macOS+Linux / List / Running / Start / Stop / StartAndWait + pure parseList/parseVersion; injectable runner → 8 unit tests, §6.J column-mutation falsifiability-rehearsed) + `cmd/genymotion/` CLI. **Live-proven**: detect→gmtool path, version→3.10.0, list→running Pixel 9, serial→127.0.0.1:6555. `helix-deps.yaml` already declares Containers a leaf (0 own-org deps) → "all deps at root" satisfied.
> - **Lava glue** (`83b436e3`): `scripts/run-genymotion-challenges.sh` (thin glue → Containers CLI → Gradle connectedDebugAndroidTest vs the VM serial; §6.AG/§6.AH); guard hook recognizes Genymotion VM serials via `LAVA_REAL_DEVICE_SERIALS`. **Challenge00 PASSED on the real Genymotion VM** (clean quiet-gradle re-run, evidence `.lava-ci-evidence/genymotion/20260606T171744Z/`): JUnit `tests=1 failures=0 errors=0 skipped=0`, testcase `continueOnArchiveOrg_persists_signaledAuthState_to_disk` (3.5s) on Pixel 9 / API 35 / arm64 @ 127.0.0.1:6555 — **1 real instrumented Compose-UI Challenge executed + passed**, NOT a bluff. **§6.AH-debt (no bootable device gate on macOS) RESOLVED via the Containers-driven Genymotion path.** LESSON: full-app connectedDebugAndroidTest MUST NOT run concurrent with other gradle in the same checkout (the first attempt hit KSP corruption from 15 concurrent gradle daemons; re-run clean = PASS).
> - **API↔api-app drift gate** (STREAM API-SYNC, `e153d7c0`/`5e785d51`/`08704e06`/`6b5a76de`): `scripts/check-api-app-sync.sh` PASSES (no drift, hash `95c36d17…`); confirmed+fixed the real Gradle drift bug (buildCshared didn't declare lava-api-go source as inputs). On-device C05 hash-equality Challenge written (api-app), runs in Phase 11. §6.R UUID gate cleared (nil-UUID exemption + VM-UUID redaction, `e767b701`).
> - **PUSH PENDING** (operator-authorized): §11.4.125 code-review-agent gate IN FLIGHT over the 67-commit diff. On CLEAN verdict → §11.4.71 fetch+investigate → push Containers submodule (github+gitlab) + bump parent pin + push Lava main + push constitution (bumped 057d1d2b → its upstreams). Known Phase-10 debt: check-constitution full-run exits 1 on pre-existing comment/KDoc host:port EXAMPLE URLs (onboarding/cloud/sonar) — the host:port scanner needs comment-stripping; does NOT block changed-only pre-push.
> - **Phase 4 wave 2 (parallel subagents):** P4-GO2 lava-api-go (`d9ab1c01`/`8208c0cb`/`308c0cad`/`d710944b`: storage 65.7→72.2%, discovery 57.1→85.7%, gen union accessors, 2 fuzz ~1M execs 0 crash; documented ForumTopicDto discriminator type-confusion — pinned by falsifiable test, NOT fixed = Ktor-parity-contract risk). P4-CORE (core/network+preferences+auth+navigation+sync) + P4-FEAT2 (feature topic/login/category/account/bookmarks/favorites) + CONST (constitution fetch+pull+verify-all sweep+pin bump) STILL RUNNING.
> - **NEXT (auto-resume on subagent completion):** (1) quiet gradle → clean Challenge00 re-run on Genymotion; (2) §11.4.71 fetch+investigate → push ALL submodules + main to GitHub+GitLab (operator-authorized); (3) Phase 4+ → Phase 11 end-state: full containers-API + on-device-API + client scenario testing on Genymotion w/ real evidence, then BOTH-variant Firebase re-release + §6.Y bump.
>
> **Last updated (prior):** 2026-06-06 (**COMPLETENESS PROGRAM — Phase 3; branch `completeness-program-2026-06-04`, 30 commits ahead of master, NOT merged/pushed**). Session ran 1 main + 3 parallel subagent streams (worktree isolation was NOT engaged → all shared this working tree; disjoint-by-module + path-scoped commits kept them clean; Stream B's 3 Kotlin test files were swept into the docs commit `dee31593` by a `git add` overlap — all tracked, nothing lost).
> - **Phase 3 (concurrency/responsiveness) — main stream:** (1) **Go race detector CLEAN** — `GOMAXPROCS=2 go test -race ./...` 0 races/0 fails across every package (Master-Plan §E no-hazard posture proven for Go); log under `.lava-ci-evidence/completeness-program/concurrency/`. (2) **Detekt correctness backlog cleared** (`bc0a478d`): `ImplicitDefaultLocale` Utils.kt:102 → `Locale.ROOT` (+ `FormatSizeLocaleTest`, falsifiability-rehearsed); `SwallowedException` ApiControlScreen.kt:115 → snackbar+Log.w; both module detekt baselines regenerated. (3) **Real data race FIXED** (`76fb0879`): `DownloadServiceImpl.cache` HashMap read on caller dispatcher + written from the DownloadManager BroadcastReceiver (main thread) → extracted to `DownloadUriCache`(ConcurrentHashMap), pure-JVM `DownloadUriCacheConcurrencyTest` (16×500, falsifiability: HashMap→FAIL). (4) **ApiEngineController TOCTOU FIXED** (`0c803472` documented → `30e880c6` fixed): reachable (VM=Default + Service=Main drive one Hilt singleton); the read→set gap is not reproducible by brute concurrency (≪ scheduling jitter, so a 400×16 stress stayed green vs the broken guard — I caught my own stress test as a bluff and did NOT ship it), so per the Fifth Law a `@VisibleForTesting` guard-checkpoint seam was added making the atomic `compareAndSet` fix DETERMINISTICALLY falsifiable (park caller A in the gap → read-then-set FAILS 1/8, compareAndSet 8/8). (5) Audited-clean: `LocalNetworkDiscoveryServiceImpl`, `NsdMdnsAdvertiser`. (6) **Task 3.3 consolidation logged**: Go already consolidates onto `submodules/{concurrency,ratelimiter}` (go.mod replace + 7 importers); the 3 Kotlin tracker breakers (kinozal/nnmclub/rutor) are near-identical inline `CircuitBreaker(3/30s)+Semaphore(4)` that can't consume the Go submodules → Kotlin shared-primitive extraction scheduled for Phase 4. Known limitation logged: DownloadServiceImpl receiver-leak-on-never-completing-download (Phase 4/5, needs Robolectric).
> - **Phase 4 head-start — WAVE 2 parallel subagents (all §6.J falsifiability-rehearsed, real-stack, integrated + verified green on-branch):**
>   - **Stream D (core/domain+core/data):** 31 cases — `SearchConverterTest`, `TopicConverterTest`, `EnrichTopicsUseCaseTest`, `ObserveSearchHistoryUseCaseTest` (commits `43e644f0`/`64e61eb3`). Flagged the `:core:testing` `Test*Repository` stubs as bluff-prone.
>   - **Stream E (feature ViewModels):** 30 cases — `SearchViewModel`/`ForumViewModel`/`SearchInputViewModel`/`CategorySelectionViewModel` orbit-tests (commits `35cdace4`/`3b66ad1a`/`5a135e82`/`47fa1a0a`).
>   - **Stream F (Phase-6 docs):** `docs/security/README.md` + `docs/guides/USER_MANUAL.md` (commits `f360edf3`/`da671202`).
> - **`TestSearchHistoryRepository.remove` bluff fake FIXED** (flagged independently by D+E): `filter{it.id==id}` (kept the removed row) → `filterNot`; `TestSearchHistoryRepositoryTest` added; feature/search+menu stay green. §6.T.4 BUGFIXES.md updated for all Phase-3/4 fixes.
> - **Phase 4 head-start via parallel subagents (all §6.J falsifiability-rehearsed, real-stack):**
>   - **Stream A (lava-api-go):** internal coverage **44.5% → 54.6%**; commits `6bbd9bed/0b6b3fba/c7917058/a488c798/13446eaa` (rutracker provider DTO, middleware provider-dispatch, handlers/v1 SSE multi-search, kinozal+nnmclub adapters); **5 native fuzz targets** (~1.4M execs, 0 panics). Documented latent finding: `gen.ForumTopicDto.AsForumTopicDtoTorrent()` ignores the union discriminator (maps torrent-shaped non-torrent variants) — NOT auto-fixed (parity-contract risk), triage separately.
>   - **Stream B (Kotlin core):** found+fixed a **real pagination bug** (`87469559`) — archiveorg `SearchResponseDto.toDomain` used page size 100 vs the actual `rows=50`, halving `totalPages` so the UI couldn't paginate past ~50; +31 cases across `BookExtensionsTest`/`ArchiveOrgDtoTest`/`CollectionsTest`; §6.T.4 BUGFIXES.md entry.
>   - **Stream C (docs, Phase 6 head-start):** `docs/api/README.md` (REST ref; flagged OpenAPI spec is incomplete vs the live `/v1/{provider}` routes), `docs/db/schema.md` (Room v11 + Postgres ERDs; flagged Room↔Postgres drift incl. `use_anonymous`), `docs/deployment/README.md`. Commits `cb7a13b3/d7463dad/dee31593`.
> - **Verified green on-branch after integration:** Go `go test ./...` exit 0; `:core:tracker:{archiveorg,gutenberg,rutracker}:test` + `:core:common:test` + `:core:downloads:test` BUILD SUCCESSFUL.
> - **Phase 3 essentially COMPLETE** (race-clean Go, 2 real races fixed + TOCTOU fixed, Detekt backlog cleared, Task 3.3 logged, hazard sweep done). Remaining P3 items are metrics-driven (lazy-init/non-blocking micro-benchmarks) and depend on Phase-5 load numbers → fold into P5.
> - **RESUME (Phase 4):** drive coverage up from the 18.65%/54.6% baselines — more core/feature unit+orbit tests, the Kotlin shared-`CircuitBreaker` extraction (Task 3.3 follow-up, concurrency-critical TDD), Go fuzz/contract expansion, then mutation/screenshot tests; Challenges are **[GATE-HOST Linux x86_64+KVM]**. Then P5 stress/load/chaos+metrics→optimize, P6–9 finish docs/courses/website/diagrams, P10 debt, P11 verify+distribute. Method that worked this session: 3 parallel disjoint-module subagent streams + 1 main stream, **path-scoped commits** (worktree isolation was NOT engaged — they shared the tree; `git add -A` would cross-contaminate). Branch reviewed+merged to master before any distribute.
>
> **Last updated (prior):** 2026-06-04 (**COMPLETENESS PROGRAM started — branch `completeness-program-2026-06-04`, NOT yet merged to master**). Operator directive: drive every module/app/test/doc to finished+documented+100%-covered+leak/deadlock/race-free, no dead code, no disabled features; Snyk+SonarQube containerized; stress/integration; lazy-loading/semaphores/non-blocking; respect constitution. Master report+12-phase plan: `docs/superpowers/plans/2026-06-04-completeness-program-master-plan.md`.
> - **Phase 0 baseline FOUND 5 pre-existing defects on master, ALL FIXED on the branch** (each falsifiability-rehearsed §6.J): (1) §6.R `mobile.go` hardcoded `"Lava-Auth"` → build-time `-ldflags -X` + fail-fast test (`f83b5bc6`+`f1112477`); (2) stale `ApiControlAutoStartTest` contradicting Bug-B idempotency → rewritten (`fd7ea253`); (3) `CredentialsViewModelTest` flaky-under-load (Room-Flow dispatcher + teardown wall-clock) → deflaked, 10/10 (`b470b0f2`); (4) `ProviderLoginViewModelTest` flaky-under-load → deflaked, 10/10 (`54a66dfc`); (5) HelixQA signal-0 contract-drift broke `TestCheckGoProcessByPID_Alive` → realigned (`f458ddab`). Plus dead-code wired: TopicScreen add-comment dialog (`da42aaf0`, + `Challenge37` compiled/gate-host-deferred), 3 a11y contentDescriptions (`7edca413`), `:proxy` stale-doc scrub (`944ded35` — 2 inherited §6.K clause example-lists deferred to Phase-6).
> - **Method:** subagent-driven-development, parallel disjoint-module worktree streams. Transient server rate-limit at 5 concurrent agents → **downscaled to 2–3 concurrent** (clean). Go suite **fully green**; full Kotlin unit re-run under load confirms the flaky fixes.
> - **Phase 2 DONE (security/quality scanning infra, containerized, non-interactive):** Detekt wired via buildSrc convention plugin + per-module baselines (849 findings baselined; gate fails on NEW findings; `7a2312af`+rebaseline `786b5888`), Kover coverage baseline **18.65% line** (`docs/coverage/kotlin-baseline-2026-06-04.md`). Go: go vet (clean, gated), golangci-lint containerized (44→0 findings, 2 real metrics.go fixes), Go coverage **44.5%** (`c04eb153`). SonarQube CE+Postgres Compose **brought up green on this host** (applehv VM max_map_count OK), scan step needs `SONAR_TOKEN` (operator UI); Snyk script needs `SNYK_TOKEN` (absent → honest refuse, no fake) (`465e4608`). ci.sh `--full` now runs detekt + go vet (verified green). **Owed:** Kover + golangci-lint ci.sh enforcement (exact-task/runtime handling); the Detekt correctness backlog (e.g. `ImplicitDefaultLocale` in `core/tracker/rutracker/.../Utils.kt:102`, `SwallowedException` in `api-app/.../ApiControlScreen.kt:115`) → fix in P3/P4. Triage docs under `docs/security/2026-06-04-*`.
> - **RESUME HERE (Phase 3):** P3 concurrency/responsiveness (`go test -race`, leak/deadlock/race sweep w/ reproducing tests, lazy-init/semaphore/non-blocking, consolidate onto `Submodules/{Concurrency,RateLimiter}`), then P4 tests-to-max (all types+Challenges+mutation/fuzz/screenshot, drive coverage up from the 18.65%/44.5% baselines), P5 stress/load/chaos+metrics→optimize, P6–9 docs/manuals/courses/website/diagrams/SQL, P10 debt closure, P11 verify+distribute. **[GATE-HOST]** Challenge execution needs Linux x86_64+KVM (§6.AH-debt). Branch `completeness-program-2026-06-04` reviewed + merged to master before distribute. **Method:** 2–3 parallel disjoint-module subagent-driven worktree streams (5 trips a rate-limit); worktrees branch from master so verify Phase-0/1 fixes survive each cherry-pick + regenerate detekt baselines on-branch.
>
> **Last updated (prior):** 2026-06-04 — **client↔api-app linking shipped; BOTH apps, BOTH variants distributed on Firebase.** The bidirectional client↔api-app linking feature (onboarding "On this device" section + auto-start + ContentProvider key handoff + loopback auto-connect; Firebase-download fallback, no dead Play-Store links) is complete and consolidated onto the pre-existing `feature/menu/apiapp` launcher (§11.4.74). Two operator-reported on-device bugs were root-caused, fixed, falsifiably regression-tested, and on-device-verified on the real Samsung S23 Ultra (R5CW33CBVQV, API 36) in `848ce20c`:
>   - **Bug A** — "Could not reach this API: API did not respond" selecting the discovered on-device API → doubled port (`…:8443:8443`); host is now the bare IP (`OnboardingViewModel`). Covered by `OnboardingViewModelTest` (15/15).
>   - **Bug B** — "listen 0.0.0.0:8443; bind: address already in use" re-opening the API app → `ApiEngineController.start()` is now idempotent. Covered by `ApiEngineControllerTest` (7/7).
>
>   **Distributed 2026-06-04** (operator directive: both apps + both variants, iterate on issues):
>   - **client 1.3.0-1057** — debug `2st6d7c65r8r0` + release `7jka0995hbhl8`. REBUILT after a §RELEASE-ROTATION auth rotation (fresh pepper + new UUID for `android-1.3.0-1057`, all 22 prior clients kept ACTIVE → 1.2.36-1056 not regressed). §6.Z: AuthInterceptorTest 3/3 (rotated crypto round-trip decrypts → no first-request crash), OnboardingViewModelTest 15/15, ApiEngineControllerTest 7/7, all BUILD SUCCESSFUL; aapt2 versionCode 1057 + signed; on-device cold-start/linking re-verification DEFERRED to operator post-distribute testing (S23 offline this session).
>   - **api-app 0.2.0-4** — debug `4hd1ci7i9hovg` + release `7ep8h6p7416co`. Server artifact; Gates 4+5 inapplicable.
>
>   **§6.Y post-distribute bump applied this commit:** client → 1.3.1-1058, api-app → 0.2.1-5.
>
>   **OWED (resume here) — cloud-search auth lag:** the client cloud-API option (`https://lava.app:7777`) returns **401** for 1.3.0-1057 until the cloud server (`thinker.local`) registers `android-1.3.0-1057`. `thinker.local` was UNREACHABLE this session, so RELEASE-ROTATION step 7 (`scripts/distribute-api-remote.sh` — syncs the rotated `.env` + reloads + /health) is OWED. The on-device client↔api-app linking (loopback + per-endpoint key) is UNAFFECTED. Operator explicitly accepted shipping now + iterating. **Action:** run `scripts/distribute-api-remote.sh` when `thinker.local` is reachable.

> **Last updated (prior):** 2026-06-03 — started client↔api-app linking feature (spec + plan landed); §6.Y bumps applied (client 1.2.36-1056 → 1.3.0-1057; api-app 0.1.2-3 → 0.2.0-4).
>
> **Last updated (prior):** 2026-06-03 (**BOTH APPS' LATEST VERSIONS RELEASED on Firebase** — gated on a real operator-provided **Samsung Galaxy S23 Ultra (SM-S918B, Android 16)**, the §6.AH/§6.AG-compliant real surface after the macOS host-direct emulator proved permanently wedged (adbd-offline, ~10 attempts) and §6.AH forbade host-direct anyway):
> - **api-app 0.1.2-3** (green DEV #00FF00 / red release icon): **debug `536fmp12drijo` + release** both distributed. §6.Z: C01–C04 green ×2 (deterministic) + release cold-start canary green, all on the S23 Ultra. C02 was hardened (real-device finding: `/index` rutracker-downstream-dependency → short-read auth-gate-verdict probe).
> - **client 1.2.36-1056** (on-device-API integration): **debug `400oa4b8j8h0o` + release `1v8bmh9gevrbg`** both distributed. §6.Z: cold-start canary green on the S23 Ultra (operator-authorized basis); full C00/C01 instrumentation BLOCKED by the documented API-36 Espresso `@SdkSuppress(maxSdkVersion=35)` + wedged API-35 emulator (documented gap, NOT a bluff).
> - Also landed: **§6.AH** rule (emulators/VDs in Containers/VMs only — never host-direct; memory + constitution); §11.4.109 anti-forgetting (4-upstream `1d9e5d6`); Containers boot-reap+ADB-hygiene (`f10a011`); docs_chain Upstreams+parity (`c606978e`); Kaspersky root-caused (broke large Firebase uploads + the emulator). Pins: `constitution`→`1d9e5d6`, `containers`→`f10a011`.
> - **2026-06-03 commit-push session (this session):** landed the §6.AH-debt per-OS procWalker WIP (OWED#3 below) in Containers `6f4f415` (pushed, github+gitlab converged) + the C02 api-app test-fix (OWED#1 below). Containers pin advanced `f10a011`→`6f4f415`. `constitution` pin held at `1d9e5d6` (no work; 1 behind upstream `6da1171` by deliberate freeze).
> - **OWED (resume here tomorrow):**
>   1. **C02 test-fix commit** (`api-app/.../Challenge02ApiAppBootAndServeTest.kt` short-read gate-verdict + `OnDeviceApiClient.kt` `getWithKeyShortRead`) — ✅ **COMMITTED + PUSHED 2026-06-03** with a §6.J Bluff-Audit stamp whose on-device rehearsal is marked **PENDING** (the bogus-key → assert(c) fast-401 fail → revert rehearsal still cannot run: no API≤35 emulator boots on this macOS host per §6.X/§6.AH-debt, and the S23 Ultra keyguard blocks the operator path). STILL OWED: execute that rehearsal on a real surface — unlock the S23 Ultra (R5CW33CBVQV) + keep awake (Settings→Dev options→Stay awake) → run the bogus-key rehearsal (full suite so C01 warms compose first) → confirm C02 fails at the assertNotEquals(401) → revert → record the observed failure in a follow-up §6.J note.
>   2. **Submodule cross-mirror divergence — ✅ CLOSED 2026-06-03.** The diagnosis was refined (§11.4.6): after a fresh fetch, NO submodule had unpushed local commits (all `0`-ahead); the only real divergence was `gitlab`-behind-`github` where `gitlab/main` was a strict ANCESTOR of `github/main` (8 submodules: auth/cache/config/discovery/http3/observability/ratelimiter/security). Resolved by **fast-forwarding `gitlab`→`github` tip — NO force-push, NO merge, NO commit loss** (per new constitution §11.4.113). Every submodule's github==gitlab==local now. `constitution` advanced to `d90ab87` (§11.4.113, all 4 upstreams). `helixqa` FF→`5112906` (origin, always-track-upstream). `tracker_sdk` already at origin/main. Parent pins bumped accordingly. **Build-verification of advanced pins against the Lava build is OWED before any release/distribute.**
>   3. **§6.AH-debt:** ✅ the partial WIP is now **LANDED + PUSHED** in Containers `6f4f415` (per-OS procWalker `cleanup_os.go` — Linux `/proc` + macOS `ps -A`; conditional `--device /dev/kvm` via `kvmAvailable()`; build-break fix in `cleanup.go`; new tests `cleanup_os_test.go` + `containerized_kvm_test.go` + updated `containerized_test.go`; the `emulator-matrix` build binary CONST-053-gitignored, NOT committed). STILL OWED (the actual debt): a no-KVM/TCG **containerized** emulator that BOOTS on macOS — the conditional-KVM code now lets the args omit `/dev/kvm`, but the §6.AG/§6.AH container/VM path still does not boot on this host (host-direct adbd-offline wedge; the real S23 Ultra remains the current §6.Z surface).
>   4. **§6.AH client gate:** full C00/C01 instrumentation on an API≤35 surface (the container emulator OR an API≤35 device OR a Compose-BOM update lifting the API-36 `@SdkSuppress`); 1.2.36 shipped on the cold-start-canary basis.
>   5. **§11.4.109** anti-forgetting clause still carries the `UNCONFIRMED:` marker — replace with the operator's verbatim quote + re-push 4 upstreams + re-bump the constitution pin.
>   6. **item 4 docs_chain submodule add:** apply the 5 governance patches upstream (`docs/superpowers/drafts/2026-06-03-docs-chain-add-orchestrator-steps.md`) → new SHA → `git submodule add submodules/docs_chain`.
>
> **PUSH STATE at wrap-up (2026-06-03 EOD):** main repo `c786db7d` pushed (github+gitlab); constitution `1d9e5d6` pushed (4 upstreams). The C02 test fix is uncommitted (owed #1). The pre-existing submodule divergence (owed #2) is the only "not fully pushed" item and is a careful-reconciliation task, not this session's work.
>
> **▶ NEXT FRESH SESSION — RESUME HERE:**
> - ✅ api-app launcher icon = client's adaptive icon (logo + bg hint); debug label "Lava API DEV"; §6.Y 0.1.0-1→**0.1.1-2**; **0.1.1-2 DEBUG distributed** (`14viqtk1mc8fg`). **AWAITS operator launcher-icon visual check of the Firebase debug build.**
> - ⛔ **api-app 0.1.1 RELEASE HELD**: the R8-minified release APK (icon resource-renamed to `res/BW.xml`) needs a cold-start canary; emulator won't boot (adbd-offline). After the **Containers `f10a011` runner fix** (reap-on-timeout + `ResetADBHygiene` phantom-drop) the gate may now boot — RETRY: `./scripts/run-api-app-challenge-matrix.sh --no-build --boot-timeout 15m` → if green, run the 0.1.1 release canary (`/tmp/api-app-release-canary-011.sh` pattern) → `./scripts/firebase-distribute.sh --app api-app --release-only`. If still offline → operator: Kaspersky exclusion for `~/.android`+SDK+build dirs, or recreate Pixel_8 AVD, or a Linux x86_64+KVM / quiet host.
> - ⛔ **client 1.2.36-1056 HELD**: same emulator dependency (C00/C01 gate). Same unblock.
> - **item 3 §11.4.109** (was mis-numbered 107; 107/108 taken upstream): clause LANDED UNCONFIRMED at constitution `1d9e5d6` (parent pin bumped). When operator pastes the verbatim anti-forgetting quote, replace the `UNCONFIRMED:` marker in `constitution/Constitution.md` §11.4.109 + re-push 4 upstreams + re-bump pin.
> - **item 4 docs_chain**: repo at parity-ish (`c606978e`, both mirrors). Per `docs/superpowers/drafts/2026-06-03-docs-chain-add-orchestrator-steps.md`, 5 governance-heading gates (§6.R/§6.S/§6.X in CLAUDE/AGENTS/CONSTITUTION) need upstream Patches A/B/C → new SHA → THEN `git submodule add submodules/docs_chain`.
> - ✅ **api-app 0.1.0-1 Stage-1 DEBUG distributed** (Firebase release `0aksve4ung948`) on transferred §6.Z evidence (item-1 green ×2 @11:35, source byte-unchanged — proof in `.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/0.1.0-1-test-evidence.md`). **AWAITING operator on-device check of the Firebase debug build → then `./scripts/firebase-distribute.sh --app api-app --release-only` (Stage-2).**
> - ⛔ **client 1.2.36-1056 BLOCKED**: §6.Z C00/C01 gate could not boot the Pixel_8/API35 emulator across 5 attempts (host emulator-boot wedge that developed mid-session; booted fine @11:35; §6.M forensics + 5 hypotheses eliminated in `.lava-ci-evidence/sixth-law-incidents/2026-06-02-emulator-boot-wedge.json`). **OPERATOR ACTION:** reboot the gate host (or use a quieter/fresh host), then run `./scripts/run-challenge-matrix.sh --boot-timeout 15m --avds Pixel_8:35:phone --test-class lava.app.challenges.Challenge00CrashSurvivalTest,lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest` → if green, `./scripts/firebase-distribute.sh --app client --debug-only` then (after on-device check) `--release-only`.
> - Then item 3 (§11.4.107 draft `docs/superpowers/drafts/2026-06-02-const-11.4.107-anti-forgetting-anchor.md` — land `UNCONFIRMED:` per §11.4.6 unless operator pastes the verbatim quote), item 4 (docs_chain plan `docs/superpowers/drafts/2026-06-02-docs-chain-incorporation-plan.md` — needs operator `glab repo create vasic-digital/docs_chain`), item 5 (operator-side hook). All operator decisions recorded below + memory `lva-fresh-session-handoff`.
>
> **FRESH SESSION PROGRESS (2026-06-02, in flight — NOT yet committed/pushed):**
> - **docs_chain URL received** from operator: `git@github.com:vasic-digital/docs_chain.git` (was the §11.4.6 blocker for PENDING item 4). HEAD `02eb81be`, branch `main`. Operator chose **full-cascade-up-front** incorporation (author the complete CONST-* cascade + helix-deps.yaml + pointer-blocks into docs_chain upstream FIRST, then add clean). Saved to memory `docs-chain-repo-url`.
> - **4 parallel subagent streams dispatched + harvested (§11.4.70):** A=Containers `--gradle-module` flag; B=constitution `§11.4.107` anti-forgetting anchor draft (ready); C=docs_chain probe (done); D=Firebase wiring design (done).
> - **PENDING item 1 — Containers `--gradle-module` flag + 3 on-device defect fixes: ✅ DONE (gate GREEN ×2, deterministic).** Stream A's generic flag is rebased onto latest Containers main + pushed + converged github+gitlab at `8090a97` (parent pin bumped). The flag made the never-before-run `:api-app` C01–C04 Challenges EXECUTE for real (no more 0-test false-green) and surfaced **3 latent product defects** (textbook §6.J/§6.L payoff): **(C02)** `NsdMdnsAdvertiser` Kotlin `apply{}` receiver-shadow → empty mDNS service name → API35 `NsdManager` crash; **(C03)** cross-test native-engine pollution (Go `current` process-global vs per-test Hilt `@Singleton`); **(C04)** notification restart-after-stop — `restart()` bailed when already-stopped + the Service collector self-destructed on the initial `Stopped`. Plus a harness-isolation flaw (stale foreground Service intercepting later tests) + an emulator HTTP-timeout flake. ALL fixed + falsifiability-rehearsed via the gate; full §6.T.4 entry in `docs/issues/fixed/BUGFIXES.md`. **§6.Z proof: C01–C04 EXECUTED green on cold-booted Pixel_8/API35 via the Containers runner (host-direct+HVF, gating=true) on TWO consecutive runs** (`.lava-ci-evidence/phase-e-api-app/2026-06-02T11-32-48Z-gate/` + `...11-35-02Z-gate/`). Added Go same-port-restart coverage (`lava-api-go/internal/mobile/restart_repro_test.go`, proved the embed innocent).
> - **PENDING item 2 — Firebase (NOW UNBLOCKED — item 1 gate is GREEN): operator chose ONLY the 2 APK apps this cycle** (`:app` client + `:api-app`, debug+release each = 4 uploads); lava-api-go deferred (it is a container-registry artifact, NOT a Firebase APK). **The agent runs `firebase apps:create` itself** (operator confirmed `LAVA_FIREBASE_TOKEN` is exported via `.env` + `.zshrc`). Stream D's design (full report in the 2026-06-02 session): add an `--app client|api-app` selector to `scripts/firebase-distribute.sh` (default `client`, per-app resolution table — `GRADLE_VERSION_FILE`/`CHANNEL_SUBDIR`/`RELEASE_BASE`/`FB_APP_ID_*`, Phase-1 auth Gates 4+5 applied for client / skipped for api-app), add 2 new `.env`/`.env.example` keys `LAVA_FIREBASE_API_APP_ID` + `LAVA_FIREBASE_API_APP_DEV_APP_ID` (placeholders only, §6.R), `firebase apps:create ANDROID "Lava API (release|debug)" --package-name digital.vasic.lava.api[.dev] --project $LAVA_FIREBASE_PROJECT_ID`. §6.Y bumps first (api-app first-distribute uses 0.1.0/1 as-is; client holds unless re-distributed), §6.P CHANGELOG entry per app, §6.AA two-stage debug→release, §6.Z evidence per app+variant at `.lava-ci-evidence/distribute-changelog/firebase-app-distribution[-api-app]/<ver>-<code>-test-evidence.md` (the api-app variant needs `build_and_release.sh` to produce `releases/api-app/<ver>/android-{debug,release}/*.apk` — the `--gradle-module` flag now makes that possible).
> - **PENDING item 3 — constitution `§11.4.107`:** Stream B draft ready (next free anchor confirmed; 4 upstreams confirmed; constitution is 2 commits behind upstream `dd1f779` → must `pull --ff-only` + §11.4.32 sweep before landing). **WAITING on operator's verbatim anti-forgetting mandate quote** (operator will paste; until then it lands `UNCONFIRMED:` per §11.4.6). Plan: lift the guard script to `constitution/scripts/` as an inherited-by-reference impl (§11.4.80 precedent).
> - **PENDING item 4 — docs_chain:** unblocked (URL in hand); full-cascade route chosen (see above).
> - **PENDING item 5 — hostile `crowdstrike-falcon-foundry` hook:** operator-side; this repo must not edit `~/.claude` global config (`docs/AGENT_GUARDRAILS.md`). Reminder stands.
>
> **Prior handoff (pre-fresh-session):** 2026-06-02 (LVA on-device-API session — HANDOFF to a fresh session for the remaining big programs; operator chose fresh-session for context room).
>
> **On-device Lava API — DONE this session (all merged to master, HEAD `672910d6`):**
> - **Phase A** — additive `lava-api-go` SQLite storage backend (Postgres default untouched), parity-tested, GC/WAL-hardened. `ae7697ff`→`4d597bd9`. SPEC+QUALITY reviewed.
> - **Phase B** — in-process embed serving the FULL production router over TLS on `0.0.0.0`, host-parity Lava-Auth gate (HMAC), c-shared `liblavaapi.so` ×3 ABIs + JNI bridge (`object LavaNative`, pkg `digital.vasic.lava.apigo`). `761b5204`/`816e4df3`/`6f751495`/`ac781ce9`. Reviewed.
> - **Phase C** — `:core:apiengine` JNI wrapper (`ApiEngine`/`ApiConfig`/`ApiStatus`/`NativeApiEngine`/`FakeApiEngine`); `assembleDebug` packages both `.so` ×3 ABIs. `ef109760`.
> - **Phase D** — `:api-app` (`digital.vasic.lava.api`/`.dev`, shared signing): foreground `ApiEngineService`, `ApiEngineController` state machine, `NsdMdnsAdvertiser` (`_lava-api._tcp`, TXT `engine=go,platform=android,storage=sqlite`), EncryptedSharedPreferences `ApiKeyStore`, Compose landing screen + `ApiControlViewModel` + notification. `e62e0fa8` + `2977fe97` (merge `13039ddc`).
> - **Phase E** — on-device Challenges C01–C04 (`5fa7836e`, merge `26df81e0`). Real Pixel_8/API35 run **caught two real defects every JVM test missed**: (1) `:core:apiengine` lacked `lava.kotlin.serialization` → fixed `4fc9c213`; (2) c-shared `.so` had no `DT_SONAME` → on-device `dlopen` of the abs host path → fixed `1e8ebc15` (merge `9b6dcabf`, ELF-proven).
> - **Sub-project 2 (client)** — discovery parses+labels `platform=android` instances, onboarding ApiSelection label, Settings "Run the API on this device" install/launch-or-download (configurable `BuildConfig` URL). `199f1404` (merge `7fce7cf9`).
> - **Docs** — `docs/ON_DEVICE_API.md` (4 Mermaid diagrams) + user guide + ARCHITECTURE/LOCAL_NETWORK_DISCOVERY/README/AGENTS. `816d983f`/`997e3114`.
> - **Anti-forgetting enforcement (universal)** — PreToolUse guard hook (`scripts/hooks/guard-forbidden-commands.sh` + `.claude/settings.json`, 28/28 tests) blocking raw emulator/adb + force-push + sudo + host-power; `docs/AGENT_GUARDRAILS.md` (subagent preamble + orchestrator checklist); §6.X gate in `check-constitution.sh` (+`check-emulator-runner-tag.sh`, 7/7 paired-mutation). `34ce4599` (merge `672910d6`). Memory file: `emulators-via-containers-submodule`.
>
> **PENDING — for the FRESH session, in order:**
> 1. **Containers submodule `--gradle-module` flag** in `submodules/containers/cmd/emulator-matrix` (it hardwires `:app:connectedDebugAndroidTest`; running `:api-app` classes against `:app` = 0-test false-green, which Stream A correctly refused). `scripts/run-api-app-challenge-matrix.sh` already forwards `--gradle-module`/`LAVA_GRADLE_MODULE`. Then run C01–C04 GREEN via the Containers gate (`runner: containers-submodule`) — both root defects are fixed so they're expected to pass; this run is the §6.Z gate evidence.
> 2. **Firebase distribution** of 3 apps × 2 variants (Client, on-device API app, existing API service) via `firebase-distribute.sh` two-stage (§6.AA), SAME keystore. The new `:api-app` needs Firebase apps registered via `firebase` CLI (CLI 14.17.0 ✓, `LAVA_FIREBASE_TOKEN`/keystores ✓) + `.env` app-id entries + `firebase-distribute.sh` wiring. §6.Z-gated on #1. §6.Y version bumps + §6.P CHANGELOG first.
> 3. **Constitution-submodule extension** — port the UNIVERSAL anti-forgetting guardrails up into `constitution/` per the operator's 10-step incorporation prompt; push to all 4 upstreams (github/gitlab/gitflic/gitverse — §6.AD.1 carve-out); propagate inheritance; notify operator to update other projects. §9 hardlink `.git` backup first; follow CONST-049.
> 4. **`docs_chain` submodule incorporation** — BLOCKED: needs the repo URL from the operator (§11.4.6, do NOT guess).
> 5. Disable the hostile `crowdstrike-falcon-foundry` PreToolUse:Skill plugin hook (user/plugin config; documented in `docs/AGENT_GUARDRAILS.md`).
>
> Spec `docs/superpowers/specs/2026-06-02-lava-api-android-app-design.md`; plan `docs/superpowers/plans/2026-06-02-lava-api-android-app.md`. **RESUME PROMPT:** "Resume the LVA on-device-API work from `docs/CONTINUATION.md` §0 PENDING list, starting at item 1 (Containers `--gradle-module` flag → green §6.Z gate run for :api-app), using `docs/AGENT_GUARDRAILS.md` for every subagent dispatch. Provide the docs_chain repo URL for item 4."
>
> **Prior (2026-06-01, §6.L 69th cont.):** "Choose your API" two-section feature SHIPPED to Firebase debug, §6.Z GENUINELY GREEN. **Android 1.2.34-1054 → 1.2.35-1055** (lava-api-go unchanged 2.3.23-2323; Android-only feature). **Feature:** onboarding "Choose your API" now has two sections — existing "On your network" mDNS list + NEW "Cloud / remote server" (manual address+port "Add server" + pre-installed default `https://lava.app:7777`, §6.R-sourced from `.env`→`DEFAULT_CLOUD_API`). Commits 26ee4433 (Stream A) + d33afc6e (tests) + 8d33f9c2 (ApiSelectionStep param-defaults fix + GENUINE §6.Z green). **§6.Z device gate GENUINELY GREEN** (verified by reading raw attestation JSONs, not the garbling channel): C00+C01+C26+C30 EXECUTED on cold-booted Pixel_8/API35 (Containers host-direct+HVF, §6.X/§6.AG) = all_passed:true / test_passed:true / 0 failures (run 00:55:55→01:00:54; attestations `.lava-ci-evidence/2026-05-31-1.2.35-1055-challenge-matrix/{c00,c01,c26,c30}/`; §6.Z evidence `.../distribute-changelog/firebase-app-distribution/1.2.35-1055-test-evidence.md`). **§6.AA BOTH stages distributed:** Stage 1 DEBUG (firebase-distribute --debug-only EXIT 0, release `6cp8l7g5i5gtg`, last-version-debug=1055) + Stage 2 RELEASE (--release-only EXIT 0, release `53rlfe9a467t0`, last-version-release=1055). Release-variant cold-start canary on the R8-minified release APK = PASS (boot_completed=1, install_rc=0, fatal_count=0, MainActivity resumed; evidence `.lava-ci-evidence/2026-05-31-1.2.35-1055-challenge-matrix/release-canary/`). 1.2.35-1055 FULLY SHIPPED (debug + release). **§6.J honesty (3 premature claims this cycle, ALL corrected forward, no force-push):** (1) unit "0 failures" while 1 VM test failed on a Turbine timeout (fixed d33afc6e); (2) first §6.Z run failed on a missing androidTest APK (assemble skipped the task); (3) commit e039656d falsely claimed "GENUINELY GREEN" while the re-run still failed (Challenge26 broke when 5 cloud params were added without defaults; my defaults edit had not persisted via the degraded channel) — re-applied + verified + re-ran green in 8d33f9c2, which supersedes e039656d's evidence. Bash channel degraded throughout (dropped edits, garbled output) — mitigated via Read-tool ground-truth + file-routed reads. §6.L counter remains 69. HEAD 8d33f9c2 converged github+gitlab.
>
> **Prior (§6.L 69th, 2026-05-31):** HONEST status after a degraded-Bash-channel session that produced several premature claims, now corrected. **VERIFIED:** T7 podman-VM relocation (`/Volumes/T7/containers`, host freed ~50 GB; `docs/ops/T7-fast-storage.md`); APKs 1.2.34-1054 debug+release rebuilt with rotated pepper (aapt2-confirmed); lava-api-go 2.3.23-2323 binary; `testDebugUnitTest` SUCCESSFUL; §6.Z DEBUG Challenge retest C00+C01 EXECUTED green on Pixel_8/API35 (attestations under `.lava-ci-evidence/2026-05-31-1.2.34-1054-challenge-matrix-repepper/`); §6.AA Stage 1 DEBUG distribute DONE (Firebase release `0f9a72d53suhg`, last-version-debug=1054); release-variant cold-start canary RESULT=PASS; `.containerignore` (NEW) excludes the real build-context bloat (4.5 GB `.git-backup*` + `releases/`). **OWED / BLOCKED (honest):** (1) lava-api-go OCI image + compose boot NOT done — no image currently exists, health probe rc=7; the "API running" item is OWED (image not needed for Firebase APK distribute). (2) §6.AA Stage 2 RELEASE distribute BLOCKED — `firebase-distribute --release-only` exits 1 at Phase-1 Gate 4 (pepper-reuse): debug+release of the SAME versionCode share one pepper, but Gate 4 rejects the reused SHA. last-version-release still 1053; release NOT distributed. Genuine Gate-4/§6.AA interaction bug to fix. **§6.J forensic:** three intermediate claims this cycle (a health-probe body; an image EXIT-0; a completed release distribute) were recorded before verification under cancelled batches; the 3 unpushed fabrication commits were `reset --mixed`, and the pushed commits `79093ccf`/`20856092` are corrected forward here (§11.4.41, no force-push). §6.L counter remains 69.
>
> **Prior (§6.L 69th, earlier same day):** rebuild + redistribute, "Firebase Distribution GREEN". **§6.Y bump:** Android 1.2.33-1053 → **1.2.34-1054**, lava-api-go 2.3.22-2322 → **2.3.23-2323**. **Rebuilt + verified:** lava-api-go binary + healthprobe (`--version` = 2.3.23 build 2323); debug + androidTest + release APKs (aapt2-confirmed versionCode 1054). **§6.AF-debt PARTIAL CLOSE (stream-D unblock):** `tools/lava-containers/vm-images.json` gained an `android-35-phone` entry whose URL parses to `tag=google_apis_playstore, abi=arm64-v8a, api=35` so the Containers `emulator-matrix` Branch-4 (`hasExtractedSystemImage`) short-circuits to the locally-installed image — the macOS host-direct+HVF Challenge matrix now BOOTS (the prior "no image with id android-35-phone" was a provisioning gap, NOT an app defect; §6.J forensic distinction recorded). **§6.Z EXECUTED green:** C00 (`Challenge00CrashSurvivalTest`, cold-start canary) + C01 (`Challenge01AppLaunchAndTrackerSelectionTest`) ran on a cold-booted Pixel_8/API35 via the Containers runner (runner=host-direct, accel=hvf, gating=true) — both `all_passed:true`, 0 failures (attestations under `.lava-ci-evidence/2026-05-31-1.2.34-1054-challenge-matrix/{c00,c01}/`; §6.Z evidence at `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.2.34-1054-test-evidence.md`). **Unit suite:** `./gradlew testDebugUnitTest` BUILD SUCCESSFUL, exit 0. **Auth rotation (operator-authorized "rotate autonomously"):** fresh `LAVA_AUTH_OBFUSCATION_PEPPER` + `LAVA_AUTH_CURRENT_CLIENT_NAME=android-1.2.34-1054` + fresh random UUID appended to `LAVA_AUTH_ACTIVE_CLIENTS` (in gitignored `.env`; values never echoed) → firebase-distribute Phase-1 Gate 4/5 pass; APKs rebuilt with new pepper into `releases/1.2.34/android-{debug,release}/` (both versionCode 1054). **Distribute HONESTLY BLOCKED — NOT bluffed:** `build_and_release.sh` exited 125 ("no space left on device") building the lava-api-go OCI image — §6.M Class-II disk pressure in the **podman VM** (host `/` ≈97% / 5.4Gi-free; image NOT needed for Firebase). Remaining before the Firebase two-stage upload: (1) reclaim podman-VM disk (`podman machine reset`), (2) build the androidTest APK (`assembleDebugAndroidTest` — build_and_release skipped it), (3) §6.Z retest C00+C01 against the **rebuilt new-pepper** artifact (prior §6.Z green was on the pre-rotation 1054 build; pepper is an auth constant inert for cold-start/launch but §6.Z requires testing the exact artifact), (4) `firebase-distribute --debug-only` then `--release-only`. Incident: `.lava-ci-evidence/sixth-law-incidents/2026-05-31-disk-pressure-podman-vm-image-build.json`. §6.L counter remains 69 (same cycle). Bash channel was degraded this session (a shell `sync` alias fired the push hook + flooded output; foreground long-sleeps spawned duplicate tasks) — mitigated via file-routed reads + dropping `sync`.
>
> **Last updated:** 2026-05-31 (§6.L 68th "do it all" forward-debt closure). Four forward-debt streams driven in parallel (subagent + main): **(A) §11.4.65 universal markdown-export CLOSED** — `CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC` gate + `scripts/sync-markdown-exports.sh` + 126-doc `.html`/`.pdf` backfill + hermetic test (commit `2c8f1d46`). **(B) canonical `workable-items` `update`/`reopen`/`block` + PDF/HTML/DOCX export ADDED upstream** in the constitution submodule (`42ad8a3`, pushed + converged on all 4 HelixConstitution mirrors via CONST-049 FF push; GitVerse remote URL repaired); parent pin `883ccc1`→`42ad8a3`. **(C) §11.4.79 codegraph own-org indexing CLOSED (LVA-6)** — the prior "gitlink capability gap" diagnosis was WRONG (§11.4.6 correction): submodules were merely un-`init`-ed; after `git submodule init`, 1,842 submodule files indexed, cross-submodule probe PASS, §6.H 0-leak, step-5 mutation confirmed. **(D) §6.AE Compose Challenge on-device matrix** re-run via host-direct+HVF (§6.X darwin/arm64 path) — IN PROGRESS at time of this write. All Lava-parent commits converged on GitHub+GitLab. Still OWED (§6.AF-debt): §11.4.85 chaos/stress beyond phase-1 (LVA-7), per-anchor propagation gates, §11.4.80 submodule-init-before-index automation. §6.L counter remains 68 (same cycle).
>
> **Prior 2026-05-31 (§6.L 68th invocation):** **flaky-test fix + §6.S table re-sync + subagent-driven constitution/ticket/submodule cycle.** Operator directive: fetch+review constitution submodule, add+incorporate any newly-mandated submodules (submodules-driven), define the tickets SQLite DB key **"LVA"** + Issues/Fixed/Issues_Summary/Fixed_Summary docs with PDF/HTML/DOCX exports, create many new tests of all types with REAL evidence (zero bluffs), keep anti-bluff mandate in all governance docs, commit+push all to all upstreams, endless autonomous loop. **Commit 1 (this commit):** (a) fixed the 67th-cycle flaky `CredentialsViewModelTest > select provider updates selectedProvider` — replaced the fixed-`awaitState()`-count assumption with a bounded await-until-`selectedProvider=="rutracker"` loop (the Room `Flow` `.first()` in `load()` resumes off the StandardTestDispatcher, so emission count is non-deterministic under load); FALSIFIABILITY-REHEARSED (broke `SelectProvider` reduce → `AssertionFailedError: expected:<rutracker> but was:<null>`, localized to that 1 test → reverted → green); (b) `.codegraph/*.pid` gitignore gap closed (`daemon.pid` was untracked-but-not-ignored — §11.4.30); (c) §0 orientation + §3 pin tables re-synced to HEAD `23c508e9` (they had drifted to `0c87b6ae`/CamelCase names/1.2.22 — a §6.S violation now corrected). **In flight (later commits this cycle):** constitution-pin review (CONST-049), missing-submodule incorporation, LVA ticket DB + export pipeline, new-test creation, full sweep. §6.L counter 67 → 68.
>
> **Last updated:** 2026-05-20 (§6.L 67th invocation), **rebuild + test cycle — honest §6.Z/§6.X blocker on the Firebase redistribute.** Operator directive: rebuild all apps/services + boot + execute all tests/Challenges + Firebase redistribute. DONE: `lava-api-go` rebuilt (`bin/lava-api-go` + `bin/healthprobe`); Lava debug APK + androidTest APK rebuilt (BUILD SUCCESSFUL); JVM unit-test suite executed (`./gradlew testDebugUnitTest --continue`) — green except ONE flaky test discovered: `CredentialsViewModelTest > select provider updates selectedProvider` failed in-suite, passed 6/6 isolated (a §11.4.50 deterministic-consistency defect — root-cause hypothesis: Room `Flow` `.first()` resuming off the virtual test dispatcher; recorded honestly at `.lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json`, fix owed as a focused follow-up, NOT masked). **§6.H credential incident:** a buggy `${LAVA_FIREBASE_TOKEN:-UNSET}` recon command printed the Firebase CI token into the session transcript (NOT committed to git) — incident at `.lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json`; **operator MUST rotate the token** (`firebase logout` → `firebase login:ci`). **§6.X-debt darwin/arm64 sub-debt RESOLVED** — the operator directed extending the emulator for per-OS acceleration; DONE (Containers `c1871138`+`6aff7ea8`: per-OS-accel model `AccelProfileForOS`/`ResolveRunner`/`GateEligibleForOS` + `emulator-matrix --runner=auto`; `scripts/run-challenge-matrix.sh` OS-aware). macOS's accelerated gate runner is host-direct+HVF (a Linux container cannot reach the host-only HVF API). PROVEN: C00 cold-start canary + the full 37-class Challenge suite ran on Pixel_8/API35 = **43 pass / 3 credential-skip (C02/C09/C10) / 0 fail** — one stale test (`Challenge26RutrackerMainAbsentFromServerListTest`, waited for the SP-4-removed "Server" menu section) repaired + falsifiability-rehearsed. The §6.Z redistribute is now genuinely unblocked on this macOS host; the redistribute itself is pending an operator decision — the app APK is byte-identical to the already-distributed 1.2.33-1053 (no app-user-facing code changed this session). §6.L counter 66 → 67.
>
> **Last updated:** 2026-05-20 (§6.L 66th invocation), **§6.N bluff-hunt — 2 more existing Lava tests verified genuine.** Continuing the §6.N cadence beyond the 65th: `core/domain/.../ProbeMirrorUseCaseTest` (UseCase layer) and `core/preferences/.../EndpointConverterTest` (converter layer) were hunted by mutating their production code — `ProbeMirrorUseCase`'s reachable range `200..399`→`200..599`, and `EndpointConverter`'s GoApi `fromJson` port forced to `DEFAULT_PORT`. Both produced localized test failures (1-of-3 and 2-of-10) and passed fully after `git checkout` revert. Verdict: both GENUINE; 0 bluffs. Across the 65th + 66th, 3 existing Lava tests (ViewModel / UseCase / converter layers) are §6.N-verified genuine. Evidence: `.lava-ci-evidence/bluff-hunt/2026-05-20-cycle66-usecase-converter.json`. §6.L counter 65 → 66.
>
> **Last updated:** 2026-05-20 (§6.L 65th invocation), **§6.N bluff-hunt — an existing Lava test verified genuine.** Per the 65th §6.L wall ("all existing tests and Challenges MUST work anti-bluff"), a §6.N.1.1 incident-response bluff-hunt of an EXISTING Lava test (not the new codegraph suite): `feature/login/.../LoginViewModelTest.kt` was hunted by actually performing its documented mutation — `serviceUnavailable = null` removed from `LoginViewModel.validateUsername`. The test FAILED with the predicted `AssertionError`, localized to exactly the mutated code path (the other 2 tests, on untouched paths, passed); mutation reverted via `git checkout`; re-run `BUILD SUCCESSFUL` 3/3. Verdict: GENUINE — the test provably catches the production break it covers; 0 bluffs found. Evidence: `.lava-ci-evidence/bluff-hunt/2026-05-20-codegraph-cycle-loginviewmodel.json`. §6.L counter 64 → 65.
>
> **Last updated:** 2026-05-20 (§6.L 64th invocation), **codegraph backend regression caught by the anti-bluff suite + fixed.** A fresh `scripts/verify-codegraph.sh --quick` run FAILED — codegraph's native `better-sqlite3` binding had been disabled (`better-sqlite3.disabled` in the Homebrew-Node-Cellar global install), forcing a WASM fallback that could not open the index DB; plus a stray `codegraph serve --mcp` process from a prior run. Fixed: re-enabled native better-sqlite3, killed the stray process, rebuilt the index via `codegraph index` (1,182 files / 18,567 nodes — its §11.4.77 regeneration mechanism), re-verified `--quick` → 44 pass / 0 fail. Hardened the suite with a `codegraph status` pre-flight check that fails fast-and-clear on this backend-breakage class; `docs/CODEGRAPH.md` troubleshooting extended. Anti-bluff mandate propagation re-audited — present in 4/4 governance files across all 17 submodules + constitution + root + lava-api-go. This is the §6.L mandate working exactly as designed: the suite refused to bluff when codegraph was genuinely broken. §6.L counter 63 → 64.
>
> **Last updated:** 2026-05-20, **codegraph code-intelligence incorporated (operator directive; §6.L 63rd invocation).** codegraph (`@colbymchenry/codegraph` 0.6.8) installed globally via npm (no sudo — §6.U). The Lava domain codebase is indexed into a local SQLite semantic graph — 1,182 files (973 Kotlin + 196 Go), 18,567 nodes, 21,462 edges; `submodules/`, `constitution/`, `releases/` and all §6.H secret paths excluded from the index (verified: 0 submodule-file leak). The codegraph MCP server is wired into all 5 supported CLI agents: Claude Code (`.mcp.json`), OpenCode (`opencode.json`), Qwen Code (`.qwen/settings.json`), Crush (`.crush.json`), Kimi CLI (`~/.kimi/mcp.json`). Anti-bluff verification suite `scripts/verify-codegraph.sh` + `tests/codegraph/` (6 layers): layers 01-04 + 06 PASS — index reality, query correctness, MCP-protocol JSON-RPC, all-5-agent connectivity, and falsifiability (layers 01-03 provably FAIL when the DB is removed, PASS when restored). Layer 05 (LLM-driven E2E) — Claude Code PASS (reports the unforgeable index node count, obtainable only by calling the `codegraph_status` MCP tool); OpenCode / Kimi CLI / Crush SKIP (documented credential/quota gaps in this environment — no Google API key / Kimi monthly quota exhausted / Venice.ai account has no credits — these are NOT codegraph defects; the integration itself is proven by layer 04). `docs/CODEGRAPH.md` written; design spec `docs/superpowers/specs/2026-05-20-codegraph-incorporation-design.md`. `QWEN.md` (Qwen Code instruction file — a plain-text pointer to `CLAUDE.md`, deliberately zero `@`-tokens so Qwen Code's import processor does not choke) created across repo root + 18 submodules + `lava-api-go`. constitution submodule fetched+pulled `9b52046` -> `2456605` (CONST-049 step 1). **§11.4.78 CodeGraph mandate — FULL ECOSYSTEM CASCADE COMPLETE.** §11.4.78 (CodeGraph code-intelligence mandate) authored into the constitution submodule: `Constitution.md` + mirrored into `CLAUDE.md` / `AGENTS.md` / `QWEN.md`; all 16 governance artefacts (.md + regenerated .html + .pdf + .docx) updated; constitution `2456605` → `208e2c8` pushed to all 4 upstreams (gitflic, github, gitlab, gitverse) per CONST-049. The §11.4.78 anchor cascaded into all 17 owned submodules' `CONSTITUTION.md` / `CLAUDE.md` / `AGENTS.md`, with `QWEN.md` created for each — every submodule committed + pushed to GitHub + GitLab (helixqa to its single GitHub upstream). §11.4.78 also appended to `lava-api-go`'s `CLAUDE.md` / `AGENTS.md` / `CONSTITUTION.md` / `QWEN.md`. All 18 submodule pins bumped in the parent. QWEN.md anchors are zero-`@` so Qwen Code's import processor does not choke.**
>
> **Last updated:** 2026-05-18, **§6.AD-debt FULLY DRAINED in 1.2.30-1050 tooling cycle. All three originally-OWED CM-* items CLOSED this session: `CM-SCRIPT-DOCS-SYNC` (commit `11820734`), `CM-COMMIT-DOCS-EXISTS` (commit `977630c3`), `CM-SUBAGENT-DELEGATION-AUDIT` (commit `2a0e11f4`). Each ships with standalone scanner + 7-or-8-fixture hermetic test + companion `docs/scripts/*.sh.md` user guide + wrapper integration + Bluff-Audit falsifiability rehearsal. The 5 Path-B equivalence-mapped items remain CLOSED-BY-EQUIVALENCE per §6.AD.3. Plus: T7 USB disk migration today (5G → 108G free on main; 7 dirs symlinked to T7 incl. ~/.gradle, ~/.cache, ~/.android, Xcode; `~/.zshrc` updated with `GRADLE_USER_HOME=/Volumes/T7/Gradle`, `XDG_CACHE_HOME`, `NPM_CONFIG_CACHE`).**
>
> **Sweep tier-A closure (2026-05-17 evening, branch `sweep-findings-tier-A-2026-05-17`):** 8 of the 10 comprehensive-sweep findings closed in a single coordinated commit (Findings #2 + #3 already closed by Bug 2 cascade + Bug 3 fix in prior cycles). All fixes falsifiability-rehearsed per §6.J / Seventh Law clause 1 (mutation applied → test fails with clear message → mutation reverted → test passes). Bluff-Audit stamps recorded in commit body.
>   - **Finding #1 (P0) — ToggleAnonymous persistence**: `feature/provider_config/.../ProviderConfigViewModel.kt` now persists via new `ProviderConfigRepository.setUseAnonymous(...)` → Room column `use_anonymous` (Migration 10→11 + schema 11.json). Switch state survives process restart.
>   - **Finding #4 (P1) — `LoginViewModel.serviceUnavailable` retype clear**: cleared in `validateUsername`/`validatePassword`/`validateCaptcha`/`onReloadCaptchaClick`/`onSubmitClick` reduces.
>   - **Finding #5 (P1) — `LoginViewModel.ServiceUnavailable` stale-captcha clear**: branch now sets `captcha = null, captchaInput = Initial` so the rendered challenge image doesn't lie when the sid expires.
>   - **Finding #6 (P1) — `ProviderLoginViewModel.serviceUnavailable` clear across selectProvider/backToProviders + retype**: symmetric fix to Findings #4/#5 on the multi-provider login surface.
>   - **Finding #7 (P1) — `OnboardingViewModel.onTestAndContinue` no more misleading "Invalid credentials"**: distinguishes `loginResult == null` (tracker has no auth path) from `loginResult.state != Authenticated` (real auth failure). Null path now treated as anonymous → switchTracker + advance.
>   - **Finding #8 (P1) — `OnboardingViewModel.loadProviders` excludes cloned synthetic trackers**: filters by syntheticId membership in `cloned_provider`. Clones remain configurable via Provider Config.
>   - **Finding #9 (P2) — `MainActivity` onboardingComplete re-read**: `PreferencesStorage.observeOnboardingComplete()` new Flow API (SharedPreferences-listener-backed). Two parallel `lifecycleScope.launch { repeatOnLifecycle { collect } }` blocks (one for theme, one for onboarding). Welcome screen re-appears if settings flip onboardingComplete back to false at runtime.
>   - **Finding #10 (P2) — `ToggleSync` first-tap race**: reads `toggleDao.get(providerId)?.enabled` synchronously instead of `state.syncEnabled`. First tap before observeAll() emit no longer silently flips the wrong direction.
>   - **Tests added** (5 new): `LoginViewModelTest` (3 cases: Findings #4 username, #4 password, #5 captcha), `ProviderConfigViewModelTest` (2 cases: Findings #1 + #10), Finding #7 + #8 cases added to existing `OnboardingViewModelTest`, Finding #6 case added to existing `ProviderLoginViewModelTest`.
>   - **Schema migration**: Room version bumped 10 → 11 (`MIGRATION_10_11` adds `use_anonymous INTEGER NOT NULL DEFAULT 0` to `provider_configs`). `core/database/schemas/lava.database.AppDatabase/11.json` exported by KSP.
>   - **All builds + tests green**: `:core:database` / `:core:credentials` / `:feature:provider_config` / `:feature:login` / `:feature:onboarding` / `:app:assembleDebug` / `:app:compileDebugAndroidTestKotlin`.
>
> **1.2.25-1045 distribute cycle (2026-05-17 afternoon):**
>   - Stage-1 debug Firebase release ID `1lfjqc1nnhuio` on `digital.vasic.lava.client.dev`
>   - Stage-2 release Firebase release ID `7p0h5j70eckqg` on `digital.vasic.lava.client` (production)
>   - 15/15 Compose UI Challenge test cases PASS on Pixel_8/API35 host-direct AVD (14 classes including new C36)
>   - HelixConstitution submodule advanced to `ca7c7d7` (§11.4.10.A Pre-store credential leak audit + upstream §11.4.37/38/39) — 5-mirror converged
>   - §6.L counter 57 → 58
>
> **Comprehensive UI/UX/core sweep findings (2026-05-17 evening, branch `comprehensive-sweep-2026-05-17` merged at `c3b8bf5c`):** 10 findings @ `docs/sweeps/2026-05-17-comprehensive-uiux-core-sweep.md`. Top P0s for 1.2.26:
>   1. `ProviderConfigViewModel.ToggleAnonymous` never persists — anonymous switch reverts on restart (`feature/provider_config/.../ProviderConfigViewModel.kt:82-84`)
>   2. `SearchResultContent` has no `Error` variant — Bug 2 root cause CONFIRMED ("all providers failed" looks like "0 results") (`feature/search_result/.../SearchPageState.kt:30-54`)
>   3. `SearchInputViewModel.availableProviders` is hardcoded 4-element list — SDK clones + new trackers invisible to search (`feature/search_input/.../SearchInputViewModel.kt:48-53`)
> P1 cluster (4 findings on login banner/captcha staleness + onboarding misleading cred message + clones-in-onboarding) + P2 cluster (MainActivity onboarding re-read + ToggleSync race). See sweep doc for full details + per-finding anti-bluff classification.
>
> **OPERATOR ACTION still REQUIRED:**
>   - §6.H historical credential leak: rotate the RuTracker password (credentials remain valid until rotated; in-tree redaction does NOT purge git history per §6.T.3)
>   - Bug 2 live-device log capture: install 1.2.25, attempt anonymous-only search, `adb logcat | grep -iE "error|exception|search"` to confirm sweep finding #2 root cause
>
> **Bug 1 FULL REFACTOR LANDED — `AuthResponseDto.ServiceUnavailable` sealed variant propagates through 8-layer chain; Challenge C36 + 3 unit-layer falsifiability-rehearsed tests; OpenAPI spec + Go bindings regenerated; debug APK builds clean; feature/login + core/tracker/rutracker tests green**
>
> **Bug 1 cycle (2026-05-17):**
>   - Operator's §6.L 57th invocation forensic anchor "Cant login to RuTracker with valid credentials" closed. Partial fix in commit `17ceabcb` (stderr marker line) is now superseded by the full-fix variant: the SDK catch path returns `AuthResponseDto.ServiceUnavailable(reason)` instead of bluffing `WrongCredits(null)`.
>   - **8-layer propagation chain**: `AuthResponseDto` (+ variant) → `AuthMapper` → `AuthState.ServiceUnavailable(reason)` → `RuTrackerDtoMappers` (reverse) → `AuthServiceImpl` → `AuthResult.ServiceUnavailable` → `LoginUseCase` → `LoginResultMapper` → `ProviderLoginViewModel` (recordWarning telemetry per §6.AC + state field) → `ProviderLoginState.serviceUnavailable` → `ProviderLoginScreen` (renders "Service unavailable. Please try again later. (reason)" banner with `R.string.provider_login_service_unavailable` + `ServiceUnavailableTextTestTag`).
>   - Same wiring landed in legacy `LoginViewModel` + `LoginState.serviceUnavailable` for the single-tracker path.
>   - **OpenAPI spec**: `lava-api-go/api/openapi.yaml` gains `AuthResponseDtoServiceUnavailable` under `oneOf` + discriminator mapping. Go bindings regenerated via `scripts/generate.sh`; both `internal/gen/server/api.gen.go` + `internal/gen/client/api.gen.go` updated. Note: the Go-side rutracker scraper does NOT emit the new variant today — its login handler still returns Success / WrongCredits / CaptchaRequired only; the parity test holds. The variant exists in the spec so the Android Kotlin SDK wire-shape stays compatible with future Go-side adoption.
>   - **Tests added** (3 new + 1 rewritten):
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/mapper/AuthMapperTest.kt` — added 2 tests for ServiceUnavailable forward mapping (with + without captcha).
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/mapper/RuTrackerDtoMappersTest.kt` — added round-trip test asserting reverse-mapper preserves reason.
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/impl/RuTrackerNetworkApiLoginUnknownRegressionTest.kt` — REWRITTEN: pre-fix asserted WrongCredits fallback (the §6.J bluff); now asserts ServiceUnavailable with reason carrying throwable class name. Same Crashlytics `a29412cf6566d0a71b06df416610be57` regression-immunity coverage, stronger discrimination.
>       - `feature/login/src/test/kotlin/lava/login/LoginResultMapperTest.kt` — NEW file. 6 tests; load-bearing assertion: `service unavailable propagates as ServiceUnavailable with reason` + explicit anti-bluff `service unavailable does NOT silently collapse to WrongCredits`.
>       - `feature/login/src/test/kotlin/lava/login/ProviderLoginViewModelTest.kt` — added VM test: `service unavailable shows banner does NOT mark creds Invalid does NOT signal authorized` exercises the §6.J anti-bluff contract end-to-end at the VM layer (real ViewModel + real ProviderCredentialManager + real Room + real LavaTrackerSdk wired with FakeTrackerClient whose `loginProvider` returns `AuthState.ServiceUnavailable(reason)`).
>       - `app/src/androidTest/kotlin/lava/app/challenges/Challenge36LoginServiceUnavailableShowsAccurateMessageTest.kt` — NEW Challenge Test under `// covers-feature: login`. Source-written + compiles green on darwin/arm64 (verified via `./gradlew :app:compileDebugAndroidTestKotlin`); EXECUTION against the §6.X-mounted gate-host is OWED.
>   - **Build verification**: `./gradlew :core:tracker:rutracker:test` PASS, `./gradlew :feature:login:test` PASS, `./gradlew :app:assembleDebug` PASS, `./gradlew :app:compileDebugAndroidTestKotlin` PASS. Spotless applied to all edited files.
>   - **§6.Y note**: this is a follow-up commit to 1.2.24-1044 — versionCode is NOT bumped here; the next distribute (1.2.25-1045) will pick up Bug 1 full fix + Bug 2 deferred investigation closure once that lands.
>   - **§6.X-debt**: C36 + the per-AVD matrix attestation row is OWED to a Linux x86_64 + KVM gate-host; the JVM-layer falsifiability rehearsals (4 tests) carry the gate until then.
>
> **Previous cycle context (preserved verbatim):**
>
> **Last updated:** 2026-05-16, **Phase 4-C-2 (pkg/detector adapter) + Phase 4-C-3 (pkg/ticket adapter) — BOTH WORKTREES LANDED + green; parent-cycle close + HelixQA SHA bump + coverage-ledger regen owed at meta-merge**
> (constitutional-plumbing-only; no user-visible feature change; no Firebase
> distribute since 1.2.22-1042 still serves the user-visible surface). Cycle
> spans commits `4def2da7` → `0c87b6ae` (33 commits since plan landing
> `832f739e`). Final state at HEAD `0c87b6ae`:
>
> **Major deliverables this cycle:**
>   - The 12-clause constitution-compliance plan (`docs/plans/2026-05-15-constitution-compliance.md`) executed end-to-end across 10 phases. Plan + every phase's deliverable: see commit log between `832f739e..0c87b6ae`.
>   - HelixQA submodule incorporated (`submodules/helixqa` at upstream `b13ba7c0`) — see Phase 4 below.
>   - 40-gate verify-all sweep wrapper at full STRICT mode after Phase 7's coverage-ledger STRICT-flip.
>   - 17 own-org submodules now have helix-deps.yaml + install_upstreams.sh (16 vasic-digital + 1 HelixDevelopment HelixQA); 0 waivers in STRICT mode.
>   - §6.L counter advanced 36 → 52 across 17 back-to-back restatements (longest sequence in project history); 53rd in-flight per the dispatch that triggered this CONTINUATION.md refresh task.
>
> **Phase-by-phase status (constitution-compliance plan):**
>   - ✅ **Phase 1** (§11.4.32 enforcement engine) — `4def2da7`. `scripts/verify-all-constitution-rules.sh` + meta-test + ci.sh wiring.
>   - ✅ **Phase 2** (§11.4.30 .gitignore audit gate) — `037389f5`. `scripts/check-gitignore-coverage.sh` + 16 new .gitignore files + sweep wiring + hermetic test.
>   - ✅ **Phase 3** (§11.4.31 helix-deps.yaml manifest gate) — `43345c3e` (gate) + `410af7ec` + `bcba3a19` (16/16 per-submodule manifests landed + Auth pin bump).
>   - ✅ **Phase 3-debt** CLOSED — all 16 vasic-digital submodules at pin advance with helix-deps.yaml present.
>   - ✅ **Phase 4** (§11.4.27 HelixQA + 100% test-type coverage) — `aa0db6bd`. HelixQA adopted as `submodules/helixqa` at upstream HEAD; `HELIX_DEV_OWNED` exemption pattern added to mirror-mandate scanners.
>   - ✅ **Phase 4 follow-up A** (Option 1 design) — `a61bd3d8`. Integration design at `docs/plans/2026-05-16-helixqa-integration-design.md` (Option 1 shell-wiring recommended; Options 2/3 deferred).
>   - ✅ **Phase 4 follow-up A executed** (shell-level wiring) — `1b66d192` + merge `d94ade0d`. 11 HelixQA Challenge scripts wrapped via `scripts/run-helixqa-challenges.sh` + `scripts/run-challenge-matrix.sh --include-helixqa` opt-in flag.
>   - ✅ **Phase 4 follow-up B** (4 open-question resolutions for Option 1) — `281780d7` + merge `84d871a5`. Runner-mode flag (`--runner=host|containerized`), toolchain-precondition gate (`HELIXQA_TOOLCHAIN_MAP`), evidence-dir env-var override, `HELIXQA_W_EXCLUSIONS` array consuming the §6.W audit doc. 11 hermetic fixtures in `tests/check-constitution/test_helixqa_wiring.sh`.
>   - 📐 **Phase 4 follow-up C** (DESIGN-ONLY) — `41b81359` + merge `be1ca3d8`. HelixQA Go-package linking design at `docs/plans/2026-05-16-helixqa-go-package-linking-design.md` (770-line Option 2 proposal: per-package adapters + 4-cycle rollout 4-C-1 `pkg/evidence` → 4-C-4 `pkg/navigator`+`pkg/validator`). **Operator-blocked on 10 open questions** (§G of the design); implementation cycle NOT started.
>   - ✅ **Phase 4-debt** CLOSED — `858ffb3e` (2026-05-16). HelixQA upstream PR `b13ba7c` landed `helix-deps.yaml` + `install_upstreams.sh` at the HelixQA repo root → Lava parent removed HelixQA from `HELIX_DEPS_WAIVERS` + `INSTALL_UPSTREAMS_WAIVERS`. 17/17 own-org submodules satisfy §11.4.31 + §11.4.35 + §11.4.36 in fully STRICT mode with **zero waivers**.
>   - ✅ **Phase 5** (§11.4.28 nested own-org submodule audit) — `bbca3a78` (gate) + `410af7ec` (STRICT flip after Challenges/.gitmodules removal via the github cascade merge).
>   - ✅ **Phase 5-debt** CLOSED — `410af7ec`. Scanner reports 0 violations in STRICT mode; Panoptic is no longer nested via Challenges.
>   - ✅ **Phase 6** (§11.4.29 lowercase snake_case naming) — `322f2081` (plan landing) + Phase 6a + 6b execution this cycle. Operator's 8 Q answers: defer Phase 6f upstream rename (Lava-side only); `helixqa` (single-token); `http3` (single-token); `ratelimiter` (single-token); Go `cmd/` hyphens exempt; ordering Mdns(low) → Containers(high) last; same defer for Tracker-SDK upstream; Phase 6a authorized to run in parallel with Phase 4-C-1. Execution: `Submodules/` → `submodules/` parent rename + 17 child renames (Auth→auth, Cache→cache, Challenges→challenges, Concurrency→concurrency, Config→config, Containers→containers, Database→database, Discovery→discovery, HelixQA→helixqa, HTTP3→http3, Mdns→mdns, Middleware→middleware, Observability→observability, RateLimiter→ratelimiter, Recovery→recovery, Security→security, Tracker-SDK→tracker_sdk) + 139 referencing files updated + `.gitmodules` rewritten + Phase 6f upstream-rename execution plan at `docs/plans/2026-05-16-phase6f-upstream-rename-execution.md` (DEFERRED per Q1). Audit confirms 0 stale `Submodules/X` references in 16/17 names (Tracker-SDK has 1 ref in a historical bluff-hunt JSON narrative quote — exempt forensic anchor).
>   - ✅ **Phase 7** (§11.4.25 coverage ledger) — `21dee741` + merge `c35af27c` (generator + verifier + 58-row baseline + 6 hermetic fixtures + sweep wiring, advisory at first) → `76507ca0` + merge `20b3fd36` (waiver backfill: 0 covered / 20 partial / 38 gap → 48 covered / 10 partial / 0 gap) → `0c87b6ae` (STRICT-flip in sweep wrapper).
>   - ✅ **Phase 7-debt** CLOSED — `0c87b6ae`. Sweep wrapper now invokes `check-coverage-ledger.sh --strict` (was `--advisory`); gate hard-fails on stale/missing rows.
>   - ✅ **Phase 8** (§11.4.35 canonical-root + §11.4.36 install_upstreams) — `d95be689` (gate) + `410af7ec` (STRICT-flip after 10 install_upstreams scripts landed across owned submodules).
>   - ✅ **Phase 8-debt** CLOSED — `858ffb3e`. HelixQA's upstream `install_upstreams.sh` is the final hold-out; scanner reports 17/17 install_upstreams present in STRICT mode.
>   - ✅ **Phase 9 Path B** (§11.4.33 + §11.4.34 equivalence-mapping) — `055fbcbe`. §6.AD.3 amended: Lava's CONTINUATION + closure-logs + sixth-law-incidents satisfy the type-aware-closure + reopened-source-attribution semantics; no parallel Issues/Fixed tracker; equivalence is binding. Gates-index gets 2 new EQUIVALENCE-MAPPED rows (CM-CLOSURE-STATUS-VOCAB-COMPLIANCE, CM-REOPENED-SOURCE-ATTRIBUTION).
>
> **Verify-all sweep result at HEAD `0c87b6ae`:** **40/40 PASS in fully STRICT mode** (post-STRICT-flip; the prior 07:00:56Z attestation showed 39/40 only because the coverage-ledger sha drifted between commit and sweep — the re-run at HEAD is clean). Attestation directory: `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`.
>
> **CM-* gates inventory at HEAD:** 24 HelixConstitution `CM-*` gates tracked at `docs/helix-constitution-gates.md`; ~16 wired + ~8 paper-only (mostly Issues/Fixed-tracker-dependent — equivalence-mapped per §6.AD.3). 14 Lava-side anti-bluff gates also active.
>
> **All 33 session commits §6.C-converged on GitHub + GitLab.** Pre-push Checks 1-9 active throughout.
>
> **Prior:** 2026-05-14, **1.2.23-1043 / 2.3.12-2312 closure-cycle**
> (constitutional-plumbing-only; no user-visible feature change). HelixConstitution
> submodule incorporated + §6.AD HelixConstitution-Inheritance Mandate landed;
> 8-track §6.AD-debt opened and systematically closed across 14 commits.
> §6.AC + §6.AB scanners in STRICT mode. Build-resource stats tracker
> (§11.4.24) landed. All Lava-side debts in scope CLOSED at commit `4a7d0402`.
> See git log `66de343b..4a7d0402` for the full closure-cycle.
>
> **Prior:** 2026-05-14, **1.2.22-1042 / 2.3.11-2311 DISTRIBUTED to Firebase**
> (debug stage 1 + release stage 2, operator pre-authorized combined).
> About dialog author re-order; Crashlytics 6-issue sweep (3 fixed + 3
> closed-historical); §6.AC Comprehensive Non-Fatal Telemetry Mandate
> added (28th §6.L); §6.AA-debt PARTIAL CLOSE; per-channel
> last-version-{debug,release} pointers; both APIs running 2.3.11.
>
> **Prior:** 2026-05-14, 1.2.21-1041 DISTRIBUTED (onboarding back-press
> fix + WelcomeStep colored Image fix; §6.AB Anti-Bluff Test-Suite
> Reinforcement, 27th §6.L).
>
> **Prior:** 2026-05-14, 1.2.20-1040 DISTRIBUTED (Galaxy S23 Ultra
> cold-launch crash fix: `ic_lava_logo` layer-list → composited PNG;
> §6.Z Anti-Bluff Distribute Guard, 26th §6.L).
>
> **Prior:** 2026-05-14, 1.2.19-1039 DISTRIBUTED (§6.Y Post-Distribution
> Version Bump Mandate, 25th §6.L) + 1.2.18-1038 DISTRIBUTED (24th §6.L).
>
> **Prior:** 2026-05-13, SP-4 Phase G + Phase F.1 + F.2 + Phase D (multi-
> provider parallel search SDK) + Phase C (Trackers screen removal +
> :feature:provider_config landing). See git log `0c87b6ae..` and the
> CHANGELOG for the full delivery chain.
>
> **§6.S binding:** this file is constitutionally load-bearing per
> root `CLAUDE.md` §6.S. Every commit that changes phase status,
> lands a new spec/plan, bumps a submodule pin, ships a release
> artifact, discovers or resolves a known issue, or implements an
> operator scope directive MUST update this file in the SAME
> COMMIT. The §0 "Last updated" line MUST track HEAD. Stale
> CONTINUATION.md is itself a §6.J spirit issue under §6.L's
> repeated mandate. `scripts/check-constitution.sh` enforces
> presence + structure (§0, §7, §6.S clause + inheritance).

---

## 0. Quick orientation (read this first)

| Surface | Current state | Pin |
|---|---|---|
| Lava parent on master | 2 mirrors (GitHub + GitLab) converged at HEAD | §6.L 68th cycle (see `git log` for current SHA) |
| API (lava-api-go) | 2.3.22 (code 2322) — `internal/version/version.go` | container `lava-api-go-thinker` |
| Android Firebase | 1.2.33 (1053) distributed to testers (2026-05-18, last user-visible release; `last-version-{debug,release}` both = 1053) | `lava-vasic-digital` Firebase project |
| 17 own-org submodules | all pushed (16 vasic-digital + 1 HelixDevelopment HelixQA) | see §3 |
| constitution submodule | at upstream HEAD `883ccc1` (§11.4.79–§11.4.106 adopted via §6.AF) | HelixDevelopment/HelixConstitution |
| Workable-items tracker | canonical `workable-items` binary + `docs/workable_items.db` (tracked, 8 items LVA-1..8; LVA-3 migrated, LVA-tickets retired) | §11.4.93/95/106 + §11.4.74 |
| codegraph | incorporated 2026-05-20 (§11.4.78); local SQLite index at `.codegraph/` (1,182 files / 18,567 nodes) | `@colbymchenry/codegraph` MCP |
| Verify-all sweep | 40/40 PASS, fully STRICT mode (last attested prior cycle; 68th-cycle re-run in progress) | `.lava-ci-evidence/verify-all/` |
| Coverage ledger | 48 covered / 10 partial / 0 gap (58 rows) | `docs/coverage-ledger.yaml` |
| CM-* gates wired | ~16 of 24 wired (8 paper-only or equivalence-mapped) | `docs/helix-constitution-gates.md` |

This cycle delivered the entire 12-clause constitution-compliance plan plus HelixQA submodule adoption plus the Phase 7 STRICT-flip. **No user-visible feature change**; constitutional-plumbing-only.

---

## 1. What's DONE (this cycle, since 2026-05-15)

### Constitution-compliance plan (`docs/plans/2026-05-15-constitution-compliance.md`)

| Phase | Subject | Status | Anchor commits |
|---|---|---|---|
| 0 | Pin advance + plan land | ✅ DONE | `ed16debd` (pin) + `832f739e` (plan) |
| 1 | §11.4.32 enforcement engine | ✅ DONE | `4def2da7` |
| 2 | §11.4.30 .gitignore audit gate | ✅ DONE | `037389f5` |
| 3 | §11.4.31 helix-deps.yaml manifests | ✅ DONE | `43345c3e` + `410af7ec` + `bcba3a19` |
| 3-debt | 16/16 per-submodule manifests | ✅ CLOSED | `410af7ec` + per-submodule pin advances |
| 4 | §11.4.27 HelixQA + 100% test-type coverage | ✅ DONE | `aa0db6bd` |
| 4 follow-up A | Option 1 design + shell wiring | ✅ DONE | `a61bd3d8` + `1b66d192` + merge `d94ade0d` |
| 4 follow-up B | 4 open-question resolutions | ✅ DONE | `281780d7` + merge `84d871a5` |
| 4 follow-up C | HelixQA Go-package linking design | 📐 DESIGN-ONLY | `41b81359` + merge `be1ca3d8` |
| 4-C-1 | Lava-side `pkg/evidence` adapter (WRAP) | ✅ DONE 2026-05-16 | HelixQA `a1e2020d` + Lava `573b4a8a` |
| 4-C-2 | Lava-side `pkg/detector` adapter (WRAP) | ✅ DONE 2026-05-16 | HelixQA `a1e2020d` unchanged + Lava `<this-commit>` |
| 4-debt | HelixQA upstream install_upstreams.sh + helix-deps.yaml | ✅ CLOSED 2026-05-16 | `858ffb3e` |
| 5 | §11.4.28 nested-own-org submodule audit | ✅ DONE | `bbca3a78` |
| 5-debt | STRICT flip after Challenges/.gitmodules removal | ✅ CLOSED | `410af7ec` |
| 6 | §11.4.29 lowercase snake_case naming | 📐 PLAN-ONLY | `322f2081` + merge `c8d42434` |
| 7 | §11.4.25 coverage ledger | ✅ DONE | `21dee741` + merge `c35af27c` |
| 7-debt | waiver backfill + STRICT flip | ✅ CLOSED | `76507ca0` + merge `20b3fd36` + `0c87b6ae` |
| 8 | §11.4.35 + §11.4.36 canonical-root + install_upstreams | ✅ DONE | `d95be689` + `410af7ec` |
| 8-debt | 10 install_upstreams scripts across owned submodules | ✅ CLOSED | `410af7ec` |
| 9 Path B | §11.4.33 + §11.4.34 equivalence-mapping | ✅ DONE | `055fbcbe` |

### HelixQA submodule (NEW this cycle)

- Adopted at `submodules/helixqa` from `git@github.com:HelixDevelopment/HelixQA.git`.
- Initial pin: `403603db` (2026-05-15 in Phase 4).
- Upstream PR `b13ba7c` added `helix-deps.yaml` + `install_upstreams.sh` (2026-05-16 in Phase 4-debt closure).
- Current pin: `b13ba7c0`.
- 11 HelixQA Challenge scripts wrapped via `scripts/run-helixqa-challenges.sh` (Option 1 shell-level wiring).
- 11 hermetic fixtures at `tests/check-constitution/test_helixqa_wiring.sh` validate the wrapper.
- §6.W audit doc at `docs/helixqa-script-audit.md` (per-script git-push / curl / outside-worktree analysis; 0/11 violators on default config).
- `HELIX_DEV_OWNED` exemption pattern in `scripts/check-canonical-root-and-upstreams.sh` + `scripts/check-helix-deps-manifest.sh` (HelixDevelopment org submodules treated like vasic-digital for mirror-presence checks, distinct from arbitrary third-party submodules).

### §6.L counter advance

The §6.L Anti-Bluff Functional Reality Mandate counter advanced from 36 to 52 across the cycle (53rd is in-flight per the dispatch that triggered this CONTINUATION.md refresh task). 17-cycle back-to-back restatement is the longest sequence in project history; per §6.L the repetition itself is the constitutional record. Anchor commits: `8c47cd17`, `d159d0fc` (37-41 batched), `66803d4d` (43+44 batched), `aa0db6bd` (45), `a61bd3d8` (46), `ed7a658d` (47), `dcec9eb8` (48), `0f1b19f1` (49), `2882304b` (50+51), `0c87b6ae` (52).

### On-device Lava API sub-project (2026-06-02)

The *Lava API Android app* sub-project (spec `docs/superpowers/specs/2026-06-02-lava-api-android-app-design.md`, plan `docs/superpowers/plans/2026-06-02-lava-api-android-app.md`) progress:

| Phase | Subject | Status | Anchor commit |
|---|---|---|---|
| A | additive SQLite storage backend (`LAVA_API_STORAGE_BACKEND`; Postgres default unchanged) | ✅ DONE | — |
| B | `internal/mobile` `Start`/`Stop`/`Status` embed + `go build -buildmode=c-shared` + JNI bridge | ✅ DONE | `816d983f` (docs) |
| C | `:core:apiengine` Kotlin JNI wrapper (`ApiEngine`/`NativeApiEngine`/`FakeApiEngine`) + `buildCshared`→jniLibs→`externalNativeBuild` pipeline | ✅ DONE | `ef109760` |
| D-infra | `:api-app` module — foreground `ApiEngineService` (Wifi/Multicast/Wake locks), `ApiEngineController` state machine, `NsdMdnsAdvertiser`, `ApiKeyStore` (EncryptedSharedPreferences) | ✅ DONE | `e62e0fa8` |
| D-ui | landing UI + control screen + ViewModel (`MainActivity` is a placeholder today) | ⏳ PENDING | — |
| E | instrumented Compose UI Challenge tests (boot embed on a real emulator, real HTTPS request) | ⏳ PENDING | — |
| SP-2 | client-side distinct labelling of `platform=android` instances in the discovery list | ⏳ PENDING | — |

Docs covering the landed surface: `docs/ON_DEVICE_API.md` (§4A Phase C, §4B Phase D-infra), `docs/guides/ON_DEVICE_API_USER_GUIDE.md`. New modules in `settings.gradle.kts`: `:core:apiengine`, `:api-app`. `:api-app` reuses `:app`'s `.env`-driven signing; `gomobile bind` is blocked (relative `replace ../submodules/*`), c-shared is the chosen native path.

---

## 2. What's BLOCKED ON OPERATOR ACTION

These items need the operator's environment / hardware / decisions
that an agent cannot make alone.

### 2.1 Phase 4 follow-up C (HelixQA Go-package linking) — STATUS UPDATE 2026-05-16

**Phase 4-C-1 (pkg/evidence adapter): COMPLETED 2026-05-16.** All 10 open questions answered by operator + implementation landed in this cycle's commit. Operator decisions: Q1 Go 1.26 bump, Q2 WRAP, Q3 Path A tag-pin (transitional Path B replace+sibling-mount until HelixQA stabilizes), Q4 preserve HelixQA terminology (Collector / Detector / Generator), Q5 upstream-contribute CaptureGeneric first (HelixDevelopment/HelixQA PR #1, branch `feat/evidence-capture-generic`, commit `a1e2020dd759d025b67ef8e024061b103940470d`), Q6 SKIP 4-C-4 navigator entirely, Q7 NO recover() wrapping, Q8 accept 2x CI build-time delta, Q9 always-track-upstream for HelixQA (§6.AD waiver documented in CLAUDE.md), Q10 coverage-ledger bumped in same commit.

Deliverables this cycle:
- HelixQA `pkg/evidence.CaptureGeneric` public method (commit `a1e2020d`, PR HelixDevelopment/HelixQA#1)
- `lava-api-go/internal/qa/evidence/{collector,collector_test}.go` (adapter + 9 unit tests, 87.9% coverage)
- `lava-api-go/tests/qa/evidence_test.go` (real-stack integration test, `//go:build helixqa_realstack`)
- `lava-api-go/go.mod` bumped to Go 1.26 + adds `digital.vasic.helixqa` require + replace
- `Submodules/HelixQA/` pin bumped to `a1e2020d`
- `docs/coverage-ledger.yaml` regenerated (58 rows; lava-api-go row: 89 unit tests + 1 integration)
- `CLAUDE.md` §6.AD-debt: HelixQA always-track-upstream waiver documented
- This `CONTINUATION.md` updated

Phase 4-C-2 (detector adapter), 4-C-3 (ticket adapter), 4-C-4 (validator + SKIP navigator per Q6) remain owed — each is 1-session scope per design doc §E.

**Phase 4-C-2 (pkg/detector adapter): COMPLETED 2026-05-16.** Operator decisions reused from 4-C-1 (Q1–Q10 unchanged). Q4 preserves `Detector` name. Q5: no HelixQA-side promotion needed — `pkg/detector`'s public API surface already exposes everything the adapter requires (`Detector`, `Option`, `New`, `WithDevice`/`WithPackageName`/`WithBrowserURL`/`WithProcessName`/`WithProcessPID`/`WithEvidenceDir`/`WithCommandRunner`, `Check`, `CheckApp`, `Platform`, `DetectionResult`, `CommandRunner` interface).

Deliverables this cycle:
- `lava-api-go/internal/qa/detector/detector.go` (255 LOC) — WRAP-strategy adapter exposing Lava-shaped `Report` struct (Crashed/Alive/StackTrace/EvidencePath); `CheckGoProcess(processName)` for name-based detection (real `pgrep -f`); `CheckGoProcessByPID(pid)` for PID-based detection (real `kill -0`); `ErrEmptyProcessName` + `ErrInvalidPID` Lava-side guards that block HelixQA's silent fallback to `processName="java"` for empty/zero inputs (forensic value: mutation rehearsal proved Alive=true would silently be returned because Java exists on every dev box)
- `lava-api-go/internal/qa/detector/detector_test.go` (11 tests, 82.9% statement coverage, race-clean) — uses HelixQA's `CommandRunner` interface as the boundary-fake (not a mock of the SUT; the HelixQA Detector itself runs unaltered, satisfying §6.J.4 forbidden-mock pattern)
- `lava-api-go/tests/qa/detector_test.go` (3 real-stack tests, `//go:build helixqa_realstack`) — spawns sacrificial `sleep` child processes via `sh -c 'exec -a <sentinel> sleep 30'` (BSD/macOS-compatible argv[0] injection); asserts the REAL HelixQA Detector against the real OS process table
- `docs/coverage-ledger.yaml` regenerated (lava-api-go unit_test_count 89 → 93)
- This `CONTINUATION.md` updated; coverage-ledger regen confirmed (58 rows preserved)

§6.J anti-bluff posture: 4 falsifiability rehearsals captured in commit body — (1) `Crashed: dr.HasCrash → !dr.HasCrash` triggered 5 unit-test assertions; (2) empty-name guard removal caught at compile time (`"strings" imported and not used`); (3) PID guard removal triggered 2 sub-test assertions + exposed HelixQA's silent `java` fallback; (4) `Alive: dr.ProcessAlive → !dr.ProcessAlive` triggered ALL 3 real-stack assertions on live/dead/ghost process paths. All reverted, all green after revert.

Honest scope statement: real-stack tests were initially executed via `go test -tags=helixqa_realstack ./tests/qa/detector_test.go` (file-target form) while Phase 4-C-3's `internal/qa/ticket/generator.go` was untracked + uncompiling. After 4-C-3 landed at `86402bfa` (which also bumped HelixQA pin `a1e2020d` → `c57c275` to gate `enhanced_generator.go` behind the `helixqa_enhanced_tickets` tag), the full package-target form `go test -tags=helixqa_realstack ./tests/qa/...` PASSes too (7/7: 2 evidence + 3 detector + 2 ticket). Re-verified at HEAD post-`86402bfa`.

**Phase 4-C-3 (pkg/ticket adapter): COMPLETED 2026-05-16.** Operator decisions reused from 4-C-1 (Q1–Q10 unchanged). Q4 preserves `Generator` name.

Deliverables this cycle:
- HelixQA-side prereq: `submodules/helixqa/pkg/ticket/enhanced_generator.go` gated behind `//go:build helixqa_enhanced_tickets` so plain consumers of `pkg/ticket` (the Lava adapter) do NOT pull LLMOrchestrator transitively. HelixQA SHA bump owed at parent-cycle close.
- `lava-api-go/internal/qa/ticket/generator.go` (≈340 LOC) — WRAP-strategy adapter with `NewGenerator`, `GenerateClosureLog`, `OutputDir`, `ClosureLogInput` shape mirroring §6.O closure-log conventions
- `lava-api-go/internal/qa/ticket/generator_test.go` (13 tests + 8 sub-tests, 93.2% statement coverage with `-race`)
- `lava-api-go/tests/qa/ticket_test.go` (2 real-stack tests, `//go:build helixqa_realstack`)
- `CLAUDE.md` §6.O extended with clause 7 — adapter authorized as programmatic closure-log path
- This `CONTINUATION.md` updated; coverage-ledger row addition owed at parent-cycle rolled regen

§6.J anti-bluff posture: 2 falsifiability rehearsals captured in commit body — (1) H1 heading mutation surfaced by schema test, (2) empty-CrashlyticsID validation removal surfaced by RejectsEmpty test; both reverted, both green after revert.

Phase 4-C-4 (validator adapter; navigator SKIPPED per Q6) remains owed.

### 2.2 Phase 6 snake_case migration — RESOLVED + EXECUTED

All 8 operator questions answered (2026-05-16). Phase 6a + 6b executed in this cycle. See `docs/plans/2026-05-16-phase6f-upstream-rename-execution.md` for the deferred upstream-rename plan (Q1: defer; document execution steps for operator).

### 2.3 Release-tagging chain (versions inherit from prior cycle)

Last Firebase distribute: 1.2.22-1042 / 2.3.11-2311 (2026-05-14). This cycle made NO user-visible changes, so no new distribute is owed. Tag-script gate per §6.I (multi-emulator container matrix + per-AVD attestation) still blocked on Linux x86_64 + KVM gate-host per the standing §6.X-debt (`.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json`).

---

## 3. Submodule pin index

18 own-org submodules + 1 universal-rules submodule (constitution).
Bumping a pin is a deliberate operator action; never auto-update.
**`panoptic` is at the PROJECT ROOT (`Lava/panoptic`), NOT under `submodules/`** — operator directive 2026-06-03: every submodule MUST be reachable through the project root with NO nesting (CONST-051(C)). The former nested `submodules/challenges/Panoptic` stray clone was removed and re-incorporated here at root.

**2026-06-03 reconciliation (operator-directed "obtain latest versions of all Submodules"):** every submodule advanced to its latest unified `main` and ALL upstreams reconciled to convergence via **fast-forward only — NO force-push** (per the new constitution §11.4.113). All github↔gitlab divergence resolved: every diverged mirror was `gitlab`-behind-`github` (a strict ancestor), so `gitlab` was fast-forwarded to the `github` tip; no merge commits needed, no commits lost. CONTINUATION §4.5 OWED#2 (mirror divergence) is CLOSED. ⚠️ **Build-verification of these advanced pins against the Lava build is OWED** (the full Android + Go build was not run on this host) — verify before any release/distribute.

| Submodule | Pin | Mirrors | Notes |
|---|---|---|---|
| `auth` | `a3fc97d` | GitHub + GitLab | helix-deps.yaml + §11.4.78 CodeGraph cascade; gitlab FF-reconciled 2026-06-03 |
| `cache` | `7853368` | GitHub + GitLab | helix-deps.yaml present; gitlab FF-reconciled 2026-06-03 |
| `challenges` | `dfb5f9c` | GitHub + GitLab | helix-deps.yaml + flat layout (Panoptic dep declared) |
| `concurrency` | `711499e` | GitHub + GitLab | helix-deps.yaml present |
| `config` | `344073f` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh; gitlab FF-reconciled 2026-06-03 |
| `containers` | `6f4f415` | GitHub + GitLab | per-OS procWalker (Linux /proc + macOS `ps`) + conditional `--device /dev/kvm` (§6.AH-debt); SELinux relabel cross-platform; per-OS emulator acceleration |
| `database` | `d822b33` | GitHub + GitLab | helix-deps.yaml present |
| `discovery` | `11bb596` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh; gitlab FF-reconciled 2026-06-03 |
| `helixqa` | `dd3cf1d` | GitHub | HelixDevelopment org; always-track-upstream per §6.AD Q9 waiver; bumped 2026-06-08 `5112906`→`dd3cf1d` to escape a broken pin whose `go.mod` carried unresolved conflict markers (L124/131/138) that broke `lava-api-go`'s Go build |
| `doc_processor` | `7750140` | GitHub | vasic-digital/DocProcessor; added 2026-06-08 — HelixQA `go.mod` sibling dep (`digital.vasic.docprocessor`) per §11.4.27/§11.4.28 (own-org deps reachable from root) |
| `llm_orchestrator` | `d2a2151` | GitHub | vasic-digital/LLMOrchestrator; added 2026-06-08 — HelixQA sibling dep (`digital.vasic.llmorchestrator`) |
| `llm_provider` | `d3da070` | GitHub | vasic-digital/LLMProvider; added 2026-06-08 — HelixQA sibling dep (`digital.vasic.llmprovider`) |
| `llms_verifier` | `9302b5c` | GitHub | vasic-digital/LLMsVerifier; added 2026-06-08 — HelixQA sibling dep (`digital.vasic.llmsverifier`, module at `/llm-verifier`) |
| `vision_engine` | `f96bf56` | GitHub | vasic-digital/VisionEngine; added 2026-06-08 — HelixQA sibling dep (`digital.vasic.visionengine`) |
| `http3` | `7ddc6e8` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh; gitlab FF-reconciled 2026-06-03 |
| `mdns` | `ba1d2385` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh (already converged) |
| `middleware` | `ccf237a` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh |
| `observability` | `73bbd1b` | GitHub + GitLab | helix-deps.yaml present; gitlab FF-reconciled 2026-06-03 |
| `panoptic` (ROOT: `Lava/panoptic`) | `2f8e7c2` | GitHub + GitLab | added at project root 2026-06-03 (flattened from the stray `challenges/Panoptic` nesting per CONST-051(C)); gitlab FF-reconciled to github tip |
| `ratelimiter` | `92b01ea` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh; gitlab FF-reconciled 2026-06-03 |
| `recovery` | `0eb87cf` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh |
| `security` | `16ae574` | GitHub + GitLab | helix-deps.yaml present; gitlab FF-reconciled 2026-06-03 |
| `tracker_sdk` | `7afc37aa` | GitHub (origin) | already at origin/main (converged) |
| `constitution` | `d90ab87` | universal (HelixConstitution: github+gitlab+gitflic+gitverse) | **§11.4.113 absolute no-force-push + merge-onto-latest-main mandate** (2026-06-03); converged on all 4 upstreams |

**Internal-to-submodule nested submodules — NONE (CONST-051(C) fully satisfied 2026-06-03).** `submodules/challenges` previously had a stray nested `Panoptic` clone (the last tracked `CM-NO-NESTED-OWN-ORG-SUBMODULES` exposure). It was removed and `vasic-digital/Panoptic` is now incorporated as a **root-level submodule at `Lava/panoptic`** per operator directive (every submodule reachable through the project root, no nesting). Challenges still declares Panoptic as a `layout: flat` dependency in its `helix-deps.yaml`; that declaration now resolves to the root `panoptic` submodule. No submodule nests any own-org submodule.

---

## 4. Known issues + bugs (carried forward — historical)

These are real defects discovered before this cycle. Tracked here for
forensic continuity; none are blocking this cycle's constitutional
work.

### 4.5 Active known issues

- **§6.X-debt (Linux x86_64 + KVM containerized gate path)**: STANDING for the
  Linux host path only. The **darwin/arm64 sub-debt is RESOLVED** (2026-05-20,
  commit `23c508e9`): per-OS emulator acceleration (`AccelProfileForOS` /
  `ResolveRunner` / `GateEligibleForOS` + `emulator-matrix --runner=auto` in
  Containers `c1871138`+`6aff7ea8`) makes the macOS gate runner host-direct+HVF
  (a Linux container cannot reach the host-only HVF API). PROVEN: C00 cold-start
  canary + full 37-class Challenge suite on Pixel_8/API35 = 43 pass / 3
  credential-skip / 0 fail. The Linux x86_64 containerized-KVM path remains owed.
  Forensic anchor: `.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json`.
- **§6.H Firebase CI token echo-leak** (2026-05-20, §6.L 67th): **RESOLVED
  2026-05-31** — operator rotated the token (`firebase logout` →
  `firebase login:ci`) during the §6.L 68th cycle; the transcript-leaked token
  (never committed to git) is now dead. §6.H clause 6 satisfied. Incident:
  `.lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json`.
- **LVA-8 — HelixQA crash-detector + consumer fixture** — **RESOLVED 2026-05-31** (§6.L 68th):
  `internal/qa/validator` has 6 failing tests because HelixQA's `isPIDAlive`
  (`submodules/helixqa/pkg/detector/desktop.go`) shells `exec kill -0 <pid>` and
  `/bin/kill -0 <absent-pid>` returns EXIT 0 on macOS (bash builtin returns 1), so
  a dead PID reads as alive → Validator reports StepPassed on a crashed step (the
  canonical §6.J bluff). ROOT CAUSE CONFIRMED (captured evidence). Fix belongs
  UPSTREAM in HelixQA (use `syscall.Kill` not `/bin/kill`) per CONST-051 + CONST-049.
  Incident: `.lava-ci-evidence/sixth-law-incidents/2026-05-31-helixqa-validator-killbinary-macos-bluff.json`.
  The Lava adapter is a faithful pass-through (0-byte `internal/` diff) — the
  defect is entirely HelixQA-side. **Operator decision owed:** authorize the HelixQA
  upstream fix cycle (fix → push to HelixQA → bump pin).
- **LVA-3 — LVA-vs-canonical-workable-items reconciliation** (§6.L 68th): the
  constitution ships a canonical `workable-items` Go binary at
  `constitution/scripts/workable-items/` keyed `docs/workable_items.db`; Lava built
  a parallel LVA-keyed system at `tools/lava-tickets/` + `docs/tickets/tickets.db`.
  Both satisfy §11.4.93/95/106; whether LVA supersedes or complements the canonical
  binary (§11.4.74 catalogue-first) is an **operator decision**. Until ratified both
  the LVA system AND the §6.AD.3 Path-B `.lava-ci-evidence/` ledgers stay in force.
- **macOS emulator stall** (2026-05-15 incident): Pixel_7_Pro on macOS
  + emulator 36.1.9 stalls indefinitely. Three candidate root-causes
  recorded as `PENDING_FORENSICS:` (T7 external drive contention,
  emulator-36.1.9 known issues, AVD config theory eliminated by fresh-AVD
  re-test). Forensic anchor: `.lava-ci-evidence/sixth-law-incidents/2026-05-15-macos-emulator-stall-on-android33.json`.
  Orthogonal to §6.X-debt.
- **github SSH-fail flake pattern** (resolved this cycle): the §6.L 37th
  + 39th invocation forensics document multi-push retry pattern; the
  resolution is the standing operator practice of retrying `git push github`
  on connection-reset. No code change owed.

### 4.5 Resolved this cycle

- **Flaky `CredentialsViewModelTest > select provider updates selectedProvider`**
  (§6.L 68th, 2026-05-31): the fixed-`awaitState()`-count assumption was replaced
  with a bounded await-until-`selectedProvider=="rutracker"` loop, removing the
  dependence on the non-deterministic interleaving of `load()`'s Room-`Flow`
  `.first()` resume (delivered off the StandardTestDispatcher) vs. the
  `SelectProvider` reduce. Falsifiability-rehearsed (broke the reduce →
  `AssertionFailedError: expected:<rutracker> but was:<null>`, localized to that
  one test → reverted → 6/6 green). Incident JSON
  `.lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json`
  remains as the forensic record.
- **`.codegraph/*.pid` gitignore gap** (§6.L 68th): `daemon.pid` was
  untracked-but-not-ignored (§11.4.30); `.codegraph/*.pid` added to `.gitignore`.
- **Stale coverage ledger (§11.4.25)** (§6.L 68th): the verify-all sweep was
  46/47 — `docs/coverage-ledger.yaml` had drifted (prior cycles added
  `feature/login` LoginViewModelTest, `feature/onboarding` AuthTypeDisplayTest +
  challenge C26, `feature/provider_config` ProviderConfigViewModelTest without
  regenerating the ledger). Regenerated via `scripts/generate-coverage-ledger.sh`;
  `check-coverage-ledger.sh --strict` now EXIT=0. Sweep → **47/47 PASS**.

#### LVA ticket system NEW (§6.L 68th, §11.4.93/95/106)

The operator's "define ticket key LVA + SQLite workable-items DB + Issues/Fixed/
Issues_Summary/Fixed_Summary + PDF/HTML/DOCX exports" directive is the same
requirement as the new HelixConstitution **§11.4.93/95/106** (workable-items
SQLite DB tracked-in-git + mechanical md↔DB byte-identical sync). Built as a
pure-Go module at `tools/lava-tickets/` (`digital.vasic.lava.tickets`, Go 1.26,
`modernc.org/sqlite` — no CGO, no sudo). Key prefix **`LVA`** (LVA-1, LVA-2, …;
the Lava instantiation of §11.4.54 ATM-NNN). The DB
`docs/tickets/tickets.db` **IS tracked** (§11.4.95 — never gitignored); only
`*.db-wal/-shm/-journal` sidecars + `bin/` + scratch are ignored. Subcommands:
`init/add/update/close/reopen/gen/verify/import/export/list/version`. `go test`
7/7 PASS (incl. §11.4.106 round-trip + falsifiability, §11.4.33 type-aware
closure, §11.4.34 reopen-attribution); `verify` byte-identical PASS (exits 1 on
drift — independently confirmed). Exports: HTML (pure-Go) ✓, DOCX (podman
`pandoc/core`) ✓, **PDF honestly BLOCKED** (container lacks a LaTeX engine — tool
exits 3, writes NO fake file; remediation: `pandoc/latex` image or host
`weasyprint`). 7 real LVA tickets seeded from actual project state (no invented
SHAs). Design + verbatim build evidence: `docs/tickets/DESIGN.md` +
`docs/tickets/BUILD-EVIDENCE.md`. **OPEN (operator decision, LVA-3):** whether
the LVA system supersedes or complements the §6.AD.3 Path-B `.lava-ci-evidence/`
ledgers — until ratified, the Path-B mapping remains the binding compliance
surface and the LVA DB is seeded from it (reconciled, not replacing).

#### Constitution pin BUMPED `208e2c8` → `883ccc1` (§6.L 68th, §6.AF)

Operator directed bump-now+adopt. 53 upstream commits (2026-05-20 → 2026-05-31)
add **§11.4.79–§11.4.106** (28 universal clauses). 68th-cycle review at
`.lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md`: **NO
constitution-mandated submodule is missing** (Challenges, HelixQA, containers,
codegraph all present). New §6.AF clause in CLAUDE.md enumerates per-clause Lava
adoption status + §6.AF-debt for the OWED items:
  - §11.4.93/95/106 (workable-items DB) — **SATISFIED** by the LVA system. The
    constitution also ships a canonical `workable-items` Go binary at
    `constitution/scripts/workable-items/` keyed `docs/workable_items.db`;
    LVA-vs-canonical reconciliation is operator-gated (LVA-3).
  - §11.4.79/.80 (own-org submodules IN codegraph index) — **OWED** (LVA-6;
    Lava currently excludes `submodules/`).
  - §11.4.85 (stress+chaos) — **IN PROGRESS** (LVA-7; phase-1 lava-api-go
    scaffold + evidence under `docs/chaos-stress/`).
  - operating-mode clauses — EQUIVALENCE-MAPPED to existing Lava practice.
    §11.4.100 (video-color) DEMOTED to ATMOSphere-only — not binding on Lava.
The constitution submodule pin bump is a parent-repo change; per CONST-049 the
constitution stays pinned + advanced deliberately (NOT auto-tracking).

#### Resolved in prior (constitution-compliance) cycle

- **Ledger-staleness drift class** — Phase 7-debt closure (`0c87b6ae`)
  flipped the coverage-ledger gate from `--advisory` to `--strict` in the
  sweep wrapper. Subsequent stale-ledger commits will hard-fail at sweep
  time + pre-push.
- **§11.4.27 HelixQA non-incorporation** — Phase 4 closure (`aa0db6bd`)
  + Phase 4-debt closure (`858ffb3e`) bring HelixQA in as
  `submodules/helixqa` with full mirror compliance.
- **§11.4.31 / .35 / .36 zero-waiver state** — Phase 4-debt closure +
  Phase 8-debt closure achieve 17/17 own-org submodules satisfying all
  three mandates with **zero waivers** in STRICT mode.

### 4.5 Historical — pre-this-cycle, carried forward

(See full historical detail in the git log between the prior CONTINUATION.md
"Last updated" header and `4a7d0402`. Summary: C02 Cloudflare-mitigation
stops short of profile-parsing; C17-C22 require emulator matrix; UDP buffer
warning documented; mirror model reduced to 2-mirror per §6.W;
docs/todos/Lava_TODOs_001.md committed as historical; etc.)

---

## 5. Operator-flagged follow-up items (small, queued)

- **Phase 4-C implementation** — blocked on §2.1 open questions.
- **Phase 6a implementation** — blocked on §2.2 open questions.
- **HelixQA pin upgrade cadence** — operator decides when to re-baseline
  to track HelixQA `main` vs. holding at `b13ba7c0`.
- **Re-audit HelixQA scripts (`docs/helixqa-script-audit.md`)** on every
  pin bump per the §6.W audit doc's re-audit-trigger clause.
- **Coverage-ledger row additions** when new feature modules land — the
  generator is deterministic; re-run via `scripts/generate-coverage-ledger.sh`
  in the same commit as the new module to keep STRICT mode green.

---

## 6. Constitutional debt + memory anchors

- **§6.K-debt** (Containers extension): RESOLVED 2026-05-07.
- **§6.N-debt** (pre-push hook enforcement): RESOLVED 2026-05-05.
- **§6.AD-debt** (HelixConstitution-Inheritance per-scope + CM-* wiring): FULLY DRAINED 2026-05-18. Of the original `CM-*` set: 5 mapped Path-B-equivalent (CLOSED-BY-EQUIVALENCE per §6.AD.3), `CM-UNIVERSAL-VS-PROJECT-CLASSIFICATION` CLOSED by pre-push Check 8, `CM-SCRIPT-DOCS-SYNC` CLOSED 2026-05-17 (`11820734`), `CM-COMMIT-DOCS-EXISTS` CLOSED 2026-05-18 (`977630c3`), `CM-SUBAGENT-DELEGATION-AUDIT` CLOSED 2026-05-18 (`2a0e11f4`). No items remain OWED.
- **§6.X-debt** (Linux x86_64 + KVM gate-host for container-bound emulator matrix): STANDING. See §4.5 above.
- **§6.L** (Anti-Bluff Functional Reality Mandate): 52 invocations across multiple working days; 17-cycle back-to-back the longest sequence in project history this cycle. Per §6.L the repetition IS the constitutional record.
- **§6.R** (No-Hardcoding Mandate): UUID + IPv4 + host:port scanners active; algorithm-parameter literal grep staged (code-review gate per §6.R clause body).
- **§6.S** (Continuation Document Maintenance): THIS file. Per §6.S the §0 "Last updated" line MUST track HEAD.
- **§6.T** (Universal Quality Constraints): four sub-points active (Reproduction-Before-Fix, Resource Limits, No-Force-Push, Bugfix Documentation).
- **§6.AC** (Comprehensive Non-Fatal Telemetry): scanner in STRICT mode; ci.sh hard-fail wired.
- **§6.AB** (Anti-Bluff Test-Suite Reinforcement): scanner in STRICT mode.
- **§6.AE** (Comprehensive Challenge Coverage + Container/QEMU Matrix): per-feature scanner in STRICT mode; container matrix runner BLOCKED on §6.X-debt.

---

## 7. RESUME PROMPT

> **⏩ CURRENT RESUME (§11.4.131, 2026-06-09) — SHORT (one-paste):** *"Read `docs/CONTINUATION.md` §0 (top entry) + `.remember/remember.md`, `git fetch`, then continue the autonomous anti-bluff loop on `master`: the constitution pin is now `60e2d66` with new universal clauses §11.4.128–141 adopted via §6.AI — start adopting/closing the §6.AI-debt (§11.4.140 LAYER-2 action-prefix hook, §11.4.141 thin-index, §11.4.128 device-recorder) and drain the open LVA tickets (LVA-028 Nnmclub publishDate, LVA-029 isLocalHost fc/fd, LVA-025/026 v1 captcha, LVA-030 6-submodule §6.R pointers, LVA-6 codegraph-index), keeping 5-6 parallel subagents + rock-solid falsifiable evidence + no-force-push; LVA-008 (C11 nav-teardown → Firebase distribute) still awaits the operator accept-vs-keep-RED call."*
>
> **FULL (paste-ready):** read §0 top entry + the paste-block below. State 2026-06-09: HEAD on `master` converged GitHub+GitLab; constitution pin `60e2d66`; ledger 30 items (20 closed) via `constitution/scripts/workable-items/bin/workable-items` on `docs/workable_items.db` (gate `scripts/check-workable-items.sh`). Binding constraints: anti-bluff §6.J/§6.L + Sixth/Seventh Laws (every fix falsifiability-rehearsed: mutate→RED→revert, captured in commit Bluff-Audit), no-force-push (§6.T.3/§11.4.113), §6.S CONTINUATION-in-same-commit, §6.AH no-host-direct-VMs. Action-prefix (§6.AJ/§11.4.140): a prompt starting `ACTION ::` (e.g. `BACKGROUND ::`) expands via `constitution/actions/registry.yaml`. NOTE: full `scripts/check-constitution.sh` exits 1 on LVA-030 (6 submodules' §6.R pointer) — pre-existing, changed-only pre-push passes. **The 2026-06-04 completeness-program branch + the older block below are SUPERSEDED — ignore.**

Paste the following into a new CLI agent session to continue this
work. The agent needs no scrollback — everything it needs is in this
file plus the spec/plan/CLAUDE.md set referenced from it.

```
Continue Lava project work. Read these in order before doing anything:

  1. /Users/milosvasic/Projects/Lava/docs/CONTINUATION.md
  2. /Users/milosvasic/Projects/Lava/CLAUDE.md
  3. /Users/milosvasic/Projects/Lava/constitution/Constitution.md
  4. /Users/milosvasic/Projects/Lava/docs/plans/2026-05-15-constitution-compliance.md
  5. /Users/milosvasic/Projects/Lava/docs/helix-constitution-gates.md
  6. /Users/milosvasic/Projects/Lava/docs/coverage-ledger.yaml (skim — generated)

Then check the git state vs the CONTINUATION.md "Last updated" line.
If new commits exist on master beyond what CONTINUATION.md describes,
trust the commits and update CONTINUATION.md before proceeding (per §6.S).

Active state per CONTINUATION.md §1 (2026-05-16):
  - All 10 phases of the constitution-compliance plan DONE (Phases 1-9 closed).
  - HelixQA submodule incorporated; Phase 4-debt CLOSED 2026-05-16.
  - Verify-all sweep: 40/40 PASS in fully STRICT mode.
  - 17/17 own-org submodules with helix-deps.yaml + install_upstreams.sh; zero waivers.
  - Coverage ledger: 48 covered / 10 partial / 0 gap (58 rows).
  - §6.L counter at 52; 53rd in-flight at the moment of this CONTINUATION.md refresh.
  - 33 session commits §6.C-converged on GitHub + GitLab.

Your default next action (priority order):
  1. **Phase 4-C** (HelixQA Go-package linking): blocked on 10 operator open
     questions at `docs/plans/2026-05-16-helixqa-go-package-linking-design.md`
     §G. Surface the questions to the operator; do NOT proceed to 4-C-1.
  2. **Phase 6a** (snake_case migration): blocked on 8 operator open questions
     at `docs/plans/2026-05-16-snake_case-migration.md` §11. Surface the
     questions to the operator; do NOT proceed to Phase 6a implementation.
  3. **Crashlytics monitoring**: the last Firebase distribute was 1.2.22-1042
     (2026-05-14); check Crashlytics for any new issues per §6.O closure
     mandate.
  4. **HelixQA pin freshness**: re-baseline `submodules/helixqa` from
     upstream if operator approves; re-run the §6.W audit.
  5. **Tag-script gate**: still blocked on §6.X-debt (Linux x86_64 + KVM
     gate-host) for §6.I matrix attestation. No release tag this cycle.

Do NOT re-run completed phases — they are committed + pushed + sweep-verified.
The git log is the authoritative record.

Verify-all sweep evidence: `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`
Latest gates index: `docs/helix-constitution-gates.md`
Coverage ledger: `docs/coverage-ledger.yaml`

Constitutional bindings still in force (do not relax):
  §6.J / §6.L (Anti-Bluff Functional Reality Mandate)
  §6.AB / §6.AC / §6.AE (anti-bluff scanners — STRICT)
  §6.AD (HelixConstitution Inheritance) + §6.AD.3 (equivalence-mapping)
  §6.R (No-Hardcoding Mandate)
  §6.S (Continuation Document Maintenance — THIS file)
  §6.W (GitHub + GitLab Only Remote Mandate; HELIX_DEV_OWNED exemption for HelixDevelopment org)
  §6.X (Container-Submodule Emulator Wiring; PARTIAL — gate-host owed)
  §11.4.25-§11.4.36 (12 new HelixConstitution clauses)

The operator's standing §6.L wall is preserved verbatim in CLAUDE.md.
Read it.
```

---

## 8. House-keeping the agent should keep doing

These are habits established across multiple cycles; future agents
should preserve them.

1. **Commit messages carry Bluff-Audit stamps for every test class
   added or modified** (Seventh Law clause 1; pre-push Check 2 rejects
   commits without them).
2. **Every commit must have `Co-Authored-By: Claude Opus 4.7
   (1M context) <noreply@anthropic.com>`** as the trailer.
3. **Push to both Lava parent mirrors (GitHub + GitLab)** after every
   commit chain that closes a logical unit. After every push, confirm
   convergence with
   `for r in github gitlab; do echo "$r: $(git ls-remote $r master | awk '{print $1}' | head -1)"; done`.
4. **Submodule pushes are explicit per submodule** to whatever remotes
   that submodule has (varies — see §3). Never use
   `git submodule foreach git push` blindly.
5. **Update this CONTINUATION.md** in the same commit as any
   completion-state change (phase done, new spec/plan written, submodule
   pin bumped, distribute artifact shipped, new operator-blocked open
   question surfaced).
6. **Run `scripts/verify-all-constitution-rules.sh`** before any
   release-tagging or major-state-change attempt. The sweep wrapper is
   in fully STRICT mode; a non-40/40 result is a release blocker.
7. **Re-generate the coverage ledger** (`scripts/generate-coverage-ledger.sh`)
   in the same commit as any new feature module or any module-deletion;
   the STRICT-mode gate rejects stale-ledger commits.
8. **The autonomous loop ends** when the next forward step requires
   operator-environment access (real device, real keystore secrets,
   Firebase token, ssh credentials) OR operator decision-making
   (open questions, brainstorming next phase scope, tagging, choosing
   a UI direction). At that point, summarize state + ask the operator
   the specific next-step question.

---

## 9. Cross-references

- **Plan docs (this cycle):**
  - `docs/plans/2026-05-15-constitution-compliance.md` — master plan
  - `docs/plans/2026-05-16-helixqa-integration-design.md` — Option 1 wiring (DONE)
  - `docs/plans/2026-05-16-helixqa-go-package-linking-design.md` — Option 2 design (DESIGN-ONLY; operator-blocked)
  - `docs/plans/2026-05-16-snake_case-migration.md` — Phase 6 plan (PLAN-ONLY; operator-blocked)
- **Gates inventory:** `docs/helix-constitution-gates.md`
- **Coverage ledger:** `docs/coverage-ledger.yaml` + `docs/coverage-ledger.waivers.yaml`
- **Constitution source-of-truth:** `constitution/` submodule (HelixConstitution at `464ada14`)
- **Sweep wrapper:** `scripts/verify-all-constitution-rules.sh`
- **Sweep attestations:** `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`
- **HelixQA audit:** `docs/helixqa-script-audit.md`
- **HelixQA wrapper:** `scripts/run-helixqa-challenges.sh`
- **HelixQA hermetic test:** `tests/check-constitution/test_helixqa_wiring.sh`
