# §6.AK Check 10 — api-app Channel Extension Patch (exact, copy-pasteable)

**Date:** 2026-06-26
**Status:** Ready to apply (logic proven hermetically — see §4 below)
**Closes:** the api-app portion of §6.AK-debt (the owed follow-up named verbatim in the live Check 10 comment at `.githooks/pre-push:204-206` and in `docs/superpowers/specs/2026-06-26-ak-gate-wiring-integration.md` §2.2).
**Companion test:** `tests/cycle-coverage/test_wiring_apiapp.sh` (5/5 PASS against the real gate; mutation-rehearsal proven — §4).
**Spec parent:** `docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md` §5.4 + `docs/superpowers/specs/2026-06-26-ak-gate-wiring-integration.md` §2.

`Classification:` project-specific (the insertion is Lava-file-specific; the gate-coverage-must-intersect-cycle-claims pattern is universal per §6.AK).

> **IMPORTANT — this document does NOT edit the live gate file.** It is the precise patch the main stream applies later. The target file (`.githooks/pre-push`) is pushed-through-pre-push concurrently by the main stream; editing it here would corrupt an in-flight gate. Apply this patch only when the main stream is quiescent.

---

## 0. Why this patch

§6.AK gate-wiring landed in commit `28a8b79b`: `.githooks/pre-push` **Check 10** + `scripts/firebase-distribute.sh` **Phase-1 Gate 7** both enforce `scripts/check-cycle-coverage.sh`.

The asymmetry the live code documents (`.githooks/pre-push:204-206`):

```
    # CLIENT-channel scope (mirrors Check 7); api-app advances are gated at
    # distribute time by firebase-distribute.sh Gate 7 (owed §6.AK-debt follow-up
    # to extend Check 10 to the api-app channel).
```

- **CLIENT** channel (`.lava-ci-evidence/distribute-changelog/firebase-app-distribution`): a `last-version-{debug,release}` advance is gated at **both** push time (Check 10) AND distribute time (Gate 7).
- **api-app** channel (`.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app`): a `last-version-{debug,release}` advance is gated **only at distribute time** (Gate 7 reads `$CHANGELOG_DIR`, which app-resolves to the api-app dir for `--app api-app`). At **push time** the api-app pointer advance is invisible to the hook.

The gap: a commit that advances the api-app `last-version-debug`/`-release` pointer with failing cycle-coverage is *accepted by the push hook* and is only caught later at distribute time. That is exactly the 1076 incident shape (`627a0d58`: a pointer-advance "ready to distribute" signal landing without covering device evidence) — pushed first, blocked later, instead of refused at the earliest gate. This patch closes the api-app push-time gap so BOTH channels are gated identically at push time.

---

## 1. The patch — wrap Check 10's per-channel body in an outer channel loop

### 1.1 Strategy

The cleanest, lowest-risk approach: the existing Check 10 already hardcodes a single `ak_chan_dir` and then runs the full `for ptr in last-version-debug last-version-release; do … done` advance-detect-and-gate loop against it. Wrap that exact body in an **outer `for ak_chan_dir in <client> <api-app>; do … done`** loop. Zero logic inside the body changes — every `git show "$sha^:$ak_ptr_path"`, snapshot lookup, `--evidence-dir="$ak_chan_dir"`, and `--head="$sha"` invocation already keys off `$ak_chan_dir`, so the body is already channel-agnostic. This is the minimal diff that proves identical behavior on both channels.

### 1.2 Exact current block (anchor — `.githooks/pre-push` lines 195-239)

The block as committed (28a8b79b), reproduced for unambiguous anchoring:

```bash
    # ===== Check 10: §6.AK cycle-coverage on distribute-advancing commits =====
    # Closes the pre-push portion of §6.AK-debt (companion to firebase-
    # distribute.sh's Phase-1 Gate 7). When a commit ACTUALLY advances
    # last-version-debug or last-version-release (NEW > OLD — same detection as
    # Check 7), a pointer-advance signifies "ready to distribute"; the §6.AK
    # cycle-coverage gate MUST then pass for that version: every CHANGELOG-claimed
    # user-visible fix needs an executed+PASSED covering device Challenge in the
    # §6.Z evidence file. Pushing the advance without covering device evidence is
    # the 1076 incident shape (627a0d58: C00-only gate shipped broken flows).
    # CLIENT-channel scope (mirrors Check 7); api-app advances are gated at
    # distribute time by firebase-distribute.sh Gate 7 (owed §6.AK-debt follow-up
    # to extend Check 10 to the api-app channel).
    ak_chan_dir=".lava-ci-evidence/distribute-changelog/firebase-app-distribution"
    for ptr in last-version-debug last-version-release; do
      ak_ptr_path="${ak_chan_dir}/${ptr}"
      git diff-tree --no-commit-id --name-only -r "$sha" 2>/dev/null | grep -qF "$ak_ptr_path" || continue
      ak_old=$(git show "$sha^:$ak_ptr_path" 2>/dev/null | tr -d ' \n' || echo "0")
      ak_new=$(git show "$sha:$ak_ptr_path" 2>/dev/null | tr -d ' \n' || echo "0")
      [[ "$ak_new" =~ ^[0-9]+$ && "$ak_old" =~ ^[0-9]+$ && "$ak_new" -gt "$ak_old" ]] || continue
      case "$ptr" in
        last-version-debug)   ak_channel="debug" ;;
        last-version-release) ak_channel="release" ;;
        *)                    ak_channel="debug" ;;
      esac
      ak_vname=""
      ak_snapshot=$(ls "${ak_chan_dir}"/*-${ak_new}.md 2>/dev/null | grep -v '\-test-evidence\.md$' | head -1 || true)
      if [[ -n "$ak_snapshot" ]]; then
        ak_vname=$(basename "$ak_snapshot" -${ak_new}.md)
      else
        ak_vname=$(git show "$sha:app/build.gradle.kts" 2>/dev/null | grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' | head -1 | sed 's/.*"\([^"]*\)".*/\1/' || echo "")
      fi
      [[ -n "$ak_vname" ]] || continue
      if [[ -x ./scripts/check-cycle-coverage.sh ]]; then
        ak_rc=0
        ./scripts/check-cycle-coverage.sh \
          --version="${ak_vname}-${ak_new}" \
          --channel="$ak_channel" \
          --evidence-dir="$ak_chan_dir" \
          --head="$sha" \
          --strict >/dev/null 2>&1 || ak_rc=$?
        if [[ "$ak_rc" -ne 0 ]]; then
          violations+=("$sha: §6.AK violation — advances $ptr ${ak_old}→${ak_new} (${ak_vname}) but the cycle-coverage gate exits $ak_rc. A CHANGELOG claim lacks a covering executed+PASSED device Challenge (or the §6.Z evidence / cycle-coverage-map is missing / stale / wrong-SHA). Run the missing device Challenge(s) on the gate, OR strike the unverified claim(s) from CHANGELOG.md (§6.AK clause 6), before pushing the pointer advance.")
        fi
      fi
    done
```

### 1.3 The replacement block (paste in place of the block in §1.2)

Replace the entire block above with this. **Three deltas only**, all marked `<<< api-app extension`:

1. the comment's `CLIENT-channel scope … owed §6.AK-debt follow-up` note becomes a `BOTH-channel scope` note;
2. the single `ak_chan_dir=…firebase-app-distribution` assignment becomes an outer `for ak_chan_dir in …firebase-app-distribution …firebase-app-distribution-api-app; do`;
3. a matching `done` closes the outer loop after the existing inner `for ptr … done`.

The 31 lines of inner body (`for ptr in … done`) are **byte-identical** to §1.2 — only re-indented by two spaces to sit inside the outer loop.

```bash
    # ===== Check 10: §6.AK cycle-coverage on distribute-advancing commits =====
    # Closes the pre-push portion of §6.AK-debt (companion to firebase-
    # distribute.sh's Phase-1 Gate 7). When a commit ACTUALLY advances
    # last-version-debug or last-version-release (NEW > OLD — same detection as
    # Check 7), a pointer-advance signifies "ready to distribute"; the §6.AK
    # cycle-coverage gate MUST then pass for that version: every CHANGELOG-claimed
    # user-visible fix needs an executed+PASSED covering device Challenge in the
    # §6.Z evidence file. Pushing the advance without covering device evidence is
    # the 1076 incident shape (627a0d58: C00-only gate shipped broken flows).
    # BOTH-channel scope: client (firebase-app-distribution) AND api-app          # <<< api-app extension
    # (firebase-app-distribution-api-app). The api-app channel is also gated at    # <<< api-app extension
    # distribute time by firebase-distribute.sh Gate 7; gating it here too closes  # <<< api-app extension
    # the push-time gap (§6.AK-debt api-app follow-up). Each channel's pointer +   # <<< api-app extension
    # snapshot + §6.Z evidence live under its own dir, so the same body keys off   # <<< api-app extension
    # $ak_chan_dir unchanged.                                                      # <<< api-app extension
    for ak_chan_dir in \
        ".lava-ci-evidence/distribute-changelog/firebase-app-distribution" \
        ".lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app"; do   # <<< api-app extension
      for ptr in last-version-debug last-version-release; do
        ak_ptr_path="${ak_chan_dir}/${ptr}"
        git diff-tree --no-commit-id --name-only -r "$sha" 2>/dev/null | grep -qF "$ak_ptr_path" || continue
        ak_old=$(git show "$sha^:$ak_ptr_path" 2>/dev/null | tr -d ' \n' || echo "0")
        ak_new=$(git show "$sha:$ak_ptr_path" 2>/dev/null | tr -d ' \n' || echo "0")
        [[ "$ak_new" =~ ^[0-9]+$ && "$ak_old" =~ ^[0-9]+$ && "$ak_new" -gt "$ak_old" ]] || continue
        case "$ptr" in
          last-version-debug)   ak_channel="debug" ;;
          last-version-release) ak_channel="release" ;;
          *)                    ak_channel="debug" ;;
        esac
        ak_vname=""
        ak_snapshot=$(ls "${ak_chan_dir}"/*-${ak_new}.md 2>/dev/null | grep -v '\-test-evidence\.md$' | head -1 || true)
        if [[ -n "$ak_snapshot" ]]; then
          ak_vname=$(basename "$ak_snapshot" -${ak_new}.md)
        else
          ak_vname=$(git show "$sha:app/build.gradle.kts" 2>/dev/null | grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' | head -1 | sed 's/.*"\([^"]*\)".*/\1/' || echo "")
        fi
        [[ -n "$ak_vname" ]] || continue
        if [[ -x ./scripts/check-cycle-coverage.sh ]]; then
          ak_rc=0
          ./scripts/check-cycle-coverage.sh \
            --version="${ak_vname}-${ak_new}" \
            --channel="$ak_channel" \
            --evidence-dir="$ak_chan_dir" \
            --head="$sha" \
            --strict >/dev/null 2>&1 || ak_rc=$?
          if [[ "$ak_rc" -ne 0 ]]; then
            violations+=("$sha: §6.AK violation — advances $ptr ${ak_old}→${ak_new} (${ak_vname}) on ${ak_chan_dir##*/} but the cycle-coverage gate exits $ak_rc. A CHANGELOG claim lacks a covering executed+PASSED device Challenge (or the §6.Z evidence / cycle-coverage-map is missing / stale / wrong-SHA). Run the missing device Challenge(s) on the gate, OR strike the unverified claim(s) from CHANGELOG.md (§6.AK clause 6), before pushing the pointer advance.")
          fi
        fi
      done
    done                                                                          # <<< api-app extension (closes the outer channel loop)
```

> The only body-line change beyond re-indentation is the `violations+=` message: `on ${ak_chan_dir##*/}` is appended so the operator sees WHICH channel (client vs api-app) tripped. This is a message-only delta; the exit/reject behavior is identical.

### 1.4 Apply mechanics (unambiguous)

- The block sits inside the per-SHA `for sha in $range; do … done` loop (lines 58–261 of the current file) and appends to the `violations` array exactly like every other Check. The outer channel loop adds one nesting level; the existing two-space body indentation becomes four-space — re-indent the 31 inner-body lines accordingly (already done in §1.3).
- Net structural change vs. §1.2: `+ for ak_chan_dir in … do` before the `for ptr` loop, `+ done` after it, the comment note swap, and the `##*/` message suffix. Nothing else.
- Independent verification of correctness: `bash -n .githooks/pre-push` (syntax) after applying, then `bash tests/cycle-coverage/test_wiring_apiapp.sh` (must stay 5/5) and `bash tests/cycle-coverage/test_wiring.sh` (must stay 5/5 — the client path is unchanged).

---

## 2. §6.N.1.2 + Check 9 obligations on the GRAFT COMMIT (do not skip)

The commit that applies this patch will itself trip the existing hook:

- **Check 4 / §6.N.1.2** (`.githooks/pre-push:92-126`) fires because the diff touches `.githooks/pre-push` (in Check 4's gate-shaping list at `:97-98`). The graft commit body MUST carry a `Bluff-Audit:` stamp that NAMES at least one file in the diff. Cite `tests/cycle-coverage/test_wiring_apiapp.sh` as the rehearsal. Use the stamp in §3 below.
- **Check 9 / CM-SCRIPT-DOCS-SYNC** (`.githooks/pre-push:241-258`) does NOT apply: `.githooks/pre-push` is not under `scripts/*.sh`, so no companion `docs/scripts/*.md` is required for this graft. (If the same commit also touches a `scripts/*.sh` with an existing `docs/scripts/<name>.sh.md`, that doc must be updated — but this patch touches only `.githooks/pre-push` and adds a test under `tests/`.)
- **Check 2** (`.githooks/pre-push:67-74`) fires because the diff adds `tests/cycle-coverage/test_wiring_apiapp.sh` (matches `Test\.kt|_test\.go`? — NO: the filename is `test_wiring_apiapp.sh`, which does NOT match `(Test\.kt|_test\.go)$`, so Check 2 does NOT fire on it). The `Bluff-Audit:` stamp is still REQUIRED by Check 4 because the diff touches `.githooks/pre-push`.

---

## 3. The §6.N.1.2 Bluff-Audit stamp the graft commit MUST carry

```
Bluff-Audit: .githooks/pre-push (§6.AK Check 10 api-app channel extension)
  Mutation: swallow the api-app refusal — change the inner
            `violations+=("...§6.AK violation...")` push into a no-op (`:`) so a
            failing api-app cycle-coverage gate is accepted at push time.
  Observed-Failure: tests/cycle-coverage/test_wiring_apiapp.sh reports
    "FAIL [apiapp_advance_debug_fail] exit=0 (expected 1)" and
    "FAIL [apiapp_advance_release_fail] exit=0 (expected 1)" — the wrapper even
    prints "pre-push Check 10 (api-app) OK — no §6.AK violation" AFTER a failing
    gate (the exact 1076 push-time-gap shape the extension closes);
    "cases passed: 3   cases failed: 2 / RESULT: FAIL".
  Reverted: yes
```

This stamp is verbatim what the companion test's own `Bluff-Audit:` header documents and what the captured mutation rehearsal produced (§4).

---

## 4. Proof the extension logic is sound (hermetic, no real file edited)

`tests/cycle-coverage/test_wiring_apiapp.sh` generates a STUB pre-push wrapper whose grafted block mirrors §1.3's api-app body (the same Check 10 logic keyed off the api-app `ak_chan_dir` + per-`ptr` channel), invokes the REAL `scripts/check-cycle-coverage.sh` against synthetic api-app §6.Z evidence + cycle-coverage-map fixtures (HEAD + now injected via `LAVA_CYCLE_COVERAGE_HEAD` / `LAVA_CYCLE_COVERAGE_NOW_EPOCH`), and asserts both pointers (debug + release), both verdicts (covered + uncovered), plus the no-advance bypass:

```
test: §6.AK CHECK-10 api-app channel EXTENSION (pre-push push-time gate)
  PASS  [apiapp_advance_debug_pass] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
  PASS  [apiapp_advance_release_pass] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
  PASS  [apiapp_advance_debug_fail] exit=1 (expected 1) + matched '§6.AK violation'
  PASS  [apiapp_advance_release_fail] exit=1 (expected 1) + matched '§6.AK violation'
  PASS  [apiapp_no_advance] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
-----------------------------------------------------------
cases passed: 5   cases failed: 0
RESULT: PASS — pre-push Check 10 api-app channel extension proven against the real gate
```

**Mutation rehearsal (swallow the api-app refusal → negative cases flip):** with the inner `violations+=` changed to a no-op, the two negative cases flip to FAIL and the wrapper prints "Check 10 (api-app) OK" after a failing gate:

```
  PASS  [apiapp_advance_debug_pass] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
  PASS  [apiapp_advance_release_pass] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
  FAIL  [apiapp_advance_debug_fail] exit=0 (expected 1) + needle '§6.AK violation'
        ---- wrapper output ----
        pre-push Check 10 (api-app) OK — no §6.AK violation
  FAIL  [apiapp_advance_release_fail] exit=0 (expected 1) + needle '§6.AK violation'
        ---- wrapper output ----
        pre-push Check 10 (api-app) OK — no §6.AK violation
  PASS  [apiapp_no_advance] exit=0 (expected 0) + matched 'Check 10 (api-app) OK'
-----------------------------------------------------------
cases passed: 3   cases failed: 2
RESULT: FAIL — the api-app Check 10 extension logic is unsound; do NOT graft as-is
```

The extension is therefore falsifiable and sound: it refuses an api-app pointer advance whose cycle-coverage gate fails, and a swallowed refusal is mechanically detectable.

---

## 5. What this does NOT change

- `scripts/check-cycle-coverage.sh` — untouched (already committed + tested; the gate is channel-agnostic).
- `scripts/firebase-distribute.sh` Gate 7 — untouched; it already gates the api-app channel at distribute time via `$CHANGELOG_DIR`.
- Checks 1–9 + the client path of Check 10 in pre-push — untouched; this patch only widens Check 10's channel scan from one dir to two.
- `tests/cycle-coverage/test_wiring.sh` (the client-channel wiring test) — untouched; it remains the client-path proof, and must stay 5/5 after the graft.
