#!/usr/bin/env bash
# scripts/pipeline/phase-06-docs.sh — tasks.md T044 + T045: the pipeline's
# post-distribution documentation-refresh phase (FR-013 / SC-006 "zero manual
# documentation follow-up required").
#
# Two independent passes, in this order:
#
#   PASS 1 (T045) — stale-documentation fixes. Corrects the two concrete,
#     already-diagnosed staleness findings recorded in research.md R-002:
#       (a) docs/ARCHITECTURE.md's "**Pending (Phases C/D/E):**" note in the
#           "On-Device Lava API" section. Those phases HAVE shipped — the
#           note is stale in the "claims less than reality" direction.
#       (b) root CLAUDE.md's "## Project" section, which still describes a
#           companion "Ktor proxy server" and lists a `:proxy` Gradle module.
#           `:proxy` is NOT in settings.gradle.kts and has no source tree
#           (only a stale build/ directory) — it was superseded by
#           lava-api-go in SP-2. The real second Android artifact is
#           `:api-app`.
#     Both fixes are EXACT-STRING matched and therefore IDEMPOTENT: re-running
#     after a successful pass is a no-op, and if the surrounding prose has
#     since been rewritten by a human the fix simply does not apply and says
#     so, rather than guessing at a fuzzy match and mangling the document.
#
#   PASS 2 (T044) — derived-export regeneration. Every .md this phase
#     actually changed gets its .html + .pdf siblings regenerated through the
#     EXISTING scripts/sync-markdown-exports.sh (the §11.4.65 /
#     CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC convention), invoked as
#     `scripts/sync-markdown-exports.sh --regenerate <file>`. This phase does
#     NOT reimplement the pandoc/weasyprint pipeline, the in-scope/excluded
#     path rules, or the staleness definition — reimplementing any of them
#     would violate the same Local-Only CI/CD "no parallel implementation"
#     principle this pipeline already relies on for firebase-distribute.sh
#     (research.md R-003) and the systemd scripts (R-012).
#
# HONEST SCOPE NOTE on PASS 2's verdict: `sync-markdown-exports.sh
# --check-only` reports the state of the WHOLE repository, and this
# repository routinely carries export staleness caused by other, unrelated
# in-flight work. Failing this phase for those pre-existing problems would
# make it report a failure it did not cause and cannot fix without
# rewriting files it never touched. So the phase's own PASS/FAIL verdict is
# scoped precisely: it FAILS if and only if a file THIS phase changed still
# appears in --check-only's problem list afterwards. Every other problem
# --check-only reports is printed verbatim into the evidence log and named
# as pre-existing — reported, never silently swallowed (§6.J) and never
# counted against this phase.
#
# Usage:
#   scripts/pipeline/phase-06-docs.sh <run_id> [repo-path] [options]
#
# Options:
#   --dry-run             print the exact unified diff each PASS-1 fix WOULD
#                         apply and change NOTHING on disk (no doc edits, no
#                         export regeneration, no Evidence Record, no
#                         report.json append). Use this to review the fixes
#                         before letting the pipeline apply them.
#   --skip-exports        run PASS 1 only; skip PASS 2 entirely. Intended for
#                         hosts without pandoc/weasyprint, and recorded
#                         honestly in the Evidence Record as skipped rather
#                         than silently passed.
#   --regenerate-all      in PASS 2, additionally run
#                         `sync-markdown-exports.sh --regenerate-all` (the
#                         whole-repo sweep) instead of only the files this
#                         phase changed. Slow; opt-in.
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report). This script appends to that same report.json under phase
# name "docs_refresh"; it never creates a new run. In --dry-run mode the
# report.json precondition is still enforced but nothing is appended.
#
# Exit codes:
#   0 - every PASS-1 fix is applied-or-already-correct AND (unless
#       --skip-exports) every file this phase changed has fresh .html+.pdf
#       siblings, confirmed by re-running sync-markdown-exports.sh
#       --check-only and finding none of those files in its problem list;
#       one Evidence Record written and anti-bluff-validated. Under
#       --dry-run: the full would-be diff was printed and nothing written.
#   1 - a real failure (a fix could not be applied although its stale text
#       WAS present, an export regeneration failed, or a file this phase
#       changed is still missing/stale afterwards), OR the Evidence Record
#       was REJECTED by anti-bluff-validate.sh. Recorded as FAIL in
#       report.json's "docs_refresh" entry — never fabricated as success.
#   2 - usage/precondition error (missing run_id, report.json absent,
#       unknown option).

set -uo pipefail
# Deliberately NOT `set -e`: every risky step below (each fix application,
# each export regeneration, the post-run check) is explicitly guarded,
# because a non-zero result from any one of them is a REAL, WANTED signal
# for this phase's own outcome — not a script bug to abort on.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "$SCRIPT_DIR/lib/anti-bluff-validate.sh"

RUN_ID=""
REPO_PATH_OVERRIDE=""
DRY_RUN="false"
SKIP_EXPORTS="false"
REGENERATE_ALL="false"

_positional=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)        DRY_RUN="true"; shift ;;
    --skip-exports)   SKIP_EXPORTS="true"; shift ;;
    --regenerate-all) REGENERATE_ALL="true"; shift ;;
    -h|--help)
      sed -n '2,83p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    --*)
      echo "phase-06-docs: unknown option '$1'" >&2
      exit 2 ;;
    *)
      case "$_positional" in
        0) RUN_ID="$1" ;;
        1) REPO_PATH_OVERRIDE="$1" ;;
        *) echo "phase-06-docs: unexpected extra argument '$1'" >&2; exit 2 ;;
      esac
      _positional=$((_positional + 1))
      shift ;;
  esac
done

if [[ -z "$RUN_ID" ]]; then
  echo "phase-06-docs: usage: $0 <run_id> [repo-path] [--dry-run] [--skip-exports] [--regenerate-all]" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-06-docs: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-06"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"

COMBINED_LOG="${RAW_DIR}/docs-refresh-combined.log"
: > "$COMBINED_LOG"

_log() { echo "$*" | tee -a "$COMBINED_LOG"; }

START_TS=$(date +%s)

OVERALL_OK="true"
FAILURE_REASONS=()
_fail() {
  OVERALL_OK="false"
  FAILURE_REASONS+=("$1")
  _log "phase-06-docs: FAILED — $1"
}

_log "phase-06-docs: repo=${REPO_PATH}"
_log "phase-06-docs: run_id=${RUN_ID}"
_log "phase-06-docs: dry_run=${DRY_RUN} skip_exports=${SKIP_EXPORTS} regenerate_all=${REGENERATE_ALL}"
_log ""

CHANGED_FILES=()
FIX_OUTCOMES=()

# ---------------------------------------------------------------------------
# PASS 1 (T045) — stale-documentation fixes.
#
# Each fix is applied by a small python3 helper that: reads the file, looks
# for the EXACT stale text, and either rewrites it or reports that the text
# is absent. The helper prints one of three verdicts on stdout, which this
# script reads as the fix's real outcome:
#   APPLIED   — stale text found and replaced (or, under --dry-run, WOULD be)
#   NOOP      — stale text not present (already fixed, or the prose moved)
#   ERROR:<m> — something went genuinely wrong
# ---------------------------------------------------------------------------
_apply_fix() {
  # _apply_fix <fix_id> <target_file> <python_helper_body>
  local fix_id="$1" target="$2" helper="$3"
  local abs_target="${REPO_PATH}/${target}"

  _log "--- fix ${fix_id}: ${target} ---"

  if [[ ! -f "$abs_target" ]]; then
    _fail "[${fix_id}] target file does not exist: ${abs_target}"
    FIX_OUTCOMES+=("${fix_id}=MISSING-TARGET")
    return 1
  fi

  local out rc
  out="$(LAVA_FIX_TARGET="$abs_target" LAVA_FIX_DRYRUN="$DRY_RUN" python3 -c "$helper" 2>&1)"
  rc=$?

  if [[ $rc -ne 0 ]]; then
    _fail "[${fix_id}] helper exited ${rc}: ${out}"
    FIX_OUTCOMES+=("${fix_id}=ERROR")
    return 1
  fi

  local verdict
  verdict="$(printf '%s\n' "$out" | head -1)"
  # Everything after the first line is the unified diff (dry-run) or a note.
  printf '%s\n' "$out" | tail -n +2 | tee -a "$COMBINED_LOG"

  case "$verdict" in
    APPLIED)
      _log "[${fix_id}] verdict: APPLIED (stale text found and rewritten)"
      FIX_OUTCOMES+=("${fix_id}=APPLIED")
      if [[ "$DRY_RUN" != "true" ]]; then
        CHANGED_FILES+=("$target")
      fi
      ;;
    WOULD-APPLY)
      _log "[${fix_id}] verdict: WOULD-APPLY (dry run — nothing written)"
      FIX_OUTCOMES+=("${fix_id}=WOULD-APPLY")
      ;;
    NOOP)
      _log "[${fix_id}] verdict: NOOP (stale text not present — already correct, or the prose has moved)"
      FIX_OUTCOMES+=("${fix_id}=NOOP")
      ;;
    ERROR:*)
      _fail "[${fix_id}] ${verdict#ERROR:}"
      FIX_OUTCOMES+=("${fix_id}=ERROR")
      return 1
      ;;
    *)
      _fail "[${fix_id}] helper produced an unrecognized verdict: '${verdict}'"
      FIX_OUTCOMES+=("${fix_id}=ERROR")
      return 1
      ;;
  esac
  return 0
}

_log "=== PASS 1 (T045): stale-documentation fixes ==="

# --- Fix 1: docs/ARCHITECTURE.md "Pending (Phases C/D/E)" -------------------
read -r -d '' FIX1_HELPER <<'PYEOF' || true
import difflib
import os
import sys

target = os.environ["LAVA_FIX_TARGET"]
dry = os.environ["LAVA_FIX_DRYRUN"] == "true"

MARKER = "**Pending (Phases C/D/E):**"
NEW = (
    "**Landed (Phases C/D/E):** the `:core:apiengine` Kotlin wrapper, the `:api-app`\n"
    "Compose module, the foreground `ApiEngineService`, the on-device `NsdManager`\n"
    "advertiser (TXT `engine=go, platform=android, storage=sqlite`), the landing UI,\n"
    "and the instrumented Challenge tests — all shipped. Verified against the tree\n"
    "rather than inferred from prose: `settings.gradle.kts` declares `include(\":api-app\")`\n"
    "and `include(\":core:apiengine\")`;\n"
    "`api-app/src/main/kotlin/lava/api/app/service/ApiEngineService.kt` and\n"
    "`api-app/src/main/kotlin/lava/api/app/service/NsdMdnsAdvertiser.kt` exist; the\n"
    "instrumented Challenge tests live at\n"
    "`api-app/src/androidTest/kotlin/lava/api/app/challenges/`\n"
    "(`Challenge01ApiAppColdStartTest` through `Challenge07OpenClientTest`); device-gate\n"
    "evidence is recorded under `.lava-ci-evidence/1075-apiapp-gate/` and\n"
    "`.lava-ci-evidence/1076-apiapp-gate/`; and `scripts/firebase-distribute.sh` already\n"
    "ships an `--app client|api-app` selector that distributes it.\n"
)

with open(target, "r", encoding="utf-8") as f:
    original = f.read()

if MARKER not in original:
    print("NOOP")
    print("  (marker %r not present in %s)" % (MARKER, target))
    sys.exit(0)

lines = original.split("\n")
start = None
for i, line in enumerate(lines):
    if line.startswith(MARKER):
        start = i
        break
if start is None:
    print("ERROR:marker present in file body but not at the start of any line — refusing to guess at the paragraph boundary")
    sys.exit(0)

end = start
while end < len(lines) and lines[end].strip() != "":
    end += 1

new_lines = lines[:start] + NEW.rstrip("\n").split("\n") + lines[end:]
updated = "\n".join(new_lines)

diff = "".join(
    difflib.unified_diff(
        original.splitlines(keepends=True),
        updated.splitlines(keepends=True),
        fromfile="a/" + os.path.basename(target),
        tofile="b/" + os.path.basename(target),
        n=2,
    )
)

if dry:
    print("WOULD-APPLY")
    print(diff, end="")
    sys.exit(0)

with open(target, "w", encoding="utf-8") as f:
    f.write(updated)
print("APPLIED")
print(diff, end="")
PYEOF

_apply_fix "R-002a-architecture-pending-phases" "docs/ARCHITECTURE.md" "$FIX1_HELPER"

# --- Fix 2: root CLAUDE.md "## Project" section's stale :proxy --------------
read -r -d '' FIX2_HELPER <<'PYEOF' || true
import difflib
import os
import sys

target = os.environ["LAVA_FIX_TARGET"]
dry = os.environ["LAVA_FIX_DRYRUN"] == "true"

# Two exact, byte-level replacements, both inside the "## Project" section.
# Exact-match (never fuzzy) so a re-run is a no-op and a human rewrite of the
# surrounding prose makes this a reported NOOP instead of a mangled document.
REPLACEMENTS = [
    (
        ", plus a companion **Ktor proxy server** that scrapes upstream sites and "
        "exposes a JSON API to the app. Two artifacts share one Gradle build:",
        ", plus a companion **Lava API service** (`lava-api-go` — the Go service that "
        "superseded the retired Ktor `:proxy` module in SP-2) that scrapes upstream "
        "sites and exposes a JSON API to the app. Two Android artifacts share one "
        "Gradle build:",
    ),
    (
        "- `:proxy` — Ktor/Netty headless server, packaged as a fat JAR + Docker image.",
        "- `:api-app` — on-device Lava API server app, App ID `digital.vasic.lava.api` "
        "(debug suffix `.dev`); boots the same Lava API in-process on a phone/tablet, "
        "SQLite-backed and mDNS-advertised. (The former `:proxy` Ktor/Netty module is "
        "retired — it is absent from `settings.gradle.kts` and has no source tree; "
        "`lava-api-go` is its replacement.)",
    ),
]

with open(target, "r", encoding="utf-8") as f:
    original = f.read()

updated = original
applied = 0
for old, new in REPLACEMENTS:
    if old in updated:
        updated = updated.replace(old, new, 1)
        applied += 1

if applied == 0:
    print("NOOP")
    print("  (neither stale '## Project' string is present in %s — already corrected, "
          "or the section was rewritten)" % os.path.basename(target))
    sys.exit(0)

diff = "".join(
    difflib.unified_diff(
        original.splitlines(keepends=True),
        updated.splitlines(keepends=True),
        fromfile="a/" + os.path.basename(target),
        tofile="b/" + os.path.basename(target),
        n=2,
    )
)

if dry:
    print("WOULD-APPLY")
    print("  (%d of %d stale strings present)" % (applied, len(REPLACEMENTS)))
    print(diff, end="")
    sys.exit(0)

with open(target, "w", encoding="utf-8") as f:
    f.write(updated)
print("APPLIED")
print("  (%d of %d stale strings replaced)" % (applied, len(REPLACEMENTS)))
print(diff, end="")
PYEOF

_apply_fix "R-002b-claude-md-project-proxy" "CLAUDE.md" "$FIX2_HELPER"

_log ""

# ---------------------------------------------------------------------------
# PASS 2 (T044) — derived-export regeneration via the EXISTING
# scripts/sync-markdown-exports.sh. No reimplementation.
# ---------------------------------------------------------------------------
_log "=== PASS 2 (T044): derived-export regeneration (scripts/sync-markdown-exports.sh) ==="

SYNC_SH="${REPO_PATH}/scripts/sync-markdown-exports.sh"
EXPORTS_STATUS="not-run"
CHECK_OUTPUT=""
PREEXISTING_PROBLEMS=""

if [[ "$DRY_RUN" == "true" ]]; then
  EXPORTS_STATUS="skipped (dry run)"
  _log "phase-06-docs: PASS 2 skipped — --dry-run never regenerates exports"
elif [[ "$SKIP_EXPORTS" == "true" ]]; then
  EXPORTS_STATUS="skipped (--skip-exports)"
  _log "phase-06-docs: PASS 2 skipped by --skip-exports (honestly recorded as skipped, not as passed)"
elif [[ ! -x "$SYNC_SH" && ! -f "$SYNC_SH" ]]; then
  EXPORTS_STATUS="unavailable"
  _fail "scripts/sync-markdown-exports.sh not found at ${SYNC_SH} — cannot regenerate derived exports without reimplementing the §11.4.65 convention, which this phase refuses to do"
elif [[ ${#CHANGED_FILES[@]} -eq 0 && "$REGENERATE_ALL" != "true" ]]; then
  EXPORTS_STATUS="nothing-to-regenerate"
  _log "phase-06-docs: PASS 1 changed no files, so there is nothing whose exports need regenerating (pass --regenerate-all for the whole-repo sweep)"
else
  EXPORTS_STATUS="ran"
  if [[ "$REGENERATE_ALL" == "true" ]]; then
    _log "\$ ${SYNC_SH} --regenerate-all"
    if ( cd "$REPO_PATH" && bash "$SYNC_SH" --regenerate-all ) >>"$COMBINED_LOG" 2>&1; then
      _log "phase-06-docs: --regenerate-all succeeded"
    else
      _fail "scripts/sync-markdown-exports.sh --regenerate-all failed (see the combined log)"
    fi
  fi
  for f in "${CHANGED_FILES[@]}"; do
    _log "\$ ${SYNC_SH} --regenerate ${f}"
    if ( cd "$REPO_PATH" && bash "$SYNC_SH" --regenerate "$f" ) >>"$COMBINED_LOG" 2>&1; then
      _log "phase-06-docs: regenerated .html + .pdf siblings for ${f}"
    else
      _fail "scripts/sync-markdown-exports.sh --regenerate ${f} failed (see the combined log)"
    fi
  done
fi

# --- Post-run verification, scoped to what THIS phase changed --------------
#
# TWO checks, deliberately, because either one alone is satisfiable by the
# absence of the thing it is supposed to prove:
#
#   (a) a FIRST-HAND on-disk check of this phase's own outputs. The grep-based
#       check below asks "did --check-only complain about my files?", and
#       nothing complains when nothing is reported — so a
#       sync-markdown-exports.sh that exits 0 for every flag and does nothing
#       produced an empty problem list, matched no grep, and got this phase a
#       PASS with an Evidence Record asserting the siblings were verified
#       fresh while not one .html or .pdf existed anywhere on disk. Asserting
#       directly that the files this phase claims to have regenerated are
#       really there, and really not older than their .md, cannot be satisfied
#       by absence. This is not a reimplementation of the §11.4.65 export
#       pipeline (which this phase still refuses to duplicate) — it is this
#       phase checking its own claimed output, using the same
#       exists-and-not-older-than definition sync-markdown-exports.sh's own
#       check_only() uses.
#
#   (b) --check-only's EXIT CODE, which was previously never captured at all.
#       Its documented codes are 0 (clean), 1 (problems found) and 2 (usage
#       error). When the flag is renamed upstream, --check-only exits 2 having
#       printed only a usage line, that line contains no .html/.pdf path, the
#       grep matches nothing, and "the verification could not run" became
#       indistinguishable from "the verification passed".
EXPORT_CLAIM=""
if [[ "$EXPORTS_STATUS" == "ran" ]]; then
  # (a) first-hand: are this phase's own claimed outputs really on disk?
  MISSING_SIBLINGS=()
  for f in "${CHANGED_FILES[@]}"; do
    base="${f%.md}"
    for ext in html pdf; do
      sib="${REPO_PATH}/${base}.${ext}"
      if [[ ! -f "$sib" ]]; then
        MISSING_SIBLINGS+=("${base}.${ext} (absent)")
      elif [[ "${REPO_PATH}/${f}" -nt "$sib" ]]; then
        MISSING_SIBLINGS+=("${base}.${ext} (older than ${f})")
      fi
    done
  done
  if [[ ${#MISSING_SIBLINGS[@]} -gt 0 ]]; then
    _fail "PASS 2 verification (first-hand, on disk): sync-markdown-exports.sh reported success but these derived exports are still missing or stale: ${MISSING_SIBLINGS[*]}"
  else
    _log "phase-06-docs: PASS 2 first-hand check — every changed .md has an .html and a .pdf sibling on disk, none older than its .md"
  fi

  # (b) --check-only, with its exit code actually examined.
  _log "\$ ${SYNC_SH} --check-only   (repo-wide; this phase's verdict is scoped to its own changed files)"
  CHECK_OUTPUT="$( ( cd "$REPO_PATH" && bash "$SYNC_SH" --check-only ) 2>&1 )"
  CHECK_RC=$?
  _log "$CHECK_OUTPUT"
  _log "phase-06-docs: sync-markdown-exports.sh --check-only exited ${CHECK_RC}"

  if [[ "$CHECK_RC" -ne 0 && "$CHECK_RC" -ne 1 ]]; then
    # 0 = clean, 1 = problems found (normal; the scoped grep below decides
    # whether any of them are THIS phase's). Anything else means --check-only
    # did not run as a check at all, so this phase cannot claim to have
    # verified anything through it.
    _fail "PASS 2 verification: scripts/sync-markdown-exports.sh --check-only exited ${CHECK_RC}, which is neither 0 (clean) nor 1 (problems found) — the verification step itself did not run, so this phase cannot claim its changed files were re-checked. Its output was: $(printf '%s' "$CHECK_OUTPUT" | head -3 | tr '\n' ' ')"
  fi

  for f in "${CHANGED_FILES[@]}"; do
    base="${f%.md}"
    # Herestring, not a pipe. This site's direction is the DANGEROUS one: a
    # SIGPIPE-induced 141 makes the `if` false, the `_fail` below is skipped, and
    # the phase claims the export was regenerated while `--check-only` still
    # reports it stale. Latent today only because CHECK_OUTPUT is small.
    # See the full explanation in scripts/pipeline/lib/anti-bluff-validate.sh.
    if grep -qF -- "${base}.html" <<< "$CHECK_OUTPUT"; then
      _fail "PASS 2 verification: ${base}.html is still reported MISSING/STALE by sync-markdown-exports.sh --check-only after this phase regenerated it"
    fi
    if grep -qF -- "${base}.pdf" <<< "$CHECK_OUTPUT"; then
      _fail "PASS 2 verification: ${base}.pdf is still reported MISSING/STALE by sync-markdown-exports.sh --check-only after this phase regenerated it"
    fi
  done

  # Everything else --check-only flagged is pre-existing and NOT this phase's
  # doing. Reported verbatim, never silently swallowed, never counted against
  # this phase's verdict.
  PREEXISTING_PROBLEMS="$(printf '%s\n' "$CHECK_OUTPUT" | grep -E '^\s+(MISSING|STALE)' || true)"
  for f in "${CHANGED_FILES[@]}"; do
    base="${f%.md}"
    PREEXISTING_PROBLEMS="$(printf '%s\n' "$PREEXISTING_PROBLEMS" | grep -vF -- "${base}.html" | grep -vF -- "${base}.pdf" || true)"
  done
  if [[ -n "$PREEXISTING_PROBLEMS" ]]; then
    _log ""
    _log "phase-06-docs: NOTE — sync-markdown-exports.sh --check-only also reports the following"
    _log "               problems on files this phase did NOT touch. They pre-date this phase"
    _log "               and are reported, not swallowed; they do not affect this phase's verdict."
    _log "$PREEXISTING_PROBLEMS"
  fi
fi

_log ""

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

# ---------------------------------------------------------------------------
# Evidence Record + report.json
# ---------------------------------------------------------------------------
RESULT="PASS"
if [[ "$OVERALL_OK" != "true" ]]; then
  RESULT="FAIL"
fi

# The export claim was previously a fixed sentence appended on every PASS,
# asserting the --check-only re-verification unconditionally — including on
# the paths where PASS 2 provably never ran. It read, in one breath,
# "Derived-export pass: skipped (--skip-exports); every changed .md was
# re-checked with ... --check-only". An Evidence Record may only assert what
# this run really did.
if [[ "$EXPORTS_STATUS" == "ran" ]]; then
  EXPORT_CLAIM="every changed .md was verified first-hand on disk (an .html and a .pdf sibling present, neither older than its .md) and re-checked with scripts/sync-markdown-exports.sh --check-only (exit ${CHECK_RC:-unknown}), with none of their .html/.pdf siblings in its MISSING/STALE list"
else
  EXPORT_CLAIM="no --check-only re-verification was performed for this run, because the derived-export pass did not run (${EXPORTS_STATUS})"
fi

CHANGED_LIST="none"
if [[ ${#CHANGED_FILES[@]} -gt 0 ]]; then
  CHANGED_LIST="$(printf '%s, ' "${CHANGED_FILES[@]}")"
  CHANGED_LIST="${CHANGED_LIST%, }"
fi

RECORD_PATH=""
ANTI_BLUFF_STATUS="n/a (dry run)"

if [[ "$DRY_RUN" != "true" ]]; then
  if [[ "$RESULT" == "PASS" ]]; then
    ASSERTION_SUMMARY="R-002 stale-doc pass outcomes: ${FIX_OUTCOMES[*]} (exact-string matched, so APPLIED means the specific stale text was genuinely present and is now rewritten, and NOOP means it was genuinely absent — never a fuzzy guess). Files changed: ${CHANGED_LIST}. Derived-export pass: ${EXPORTS_STATUS}; ${EXPORT_CLAIM}."
  else
    ASSERTION_SUMMARY="FAILED: ${FAILURE_REASONS[*]}. R-002 stale-doc pass outcomes: ${FIX_OUTCOMES[*]}. Files changed: ${CHANGED_LIST}. Derived-export pass: ${EXPORTS_STATUS}; ${EXPORT_CLAIM}."
  fi

  if ! RECORD_PATH="$(write_evidence_record \
      "$PHASE_DIR" \
      "docs-refresh-stale-fixes-and-exports" \
      "hermetic-script" \
      "scripts/pipeline/phase-06-docs.sh ${RUN_ID} (R-002 stale-doc fixes + scripts/sync-markdown-exports.sh --regenerate per changed file)" \
      "$RESULT" \
      "$ASSERTION_SUMMARY" \
      "$COMBINED_LOG")"; then
    echo "phase-06-docs: ERROR — write_evidence_record failed" >&2
    append_phase_result "$RUN_ID" "docs_refresh" "FAIL" "$DURATION" "$PHASE_DIR" >/dev/null || true
    exit 1
  fi

  if validate_evidence_record "$RECORD_PATH" >/dev/null 2>&1; then
    ANTI_BLUFF_STATUS="validated"
  else
    ANTI_BLUFF_STATUS="$(jq -r '.anti_bluff_status' "$RECORD_PATH" 2>/dev/null || echo "REJECTED: unknown")"
    OVERALL_OK="false"
    RESULT="FAIL"
    FAILURE_REASONS+=("Evidence Record REJECTED by anti-bluff-validate.sh: ${ANTI_BLUFF_STATUS}")
  fi

  PHASE_RESULT="PASS"
  [[ "$RESULT" == "PASS" ]] || PHASE_RESULT="FAIL"
  append_phase_result "$RUN_ID" "docs_refresh" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR" >/dev/null
else
  PHASE_RESULT="$RESULT"
fi

echo ""
echo "phase-06-docs: SUMMARY"
echo "  PASS 1 (stale-doc fixes):  ${FIX_OUTCOMES[*]:-none attempted}"
echo "  files changed:             ${CHANGED_LIST}"
echo "  PASS 2 (derived exports):  ${EXPORTS_STATUS}"
if [[ -n "$RECORD_PATH" ]]; then
  echo "  Evidence Record:           ${RECORD_PATH} (anti_bluff_status=${ANTI_BLUFF_STATUS})"
fi
echo "  dry run:                   ${DRY_RUN}"
echo "  phase result:              ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-06-docs: FAILED —" >&2
  for r in "${FAILURE_REASONS[@]}"; do
    echo "  ${r}" >&2
  done
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "phase-06-docs: DRY RUN complete — nothing was written"
fi
exit 0
