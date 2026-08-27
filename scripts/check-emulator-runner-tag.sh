#!/usr/bin/env bash
# scripts/check-emulator-runner-tag.sh
#
# §6.X gate — every NEW emulator/Challenge attestation evidence file that records
# emulator execution MUST declare `runner: containers-submodule` (the §6.X
# container-bound gate path) OR `runner=containers-submodule`. A file that
# records an emulator run with a raw/host-direct runner tag — or NO runner tag —
# FAILS.
#
# WHY (forensic anchor): §6.X mandates that the emulator process runs inside a
# podman/docker container managed by the Containers submodule, and that the
# attestation row declares `runner: containers-submodule`. The original
# enforcement clause (c) in scripts/check-constitution.sh was paper-only ("rows
# lacking this declaration are rejected by scripts/tag.sh"). This scanner makes
# clause (c) mechanical at the check-constitution layer too: an emulator-run
# evidence file that omits the runner tag can no longer ship silently.
#
# SCOPE — going-forward, not retroactive. The gate flags only evidence files
# that are NEWLY ADDED relative to git HEAD (staged additions + untracked files
# under the evidence tree). Pre-existing committed evidence is grandfathered:
# many historical files predate §6.X (2026-05-13) or document the §6.X-resolved
# macOS host-direct+HVF gate path, and the task constraint forbids touching real
# evidence. Enforcing the tag on the NEXT file is what closes the
# anti-forgetting gap; retroactively failing committed history is not the job.
#
# "Records emulator execution" — TWO conditions, both required (tightened
# 2026-08-26; see WHY THE MENTION TEST WAS NOT ENOUGH below).
#
# (1) MARKER: the file contains at least one emulator-run marker:
#   - adb_devices_state         (per-AVD forensic snapshot, §6.I.4 Group B)
#   - boot_seconds              (cold-boot timing of an emulator)
#   - connectedAndroidTest / connectedDebugAndroidTest  (instrumentation run)
#   - emulator-matrix           (the Containers matrix CLI)
#
# (2) ATTESTATION SHAPE: the file also RECORDS a run rather than discussing
#     one — either
#       (2a) an attestation FIELD in key position: one of the §6.I.4 per-row
#            field names ("avd": / avd= / | avd | and siblings), in JSON-key,
#            key=value, or markdown-table-cell form; or
#       (2b) a marker sharing a line with an INVOCATION or OUTCOME token
#            (gradlew, BUILD SUCCESSFUL/FAILED, am instrument, --tests,
#            adb -s / adb shell, avdmanager, `emulator-matrix -<flag>`),
#            which is what a plaintext run log looks like.
#
# WHY THE MENTION TEST WAS NOT ENOUGH. The previous version matched (1) alone
# and asserted in this very comment that "bluff-hunt logs … that merely quote a
# marker in prose" would stay clear because they are "unlikely to be NEW
# evidence files added in the same change". That assumption was stated, never
# implemented, and it is false: a §6.N bluff-hunt record is a NEW file under
# .lava-ci-evidence/ by construction, so the going-forward scope does nothing
# for it. MEASURED on 2026-08-26 —
# .lava-ci-evidence/bluff-hunt/2026-08-26-cycle-vacuous-pass-sweep.json was
# flagged as an untagged emulator attestation on the strength of ONE marker,
# inside an "unconfirmed" prose field whose own text reads "Gradle was not run
# (resource constraint)". The gate was calling a written record of NOT running
# an emulator an unattested emulator run. Its sibling
# .lava-ci-evidence/bluff-hunt/2026-06-26-cycle-1.json is the same shape: its
# only marker sits in "constraints": "… NO :app:connectedAndroidTest, NO
# emulator/device …".
#
# WHY NOT A PATH EXEMPTION. Excusing .lava-ci-evidence/bluff-hunt/ would let a
# real attestation hide by being written to that directory. Condition (2) is
# keyed on what the file IS, not where it lives: an attestation dropped into
# the bluff-hunt directory still carries per-row fields and is still flagged.
# tests/check-constitution/test_emulator_runner_tag.sh pins both directions.
#
# COST, measured rather than asserted: across all 117 tracked evidence files
# carrying a marker, condition (2) reclassifies exactly 4 as prose. Each was
# read: two are bluff-hunt records whose marker is in a prose field, one is an
# investigation note analysing a Gradle task, one is a subagent-dispatch
# "purpose" string. None records a device run. Every genuine attestation in the
# corpus — JSON per-row files, canary attestations, and the markdown
# *-test-evidence.md files whose marker appears in a table header — still
# satisfies (2).
#
# OVERRIDES:
#   - LAVA_EMULATOR_EVIDENCE_FILES — newline-separated explicit file list to
#     scan (hermetic test injection; bypasses git-new-file detection).
#   - LAVA_EMULATOR_RUNNER_STRICT=0 — advisory mode (exit 0 even on violation).
#
# This scanner is generic + portable on purpose so it ports up into the shared
# constitution submodule later.
#
# Classification: universal

set -uo pipefail

cd "$(dirname "$0")/.." 2>/dev/null || true

STRICT="${LAVA_EMULATOR_RUNNER_STRICT:-1}"

EVIDENCE_MARKER='adb_devices_state|boot_seconds|connectedAndroidTest|connectedDebugAndroidTest|emulator-matrix'

# (2a) §6.I.4 per-row attestation field names, in key position. Three syntaxes
# are accepted because attestations in this repo are written in all three:
# JSON ("avd":), key=value run logs (avd=), and markdown table headers (| avd |).
ATTEST_FIELDS='adb_devices_state|boot_seconds|avd|avd_name|api_level|device|device_model|serial|emulator_serial|runner|runtime|gating|concurrent|test_class|test_passed|screen_density|form_factor|test_seconds'
ATTEST_SHAPE="(\"(${ATTEST_FIELDS})\"[[:space:]]*:)|(^|[[:space:],{])(${ATTEST_FIELDS})[[:space:]]*=|(\\|[[:space:]]*(${ATTEST_FIELDS})[[:space:]]*\\|)"

# (2b) Tokens that mark a line as a record of an INVOCATION or its OUTCOME, for
# plaintext run logs that carry no structured per-row fields.
RUN_TOKEN='gradlew|BUILD SUCCESSFUL|BUILD FAILED|am instrument|--tests|adb -s|adb shell|avdmanager|emulator-matrix[[:space:]]+-'

# Accept either `runner: containers-submodule` or `runner=containers-submodule`
# with optional surrounding quotes/whitespace.
RUNNER_OK='runner["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?containers-submodule'

# --------------------------------------------------------------------------
# Build the candidate file list.
# --------------------------------------------------------------------------
candidates=()
if [[ -n "${LAVA_EMULATOR_EVIDENCE_FILES:-}" ]]; then
  # Hermetic-test injection: explicit file list.
  while IFS= read -r line; do
    [[ -n "$line" ]] && candidates+=("$line")
  done <<< "$LAVA_EMULATOR_EVIDENCE_FILES"
else
  # Going-forward scope: only NEW files relative to HEAD under the evidence tree.
  #   - staged additions (diff-filter=A) — covers the pre-push commit case
  #   - untracked files — covers the "added but not yet staged" case
  if git rev-parse --git-dir >/dev/null 2>&1; then
    while IFS= read -r f; do
      [[ -n "$f" ]] && candidates+=("$f")
    done < <(
      {
        git diff --cached --name-only --diff-filter=A -- '.lava-ci-evidence/' 2>/dev/null || true
        git ls-files --others --exclude-standard -- '.lava-ci-evidence/' 2>/dev/null || true
      } | grep -E '\.(json|md)$' | sort -u
    )
  fi
fi

# records_emulator_run <file> — condition (1) AND condition (2). Returns 0 when
# the file is an attestation of a device run, 1 when the marker is a mention.
records_emulator_run() {
  local f=$1
  grep -qiE "$EVIDENCE_MARKER" "$f" 2>/dev/null || return 1        # (1)
  grep -qiE "$ATTEST_SHAPE" "$f" 2>/dev/null && return 0           # (2a)
  grep -iE "$EVIDENCE_MARKER" "$f" 2>/dev/null \
    | grep -qiE "$RUN_TOKEN" && return 0                           # (2b)
  return 1
}

violations=0
for f in "${candidates[@]}"; do
  [[ -f "$f" ]] || continue
  # Does this file record emulator execution?
  if records_emulator_run "$f"; then
    # Then it MUST carry the containers-submodule runner tag.
    if ! grep -qiE "$RUNNER_OK" "$f" 2>/dev/null; then
      echo "§6.X VIOLATION: $f records emulator execution but lacks 'runner: containers-submodule'." >&2
      echo "  → Gate emulator runs MUST go through the Containers submodule (§6.X)." >&2
      echo "    Add the runner tag to every attestation row, OR — if this is not an" >&2
      echo "    emulator-execution evidence file — it should not carry the markers" >&2
      echo "    ($EVIDENCE_MARKER)." >&2
      violations=$((violations + 1))
    fi
  fi
done

if [[ "$violations" -gt 0 ]]; then
  echo "" >&2
  echo "§6.X emulator-runner-tag gate: $violations new evidence file(s) record an" >&2
  echo "emulator run without 'runner: containers-submodule'. See §6.X clause (c)." >&2
  if [[ "$STRICT" != "1" ]]; then
    echo "(advisory mode — not failing)" >&2
    exit 0
  fi
  exit 1
fi

exit 0
