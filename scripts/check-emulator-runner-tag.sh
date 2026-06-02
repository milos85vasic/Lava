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
# "Records emulator execution" heuristic (precise, to avoid false positives on
# non-emulator evidence): the file mentions at least one of the emulator-run
# markers below. Pure design docs, host-stability incidents, bluff-hunt logs,
# etc. that merely *quote* a marker in prose are unlikely to be NEW evidence
# files added in the same change, and the going-forward scope keeps them clear.
#   - adb_devices_state         (per-AVD forensic snapshot, §6.I.4 Group B)
#   - boot_seconds              (cold-boot timing of an emulator)
#   - connectedAndroidTest / connectedDebugAndroidTest  (instrumentation run)
#   - emulator-matrix           (the Containers matrix CLI)
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

violations=0
for f in "${candidates[@]}"; do
  [[ -f "$f" ]] || continue
  # Does this file record emulator execution?
  if grep -qiE "$EVIDENCE_MARKER" "$f" 2>/dev/null; then
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
