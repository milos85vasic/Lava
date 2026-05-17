# Real-Device Verification — Lava-Android-1.2.0-1020

status: PENDING_OPERATOR

This evidence file is the load-bearing acceptance gate for the
Lava-Android-1.2.0-1020 release tag per Sixth Law clause 5 + Seventh
Law clause 3 (operator real-device attestation). It is currently a
**stub**: the SP-3a Phase 5 implementer landed all 26 commits plus
the post-implementation documentation polish, and the mechanical
gates in `scripts/tag.sh` (Task 5.21) are wired, but the agent cannot
run the Challenge Tests on a physical device.

The operator MUST complete the checklist below and then update the
`status` line above to `VERIFIED` before invoking
`./scripts/tag.sh --app android` for this version.

> **See also:** [`docs/SP-3A-RELEASE-NOTES.md`](../../docs/SP-3A-RELEASE-NOTES.md)
> for the full operator-facing release notes (what shipped, what's
> deferred, the same checklist below in narrative form, and the
> literal `scripts/tag.sh` commands to run after sign-off).

---

## Operator checklist

### Pre-conditions

- [ ] A physical Android device (API 26+, internet access) is
      connected and `adb devices` shows it as `device`.
- [ ] The host machine is plugged in (not battery-only).
- [ ] The development tree at HEAD matches the commit being tagged
      (`git rev-parse HEAD` recorded below).
- [ ] `KEYSTORE_PASSWORD` and `KEYSTORE_ROOT_DIR` are set in `.env`.

### Build + install

```
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:installDebug
```

- [ ] APK builds and installs without error.
- [ ] App icon `Lava (dev)` appears in the launcher.
- [ ] First launch shows the home screen without a crash dialog.

### Challenge Test execution (8 scenarios)

For each Challenge Test C1-C8, the operator MUST:

1. Run the test (or perform it manually if the connectedAndroidTest
   runner is not yet wired):
   ```
   ./gradlew :app:connectedDebugAndroidTest --tests \
     "lava.app.challenges.C<N>_<Name>Test"
   ```
2. Capture a screenshot of the user-visible state described in the
   test's primary assertion.
3. Perform the falsifiability mutation listed in the test header,
   re-run, capture the verbatim failure assertion message.
4. Revert the mutation, re-run, confirm pass.
5. Update `challenges/C<N>.json` in this directory:
   ```json
   {
     "challenge_id": "C<N>",
     "status": "VERIFIED",
     "test_run_timestamp": "<iso8601>",
     "device": "<model + Android version>",
     "rehearsal_observed_failure": "<verbatim assertion message>",
     "screenshots_sha256": ["<hash1>", "<hash2>"]
   }
   ```

Checklist:

- [ ] **C1** App launch + tracker selection — both RuTracker and
      RuTor visible in Settings → Trackers; selecting either
      updates the active state.
- [ ] **C2** Authenticated search on RuTracker — login with
      `<redacted-per-§6.H>` / `<redacted-per-§6.H>`, search "ubuntu",
      ≥1 result row with size + seeders.
- [ ] **C3** Anonymous search on RuTor — switch to RuTor, search
      "ubuntu", ≥1 result row with size + seeders. No login.
- [ ] **C4** Switch tracker and re-search — banner changes from
      "Results from RuTracker" to "Results from RuTor".
- [ ] **C5** View topic detail — title, description, file list,
      magnet URI button all rendered.
- [ ] **C6** Download `.torrent` file — file written to app
      downloads dir, parses as bencoded torrent (`info.pieces`
      present).
- [ ] **C7** Cross-tracker fallback accept — with all RuTracker
      mirrors UNHEALTHY (test seed), modal appears, "Try RuTor"
      shows RuTor results.
- [ ] **C8** Cross-tracker fallback dismiss — with same setup,
      "Cancel" shows explicit failure UI; no silent fallback to
      RuTor.

### Mirror smoke

- [ ] `submodules/tracker_sdk` is at the pinned hash; the smoke
      test in `scripts/sync-tracker-sdk-mirrors.sh --check` passes
      (or the equivalent manual verification is logged in
      `mirror-smoke/`).

### Bluff audit

- [ ] `scripts/bluff-hunt.sh` has been run since the commit being
      tagged; the resulting JSON evidence is in `bluff-audit/`.

### Sign-off

When all checkboxes above are ticked:

1. Replace `status: PENDING_OPERATOR` at the top of this file with
   `status: VERIFIED`.
2. Add a final block:
   ```
   verified_by: <operator name or signer key id>
   verified_at: <iso8601 timestamp>
   commit_sha: <git rev-parse HEAD at verification time>
   device: <model + Android version + IMEI/serial>
   evidence_zip_sha256: <hash of the zipped evidence pack>
   ```
3. Run `./scripts/tag.sh --app android`. The script's evidence-pack
   gate (Task 5.21) will validate this file's `status: VERIFIED`
   line + each Challenge Test attestation, and proceed with the
   4-upstream push and per-mirror SHA convergence check.

---

## SP-3a Phase 5 implementer's note (2026-04-30)

The implementer has:

- Landed all 25 SP-3a Phase 5 commits.
- Created the test-source files for C1-C8 at
  `app/src/androidTest/kotlin/lava/app/challenges/`.
- Created the per-test PENDING_OPERATOR evidence stubs at
  `.lava-ci-evidence/sp3a-challenges/C{1..8}-pending.json`.
- Created the mechanical evidence-pack gate in `scripts/tag.sh`
  that will refuse to tag without `status: VERIFIED` here.

The implementer has NOT:

- Run the Challenge Tests on a physical device (no device available
  in the agent environment).
- Wired the connectedAndroidTest runner into `:app/build.gradle.kts`
  (no androidx.test runner deps yet; that wiring is constitutional
  debt tracked in `feature/CLAUDE.md`).

These two gaps are the operator's responsibility per the Local-Only
CI/CD rule and Sixth Law clause 5. Tagging is BLOCKED until the
operator works through the checklist.
