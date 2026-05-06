# Lava — Bug Fix Audit Trail

Per constitutional clause **§6.T.4 (Bugfix Documentation)** — every bug
fix in this project MUST be documented here with root cause analysis,
affected files, fix description, link to the verification test/
challenge, and the commit SHA that landed the fix.

§6.O (Crashlytics-Resolved Issue Coverage Mandate) extends this for
Crashlytics-recorded issues; their closure logs live at
`.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md`. §6.T.4
covers the rest — operator-reported, self-discovered, or
reviewer-flagged bugs that don't enter the Crashlytics pipeline.

Format per entry:

```markdown
## YYYY-MM-DD — <short slug>

**Root cause:** ...
**Affected files:** ...
**Fix:** ...
**Verification test/challenge:** path or commit ref
**Fix commit:** SHA
**Forensic anchor:** (optional) what surfaced the bug
```

---

## 2026-05-06 — phase1-distribute-three-bugs

The first real-distribute of Lava-Android-1.2.7-1027 + lava-api-go-2.1.0
exposed three bugs that landed in commit `e947081`:

**Root cause 1:** `/health` and `/ready` endpoints were registered AFTER
the auth middleware in `lava-api-go/cmd/lava-api-go/main.go` `buildRouter`,
so the orchestrator's liveness probe got 401 → restart loop.

**Root cause 2:** `core/network/impl/.../LavaAuthBlobProvider.kt` was
declared `internal`, but the Phase 11 build-time-generated
`lava.auth.LavaAuthGenerated` class lives in `:app`'s source set —
different module → cannot access internal interface → compile failure.

**Root cause 3:** `scripts/distribute-api-remote.sh` +
`deployment/thinker/thinker-up.sh` did NOT ship the operator's local
`.env` `LAVA_AUTH_*` + `LAVA_API_HTTP3_*`/`BROTLI_*`/`PROTOCOL_*`
values to the thinker.local container. The new 2.1.0 binary required
`LAVA_AUTH_FIELD_NAME` + `LAVA_AUTH_HMAC_SECRET` at boot → crash-loop.

**Affected files:**
- `lava-api-go/cmd/lava-api-go/main.go`
- `core/network/impl/src/main/kotlin/lava/network/impl/LavaAuthBlobProvider.kt`
- `scripts/distribute-api-remote.sh`
- `deployment/thinker/thinker-up.sh`

**Fix:**
1. Register `/health` + `/ready` BEFORE the auth chain.
2. Drop `internal` from `LavaAuthBlobProvider`.
3. Distribute script merges operator's `.env` auth/transport block into
   a temp env file before scp; thinker-up iterates the variables and
   passes each as `-e VAR=$VAR` to `podman run`.

**Verification test/challenge:** post-fix smoke test in
`scripts/distribute-api-remote.sh`'s 60-second `/health` wait
(passes); `curl -fsSk https://thinker.local:8443/{health,ready}`
return `{"status":"alive"}` / `{"status":"ready"}` (passes); auth
gate returns 401 with `{"error":"unauthorized"}` for missing header
(passes — fail-closed posture confirmed).

**Fix commit:** `e947081`

**Forensic anchor:** the very first
`bash scripts/distribute-api-remote.sh` after the api-go-2.1.0 build
showed `lava-api-go: config: config: LAVA_AUTH_FIELD_NAME is required`
in a tight loop, with the orchestrator marking the container
`(unhealthy)` and triggering the restart loop. Operator ran the
distribute, got the lock-up output, immediately surfaced the issue.
