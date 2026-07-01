# Autonomous-QA — deep-fix status & resumption anchor (2026-06-30)

Single-blocker checkpoint for the on-device UI-E2E half. Everything else is proven or committed.

## PROVEN (real, evidence-backed — committed)
- **Core search→download works at the API level** (thinker Go backend, real authed queries):
  - ThePirateBay `1080p` → 100 results + magnet; Nyaa `mp3` → 75 + magnet (auth control-proof). `.lava-ci-evidence/autonomous-qa/2026-06-30/backend-search-evidence/`.
  - **archiveorg `1080p` → HTTP 200, 50 results, http download links** ✅ (the proven keystone query). gutenberg `mp3` → 200, 1 result. (thinker `~/lava-qa/.lava-ci-evidence/autonomous-qa/2026-06-30/api-matrix-evidence/`).
  - REAL FINDING: authenticated providers rutracker/nnmclub/kinozal → 401/502 from thinker (tracker login 502 / datacenter-IP block). Anonymous providers work; authenticated 3 do not from thinker's IP.
- Containerized-KVM emulator boots (~30s, adb via socat bridge) on thinker; Go backend healthy on thinker (`lava-api-go-thinker`, :8443, via `thinker-up.sh` podman-run, NO compose).
- Onboarding→main-app UI flow works LOCALLY (podman 5.7.1).
- Off-main nav crash fixed (core/navigation `runOnMainThread` guard); LVA-008 teardown mitigated (marker-based verdict in run-iteration.sh).

## ✅ ROOT CAUSE FOUND + FIX PROVEN (2026-06-30)
**Root cause:** on thinker the Android guest never *registers* `eth0` as a framework network at boot (the boot-time `ndc network interface add` fails while `eth0` is still DOWN). The NIC itself is fine. Android uses per-network **mark-based routing** (netd), so raw `ip route` edits the main table that Android ignores → app/ping see "Network unreachable".
**Proven fix (guest-side only — NO podman/container/image change):** post-boot, as root via `su 0`:
```
ip link set eth0 up
ip addr add 10.0.2.15/24 dev eth0
ndc network interface add 100 eth0      # net 100 already exists; just add eth0
ndc network route add 100 eth0 0.0.0.0/0 10.0.2.2   # run AFTER eth0 up (else "unreachable")
ndc network default set 100
# DNS: ndc resolver setnetdns syntax differs on API 34 (got "Command not recognized");
#   not needed for the keystone (guest→backend is adb-reverse LOOPBACK; backend has thinker internet).
```
**Verified:** after `ndc network interface add 100 eth0` + `ndc network default set 100`, `ping 10.0.2.2` = 2/2 0% loss. (`ping 8.8.8.8` ICMP fails — slirp doesn't forward ICMP-to-internet; TCP/UDP NAT works. Irrelevant for the keystone which uses loopback to the backend.)
**Next:** wire as `emu_fix_network` post-boot hook in `lib-emulator.sh`; run the archiveorg/1080p keystone.

## NEZHA MATRIX RESULT (2026-06-30) — egress SOLVED, per-provider functional bugs remain (no DOWNLOAD-OK yet)
Ran the matrix ON nezha (emulator egress via VPN) as a DURABLE systemd-user-service + linger (the ONLY way it survived — nezha logind KillUserProcesses reaped every tmux/nohup/poller attempt; `loginctl enable-linger` + `systemd-run --user` fixed it). Build OK, backend up, emulator booted, matrix ran to completion. Verdicts (goapi, --external-backend, 1080p):
- **EGRESS PROVEN WORKING**: rutracker logcat shows `RuTrackerHttp: REQUEST login.php` THEN `index.php` (TWO requests = reached rutracker.org + got a response), vs thinker's 90s CancellationException on login.php. The emulator's traffic now flows through nezha's VPN to the real trackers.
- **rutracker = SKIP** — `CAPTCHA_LOGIN`: automated login can't solve the captcha → onboarding can't complete. Honest, expected, unautomatable for this provider.
- **archiveorg = FAIL** — topic-detail renders `Something went wrong, please try again later` (C70-TREE) → Step-4 affordance wait times out (30s, Challenge70 line 609). SAME error as on thinker ⇒ NOT egress; a real archiveorg TOPIC-DETAIL bug (search reaches the topic, the topic fetch/parse fails). Anonymous provider, no captcha → the most tractable path to green; root cause (on-device archiveorg client topic parse vs archive.org response) needs focused debugging.
- **nnmclub = SKIP** — `FORM_LOGIN` onboarding "could not complete" (30s); login/connectivity step didn't finish. Needs diagnosis (no captcha, so should be automatable once the login step is traced).
- **NO DOWNLOAD-OK** this run. Egress is solved; the remaining blockers are PER-PROVIDER functional issues, not reachability.
NEXT to green: (a) fix the archiveorg topic-detail bug (anonymous → cleanest green); OR (b) try gutenberg (anonymous, different client — may not share archiveorg's topic bug, but sparse results); OR (c) diagnose nnmclub FORM_LOGIN. rutracker/kinozal are captcha/region-gated and won't auto-green.

## ✅ EGRESS SOLVED via nezha VPN (2026-06-30) — operator: "nezha.local is connected to vpn already"
PROVEN: nezha.local has VPN egress IP `31.169.53.44` (≠ thinker `176.195.102.116`) that REACHES the blocked providers — `rutracker=200, nnmclub=200, archiveorg=200` (kinozal=522 Cloudflare-flaky, FlareSolverr-solvable). nezha also has `podman` + `/dev/kvm` + `AllowTcpForwarding=yes`.
PROVEN: a SOCKS5 tunnel `ssh -D 127.0.0.1:1080 nezha.local` from thinker works IN-SESSION (`listening=1`, `via=31.169.53.44`, `rutracker=200` through it). It does NOT survive a transient ssh (setsid/nohup/tmux/systemd-run all left listening=0 from a detached launch) → run the tunnel as a bg job INSIDE the long-lived session that runs the matrix.
REMAINING MECHANICAL STEPS to green (Path B):
1. `remote_sync_repo` (local→thinker) — carries stream E's lava-api-go proxy code + all fixes.
2. REBUILD the lava-api-go image on thinker with the synced source (the deployed image predates stream E, so its provider clients ignore the proxy) + redeploy with `LAVA_API_UPSTREAM_PROXY=socks5://127.0.0.1:1080` in `~/lava/thinker.local.env`. Backend is `--network host` so it reaches `127.0.0.1:1080`. socks5 → Go does remote DNS (resolves trackers via nezha, bypassing thinker's blocked DNS).
3. In ONE long ssh session: start `ssh -D 127.0.0.1:1080 -N nezha.local &` (bg job) → run `run-matrix.sh --backend goapi --external-backend` for the trackers + archiveorg → tunnel stays up for the whole run → real green DOWNLOAD-OK.
ALT (Path A): run the whole matrix ON nezha (has podman+KVM+reachable egress, no proxy/tunnel/rebuild needed) — needs Android SDK+gradle on nezha.

### ⚠️ DECISIVE finding (2026-06-30) — backend proxy does NOT help the trackers; emulator egress is what matters
Wired Path B fully + PROVEN: backend image rebuilt w/ stream-E proxy code, `LAVA_API_UPSTREAM_PROXY=socks5://127.0.0.1:1080` confirmed IN the container (via `podman inspect`; `podman exec printenv` is a false-negative — distroless has no shell), nezha SOCKS tunnel up (`via=31.169.53.44`, rutracker=200 through it). YET rutracker still SKIP. Root cause from the on-device logcat: `RuTrackerHttp: REQUEST https://rutracker.org/forum/login.php failed ... CancellationException` (90s timeout). **The bundled rutracker client logs in DIRECTLY from the device — it does NOT route through the lava-api-go backend.** So the backend's proxy is irrelevant to it; the login egresses via the EMULATOR's network (thinker's blocked IP).
Two compounding facts:
1. Onboarding uses BUNDLED descriptors (the API `/v1/providers` catalogue fetch falls back to bundled — so the goapi-backend flow is NOT even exercised for the trackers; that itself is a real bug to fix if goapi-routing is intended for all providers).
2. Bundled tracker clients (`RuTrackerHttp` etc.) run on-device, direct to the tracker.
**THE FIX for green = make the EMULATOR's egress go through nezha's VPN**, i.e. run the matrix ON nezha (podman+KVM+VPN egress reaches rutracker/nnmclub/archiveorg=200 directly). Path B (backend proxy) only helps the API-backed/RemoteTrackerDescriptor flow, which the catalogue-fallback isn't using.
PATH A execution needs on nezha: repo (rsync), Android SDK (adb+gradle) for connectedAndroidTest, the emulator image (pull), optional lava-api-go backend. OR: emulator-on-nezha (egress via VPN) + adb/gradle driven cross-host from thinker (adb connect nezha:port). nezha already has `deployment/nezha/nezha.local.env` (known deploy target).

## ON-DEVICE KEYSTONE PROGRESS (2026-06-30, archiveorg/1080p, goapi backend on thinker)
The keystone drove the real stack DEEP; each run surfaced + fixed a genuine defect (all recorded: verdict.json + screenrecord frames + C70-TREE semantics dump + JUnit):
1. ✅ **Emulator networking** — root-caused (eth0 unregistered by netd at boot; raw `ip route` ignored due to Android mark-based routing) → `emu_fix_network` post-boot `ndc` default-network hook in `lib-emulator.sh`, now a **retry-until-gateway-reachable loop** (was flaky single-shot). Gateway 10.0.2.2 pings; ConnectivityService reports active network.
2. ✅ **Onboarding provider-deselection** — picker enumerates the FULL bundled descriptor set; the test's `DESELECT_CANDIDATES`/rutracker `displayName` were wrong. Fixed: rutracker `"Rutracker"`→`"RuTracker.org"`; added `"RuTor.info"` + `"IPTorrents"`. Onboarding now COMPLETES on-device (archiveorg-only → anonymous "Continue" → Summary → main app).
3. ✅ **Off-main nav crash** — `NestedNavigationControllerImpl.popBackStack` (+ base) called `navHostController.navigateUp()` unguarded; the search side-effect coroutine runs off-main in the test harness → `setCurrentState` ISE. Fixed with `runOnMainThreadResult` (blocking main-thread marshal) wrapping both `popBackStack`s.
4. ✅ **Search WORKS on-device** — archiveorg/1080p returns its 50 real results (Step-3 `Favorite`-node wait passes); the test clicks the first result.
5. ⚠️ **CURRENT BLOCKER — topic-DETAIL fetch errors.** Opening an archiveorg result's detail shows the error state (`C70-TREE`: Text `"Error"` / `"Something went wrong, please try again"` / `Retry` button) → Step-4 `waitUntil { present("Torrent")||present("Magnet") }` times out (30 s). Note archiveorg downloads are **HTTP files** (`http_identifier`), not torrent/magnet — so even on success the test's affordance assertion needs an HTTP-download branch. UNDER INVESTIGATION: backend archiveorg topic-endpoint bug vs client parse vs upstream archive.org metadata flakiness (archiveorg/mp3 was 502 upstream). Backend per-request logging is minimal (no topic error logged).

### Provider reality from thinker's datacenter IP (real finding)
- Authenticated (rutracker/nnmclub/kinozal/iptorrents/rutor): search 401/502 — login fails / IP-blocked. NOT usable for the full flow from thinker.
- archiveorg: search 200 / 50 results ✅, but topic-detail errors (item 5 above).
- gutenberg: search 200 but sparse (0–1 results).
⇒ No single provider yet yields a clean search→detail→download from thinker; archiveorg is closest (only the detail step remains).

### CONFIRMED INTERMITTENT (multi-run evidence, 2026-06-30)
archiveorg/1080p across consecutive keystone runs: search-works→topic-detail-"Something went wrong" (30 s) ONE run; search-itself-fails "There was a problem reaching the trackers" (60 s Step-3) the NEXT run. The app behaves CORRECTLY (surfaces the upstream failure as an error state with Retry). This is **upstream archive.org reachability flakiness from thinker's datacenter IP**, matching the API-level evidence (archiveorg/1080p=200/50 vs archiveorg/mp3=502). Also: the onboarding picker shows the BUNDLED descriptor set (RuTor.info present) ⇒ the API `/v1/providers` catalogue fetch during onboarding ALSO fails/falls-back intermittently (so the reliable curated providers TPB/Nyaa — proven at API level: 100/75 results+magnets — are NOT onboardable via the bundled-fallback picker).

### HONEST CONCLUSION (no bluff)
The on-device autonomous-QA PIPELINE is PROVEN functional and recorded: containerized-KVM emulator boot → guest networking (root-caused+fixed) → onboarding completes → search renders real results → correct error-handling. The LIMITING FACTOR for a clean end-to-end search→detail→download proof is **provider/upstream reachability from thinker's datacenter egress IP** (authenticated=IP-blocked, archiveorg=upstream-flaky, gutenberg=sparse, API-catalogue-fetch intermittently falls back to bundled). NOT a pipeline defect. A clean proof needs a residential/non-datacenter egress OR the IP-block lifted OR running where the upstreams are reliably reachable. The 4 fixes this session (emulator-net, onboarding deselect ×2, off-main popBackStack) are real, durable improvements regardless.

## THE ONE BLOCKER (was — now root-caused above)
**thinker containerized-emulator has NO guest network** (podman 4.9.3, rootless slirp4netns): guest gets a DHCP lease (10.0.2.16) but installs no route → `ip route` empty, `ping 10.0.2.2` unreachable → onboarding's network steps stall → Challenge70 SKIPs. Local podman 5.7.1 (pasta) works.

### Deep-fix attempts so far
- 42 experiments (subagent, report lost to rate-limit) — all container-net-focused — no fix wired.
- `--network host` (direct, this session): emulator did NOT become adb-reachable (`boot_completed` empty) — inconclusive on guest route.
- `--cap-add NET_ADMIN --cap-add NET_RAW --device /dev/net/tun` (direct): boot=1, **guest `ip route` EMPTY, ping 8.8.8.8 "Network is unreachable"**. FAILED.
- `--network pasta` (direct): boot=1, **guest `ip route` EMPTY, "Network is unreachable"**. FAILED.

### CRITICAL FINDING (2026-06-30) — container net backend is RULED OUT
Two genuinely different container backends (slirp4netns+caps vs pasta) yield the **identical** guest no-route. The Android emulator runs its **own** QEMU user-mode NIC *inside* the container; the guest's default route is pushed by the emulator's **RIL/modem emulation**, independent of the container's network namespace. So the blocker is **guest/emulator-internal**, NOT podman's net backend. The whole 42-experiment + 3-this-session container-net line is a dead end. STOP chasing container net.

### Remaining candidates (guest/emulator-internal — the RIGHT layer)
1. **Inspect the guest NIC state** on thinker: `adb shell ip addr` / `ip link` / `getprop | grep -E 'net|dns|ril'` — is `radio0`/`eth0` UP with 10.0.2.15? Is the route absent or the interface down?
2. **Compare a WORKING local (podman 5.7.1) emulator's** `ip addr`+`ip route`+ril props vs thinker's — the diff pinpoints the exact guest-side divergence.
3. **Emulator launch flags** inside the image entrypoint: add `-netdelay none -netspeed full`, or check whether thinker's emulator is silently launched with a degraded `-netdev` / `-no-network` due to a CPU/KVM capability the emulator probes.
4. **KVM/CPU angle**: thinker CPU may lack a feature the emulator's virtual NIC path needs; try `-accel kvm` flag variants or a different system image ABI.
5. (Lowered priority — net backend ruled out) Provision podman 5.x user-local on thinker.

## RESUMPTION (fresh session, full context)
1. Crack the emulator-net candidate above → wire into `scripts/autonomous-qa/lib-emulator.sh emu_boot` (env-gated `LAVA_EMU_NETWORK`/podman-major detect; no-op on local 5.7.1).
2. Run the PROVEN keystone: `run-matrix.sh --backend goapi --external-backend --subsets archiveorg --queries 1080p` on thinker (backend already up). Expect onboard→search(50 results)→details→download → `C70-RESULT DOWNLOAD-OK` marker → verdict PASS.
3. Execute Challenge70's §6.AB falsifiability rehearsal → honest Bluff-Audit.
4. Scale matrix on thinker (both backends; note authenticated providers will 401/502 — honest findings).
5. Resolve the gated push: §6.Y versionCode bump, `docs/scripts/run-helixqa-provider-qa.sh.md` (§11.4.18), falsifiability block in the 2026-06-30 incident JSON (§6.N.1.3), Bluff-Audit for the now-verified Challenge70 — then push (the pre-push gate correctly blocked the unverified-test commit; do NOT --no-verify).
6. §6.AK cycle-coverage gate → §6.AA two-stage dev+prod firebase-distribute.

## DURABLE STATE
- Local commit `ccdd84c1` holds the harness (8 orchestrator scripts, 5 HelixQA banks, nav fix, Challenge70, docs, forensic, curated evidence). Push is GATED (correctly) — see step 5.
- thinker: Go backend up; api-matrix-evidence + keystone evidence under `~/lava-qa/.lava-ci-evidence/autonomous-qa/2026-06-30/`.
