#!/usr/bin/env bash
# scripts/pipeline/phase-05a-changelog-entry.sh — tasks.md T042:
# authors the CHANGELOG.md entry AND the per-version
# `.lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md`
# snapshot for the exact version that is about to be distributed.
#
# ORDERING (research.md R-004 — the whole reason this phase exists as its
# own script, separate from phase-06-docs.sh): scripts/firebase-distribute.sh
# treats BOTH of these files as PRE-EXISTING INPUTS it verifies, never as
# something it authors:
#   Gate 2 (firebase-distribute.sh):
#       grep -qE "$CHANGELOG_PATTERN" "$LAVA_REPO_ROOT/CHANGELOG.md"  || exit 1
#   Gate 3 (firebase-distribute.sh):
#       [[ -f "$CHANGELOG_DIR/$APP_VERSION-$APP_VERSION_CODE.md" ]]   || exit 1
# So this phase MUST run BEFORE phase-05-distribute.sh, not after it. The
# spec's User Story 3 groups "distribute" and "refresh documentation"
# together, but the actual dependency direction for the CHANGELOG
# specifically is inverted from that naive reading.
#
# SCOPE — what this script does and, honestly, what it does NOT:
#   DOES satisfy: firebase-distribute.sh Gate 2 (CHANGELOG.md entry) and
#                 Gate 3 (per-version snapshot file).
#   Does NOT satisfy: Gate 1 (§6.P monotonic versionCode — that is the
#                 §6.Y version-bump commit's job, not a documentation job),
#                 Gates 4+5 (§6.R/§6.H pepper rotation), Gate 6 (APK
#                 content versionCode cross-check), or Gate 7 (§6.AK
#                 cycle-coverage — which reads the SEPARATE
#                 `cycle-coverage-map-<version>.yaml` + `<version>-test-
#                 evidence.{md,json}` artifacts, NOT this script's output).
#   Those gates are deliberately out of scope: each is a real, independent
#   safety property, and having a documentation-authoring script quietly
#   produce artifacts that make them pass would be exactly the §6.J bluff
#   class this project forbids.
#
# DRIFT PROTECTION (anti-bluff): the per-app channel names and Gate-2 regex
# TEMPLATES below are duplicated from scripts/firebase-distribute.sh's own
# `case "$SELECTED_APP"` block. A silent divergence there would make this
# script emit an entry the real gate does not match — a green-looking phase
# followed by a hard distribute refusal. To make that impossible to miss,
# this script greps firebase-distribute.sh at RUNTIME for each literal it
# copied and FAILS LOUDLY if the literal is no longer present. It never
# guesses, and it never proceeds on a suspected drift.
#
# SELF-VERIFICATION (anti-bluff): after writing, this script re-runs the
# gate's own two checks (the same `grep -qE "$CHANGELOG_PATTERN"` against
# the real CHANGELOG.md, and the same `[[ -f "$SNAPSHOT_FILE" ]]`) against
# what it just produced. A failure there is treated as THIS script's own
# bug and reported as FAIL — never as success.
#
# Usage:
#   scripts/pipeline/phase-05a-changelog-entry.sh <run_id> [repo-path] [options]
#
# Options:
#   --app client|api-app|both     which artifact(s) to author for (default: both)
#   --notes-file <app>=<path>     use <path>'s contents verbatim as the entry
#                                 body for <app> instead of auto-deriving it
#                                 from git history. Repeatable.
#   --title <app>=<text>          headline suffix for <app>'s entry. Repeatable.
#   --dry-run                     print exactly what WOULD be written to both
#                                 files and change NOTHING on disk (no
#                                 CHANGELOG edit, no snapshot, no Evidence
#                                 Record, no report.json append)
#   --force                       overwrite an existing snapshot file. NEVER
#                                 duplicates an existing CHANGELOG entry —
#                                 a matching entry is always left as-is.
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report, called by the top-level orchestrator — or, for a
# standalone/manual run, by the caller directly — before this phase runs).
# This script appends to that same report.json under phase name
# "changelog_entry"; it never creates a new run. In --dry-run mode the
# report.json precondition is still enforced (so a dry run proves the real
# run's preconditions too) but nothing is appended to it.
#
# IDEMPOTENCY: safe to re-run. An already-present CHANGELOG entry for the
# same version is detected via the gate's own regex and left untouched (a
# second entry would be a duplicate the real gate would happily accept but
# a human reader would not). An already-present snapshot is left untouched
# unless --force.
#
# Exit codes:
#   0 - for EVERY selected app, the CHANGELOG entry and the per-version
#       snapshot are present and were re-verified with firebase-distribute.sh's
#       own Gate 2 + Gate 3 checks; one Evidence Record per app written and
#       anti-bluff-validated (or, under --dry-run, the full would-be output
#       was printed and nothing was written).
#   1 - a real failure (drift detected against firebase-distribute.sh,
#       version unparseable, write failed, or the post-write self-verification
#       of Gate 2 / Gate 3 did not pass), OR an Evidence Record was REJECTED
#       by anti-bluff-validate.sh. Recorded as FAIL in report.json's
#       "changelog_entry" phase entry — never fabricated as success.
#   2 - usage/precondition error (missing run_id, report.json absent, bad
#       --app value, unreadable --notes-file).

set -uo pipefail
# Deliberately NOT `set -e`: every risky step below (version parsing, the
# drift greps, each file write, the post-write self-verification) is
# explicitly guarded, because a non-zero result from any one of them is a
# REAL, WANTED signal for this phase's own outcome — not a script bug to
# abort on. Relying on inherited errexit here would stop this script before
# it could record the honest failure it was invoked to observe.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "$SCRIPT_DIR/lib/anti-bluff-validate.sh"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
RUN_ID=""
REPO_PATH_OVERRIDE=""
SELECTED_APP="both"
DRY_RUN="false"
FORCE="false"
NOTES_FILE_CLIENT=""
NOTES_FILE_API_APP=""
TITLE_CLIENT=""
TITLE_API_APP=""

_positional=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --app)
      SELECTED_APP="${2:-}"; shift 2 ;;
    --app=*)
      SELECTED_APP="${1#*=}"; shift ;;
    --notes-file)
      _nf="${2:-}"; shift 2
      case "$_nf" in
        client=*)  NOTES_FILE_CLIENT="${_nf#client=}" ;;
        api-app=*) NOTES_FILE_API_APP="${_nf#api-app=}" ;;
        *) echo "phase-05a-changelog-entry: --notes-file expects <app>=<path> (app is 'client' or 'api-app'), got '${_nf}'" >&2; exit 2 ;;
      esac ;;
    --title)
      _t="${2:-}"; shift 2
      case "$_t" in
        client=*)  TITLE_CLIENT="${_t#client=}" ;;
        api-app=*) TITLE_API_APP="${_t#api-app=}" ;;
        *) echo "phase-05a-changelog-entry: --title expects <app>=<text> (app is 'client' or 'api-app'), got '${_t}'" >&2; exit 2 ;;
      esac ;;
    --dry-run) DRY_RUN="true"; shift ;;
    --force)   FORCE="true"; shift ;;
    -h|--help)
      sed -n '2,93p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    --*)
      echo "phase-05a-changelog-entry: unknown option '$1'" >&2
      exit 2 ;;
    *)
      case "$_positional" in
        0) RUN_ID="$1" ;;
        1) REPO_PATH_OVERRIDE="$1" ;;
        *) echo "phase-05a-changelog-entry: unexpected extra argument '$1'" >&2; exit 2 ;;
      esac
      _positional=$((_positional + 1))
      shift ;;
  esac
done

if [[ -z "$RUN_ID" ]]; then
  echo "phase-05a-changelog-entry: usage: $0 <run_id> [repo-path] [--app client|api-app|both] [--notes-file <app>=<path>] [--title <app>=<text>] [--dry-run] [--force]" >&2
  exit 2
fi

case "$SELECTED_APP" in
  client|api-app|both) ;;
  *) echo "phase-05a-changelog-entry: --app must be 'client', 'api-app', or 'both' (got '${SELECTED_APP}')" >&2; exit 2 ;;
esac

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

for _pair in "client:$NOTES_FILE_CLIENT" "api-app:$NOTES_FILE_API_APP"; do
  _p="${_pair#*:}"
  if [[ -n "$_p" && ! -r "$_p" ]]; then
    echo "phase-05a-changelog-entry: --notes-file for '${_pair%%:*}' is not readable: ${_p}" >&2
    exit 2
  fi
done

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-05a-changelog-entry: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-05a"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"

# Two log surfaces, on purpose.
#
# COMBINED_LOG is the whole run's narrative, including the pre-app preamble
# and anything written after the per-app loop. It is what a human reads to
# follow one invocation end to end.
#
# APP_LOG is the raw evidence for ONE app, and it is what that app's Evidence
# Record cites as its raw_output_ref. Both records used to cite the combined
# log, which made neither claim independently falsifiable: if one app's
# CHANGELOG entry were wrong, the file its record points at also contains the
# other app's successful output, and no line in it is attributable to one
# record rather than the other -- a FAILED app and a PASSED app would cite
# byte-identical evidence. An Evidence Record's raw file has to be the output
# of the thing that record is about, and nothing else.
COMBINED_LOG="${RAW_DIR}/changelog-entry-combined.log"
: > "$COMBINED_LOG"

# Set to a per-app raw log for the duration of each app's iteration; empty
# outside the loop, where output belongs only to the combined narrative.
APP_LOG=""

_log() {
  if [[ -n "$APP_LOG" ]]; then
    echo "$*" | tee -a "$COMBINED_LOG" "$APP_LOG"
  else
    echo "$*" | tee -a "$COMBINED_LOG"
  fi
}

START_TS=$(date +%s)

_log "phase-05a-changelog-entry: repo=${REPO_PATH}"
_log "phase-05a-changelog-entry: run_id=${RUN_ID}"
_log "phase-05a-changelog-entry: app=${SELECTED_APP} dry_run=${DRY_RUN} force=${FORCE}"
_log ""

DISTRIBUTE_SH="${REPO_PATH}/scripts/firebase-distribute.sh"
CHANGELOG_MD="${REPO_PATH}/CHANGELOG.md"

OVERALL_OK="true"
FAILURE_REASONS=()

_fail() {
  OVERALL_OK="false"
  FAILURE_REASONS+=("$1")
  _log "phase-05a-changelog-entry: FAILED — $1"
}

# ---------------------------------------------------------------------------
# Per-app configuration.
#
# These four values per app are COPIES of scripts/firebase-distribute.sh's
# own `case "$SELECTED_APP"` block (its "0. Resolve per-app configuration"
# section). `_assert_no_drift` below re-greps that file at runtime for each
# copied literal so a future edit there cannot silently desynchronize this
# script.
# ---------------------------------------------------------------------------
_resolve_app_config() {
  case "$1" in
    client)
      APP_GRADLE_FILE="app/build.gradle.kts"
      APP_CHANNEL="firebase-app-distribution"
      APP_LABEL_PREFIX="Lava-Android"
      APP_PATTERN_TMPL='Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android APP_VERSION \(APP_VERSION_CODE\)'
      APP_DISPLAY="Lava Android"
      APP_NOTES_FILE="$NOTES_FILE_CLIENT"
      APP_TITLE="$TITLE_CLIENT"
      ;;
    api-app)
      APP_GRADLE_FILE="api-app/build.gradle.kts"
      APP_CHANNEL="firebase-app-distribution-api-app"
      APP_LABEL_PREFIX="Lava-API-App"
      APP_PATTERN_TMPL='Lava-API-App-?APP_VERSION-?APP_VERSION_CODE|Lava-API-App APP_VERSION \(APP_VERSION_CODE\)'
      APP_DISPLAY="Lava API App"
      APP_NOTES_FILE="$NOTES_FILE_API_APP"
      APP_TITLE="$TITLE_API_APP"
      ;;
    *)
      return 1 ;;
  esac
  return 0
}

# _assert_no_drift <app> — verify every literal this script copied from
# firebase-distribute.sh is still verbatim present in that file.
_assert_no_drift() {
  local app="$1" missing=()
  if [[ ! -f "$DISTRIBUTE_SH" ]]; then
    _fail "[${app}] drift-check impossible: ${DISTRIBUTE_SH} does not exist (this phase's whole contract is to satisfy that script's Gates 2+3)"
    return 1
  fi
  grep -qF -- "$APP_GRADLE_FILE"   "$DISTRIBUTE_SH" || missing+=("GRADLE_VERSION_FILE '${APP_GRADLE_FILE}'")
  grep -qF -- "$APP_CHANNEL"       "$DISTRIBUTE_SH" || missing+=("CHANGELOG_CHANNEL '${APP_CHANNEL}'")
  grep -qF -- "$APP_PATTERN_TMPL"  "$DISTRIBUTE_SH" || missing+=("CHANGELOG_PATTERN_TMPL '${APP_PATTERN_TMPL}'")
  if [[ ${#missing[@]} -gt 0 ]]; then
    _fail "[${app}] DRIFT against scripts/firebase-distribute.sh — these literals are no longer present there: ${missing[*]}. Re-sync this script's _resolve_app_config with that file's 'case \$SELECTED_APP' block before distributing."
    return 1
  fi
  _log "[${app}] drift-check OK: gradle-file, channel, and Gate-2 pattern template all still verbatim in scripts/firebase-distribute.sh"
  return 0
}

# ---------------------------------------------------------------------------
# Per-app authoring
# ---------------------------------------------------------------------------
APPS=()
case "$SELECTED_APP" in
  both) APPS=(client api-app) ;;
  *)    APPS=("$SELECTED_APP") ;;
esac

RECORD_PATHS=()
SUMMARY_LINES=()

for APP in "${APPS[@]}"; do
  # Per-app raw evidence file, opened fresh for this app. Every _log and
  # _fail line from here to the end of this iteration lands in BOTH this file
  # and the combined narrative, so the per-app file is real captured output
  # rather than a re-derived summary of it. Seeded with the provenance a
  # reader needs to place it, since the run preamble lives in the combined log.
  APP_LOG="${RAW_DIR}/changelog-entry-${APP}.log"
  : > "$APP_LOG"
  _log "phase-05a-changelog-entry: per-app raw output for app=${APP}"
  _log "phase-05a-changelog-entry: repo=${REPO_PATH} run_id=${RUN_ID} dry_run=${DRY_RUN} force=${FORCE}"
  _log "phase-05a-changelog-entry: whole-run narrative (all apps): ${COMBINED_LOG#"$REPO_PATH"/}"
  _log "=== app: ${APP} ==="

  if ! _resolve_app_config "$APP"; then
    _fail "[${APP}] could not resolve per-app configuration"
    continue
  fi

  if ! _assert_no_drift "$APP"; then
    continue
  fi

  # --- version parse: byte-identical expressions to firebase-distribute.sh's
  #     "1. Resolve current Android version + build number" section. ---------
  GRADLE_PATH="${REPO_PATH}/${APP_GRADLE_FILE}"
  if [[ ! -f "$GRADLE_PATH" ]]; then
    _fail "[${APP}] ${APP_GRADLE_FILE} not found under ${REPO_PATH}"
    continue
  fi
  APP_VERSION="$(grep -E '^\s+versionName\s*=' "$GRADLE_PATH" \
      | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
  APP_VERSION_CODE="$(grep -E '^\s+versionCode\s*=' "$GRADLE_PATH" \
      | head -1 | sed 's/.*= \([0-9]*\).*/\1/')"
  if [[ -z "$APP_VERSION" || -z "$APP_VERSION_CODE" ]]; then
    _fail "[${APP}] could not parse versionName/versionCode from ${APP_GRADLE_FILE}"
    continue
  fi

  # Same two-pass substitution order firebase-distribute.sh uses (longer
  # token first, so the APP_VERSION pass cannot clobber the
  # "APP_VERSION_CODE" substring).
  CHANGELOG_PATTERN="${APP_PATTERN_TMPL//APP_VERSION_CODE/$APP_VERSION_CODE}"
  CHANGELOG_PATTERN="${CHANGELOG_PATTERN//APP_VERSION/$APP_VERSION}"

  CHANGELOG_DIR="${REPO_PATH}/.lava-ci-evidence/distribute-changelog/${APP_CHANNEL}"
  SNAPSHOT_FILE="${CHANGELOG_DIR}/${APP_VERSION}-${APP_VERSION_CODE}.md"
  LABEL="${APP_LABEL_PREFIX}-${APP_VERSION}-${APP_VERSION_CODE}"

  _log "[${APP}] artifact: ${APP_DISPLAY}"
  _log "[${APP}] version:  ${APP_VERSION} (${APP_VERSION_CODE})  label=${LABEL}"
  _log "[${APP}] channel:  ${APP_CHANNEL}"
  _log "[${APP}] Gate-2 pattern: ${CHANGELOG_PATTERN}"
  _log "[${APP}] Gate-3 snapshot: ${SNAPSHOT_FILE}"

  # --- previous published label -----------------------------------------
  PREV_LABEL="(none recorded)"
  PREV_SNAPSHOT=""
  PREV_CODE=""
  for ptr in "${CHANGELOG_DIR}/last-version-debug" "${CHANGELOG_DIR}/last-version"; do
    if [[ -f "$ptr" ]]; then
      PREV_CODE="$(tr -d '[:space:]' < "$ptr")"
      [[ -n "$PREV_CODE" ]] && break
    fi
  done
  if [[ -n "$PREV_CODE" ]]; then
    PREV_SNAPSHOT="$(find "$CHANGELOG_DIR" -maxdepth 1 -type f -name "*-${PREV_CODE}.md" 2>/dev/null | head -1)"
    if [[ -n "$PREV_SNAPSHOT" ]]; then
      PREV_VERSION="$(basename "$PREV_SNAPSHOT" .md)"
      PREV_LABEL="${APP_LABEL_PREFIX}-${PREV_VERSION}"
    else
      PREV_LABEL="${APP_LABEL_PREFIX} versionCode ${PREV_CODE}"
    fi
  fi
  _log "[${APP}] previous published: ${PREV_LABEL}"

  # --- entry body --------------------------------------------------------
  BODY=""
  BODY_SOURCE=""
  if [[ -n "$APP_NOTES_FILE" ]]; then
    BODY="$(cat "$APP_NOTES_FILE")"
    BODY_SOURCE="operator-authored notes file ${APP_NOTES_FILE}"
  else
    # Auto-derive from the REAL commit range since the previous published
    # snapshot last changed. This is a factual, verifiable range — not a
    # claim about what was fixed. §6.AK's claim-coverage gate reads the
    # separate cycle-coverage-map artifact, not these bullets.
    RANGE_ANCHOR=""
    if [[ -n "$PREV_SNAPSHOT" ]]; then
      RANGE_ANCHOR="$(git -C "$REPO_PATH" log -1 --format=%H -- "$PREV_SNAPSHOT" 2>/dev/null || true)"
    fi
    if [[ -n "$RANGE_ANCHOR" ]]; then
      RANGE="${RANGE_ANCHOR}..HEAD"
      BODY_SOURCE="git log ${RANGE} (anchor = the commit that last touched ${PREV_SNAPSHOT#"$REPO_PATH"/})"
    else
      RANGE="-n 20"
      BODY_SOURCE="git log -n 20 (no previous snapshot found to anchor a range — stated explicitly rather than implying a real since-last-release range)"
    fi
    # shellcheck disable=SC2086
    COMMITS="$(git -C "$REPO_PATH" log --no-merges --pretty=format:'- `%h` %s' $RANGE 2>/dev/null || true)"
    COMMIT_N=0
    if [[ -n "$COMMITS" ]]; then
      COMMIT_N="$(printf '%s\n' "$COMMITS" | wc -l | tr -d '[:space:]')"
    fi
    if [[ "$COMMIT_N" -gt 50 ]]; then
      BODY="$(printf '%s\n' "$COMMITS" | head -50)"
      BODY="${BODY}
- _(${COMMIT_N} commits in range; the 50 most recent are listed above. Full range: \`git log ${RANGE}\`.)_"
    elif [[ "$COMMIT_N" -eq 0 ]]; then
      BODY="- _No non-merge commits found in \`${RANGE}\`. This entry records the version identity only; see the Pipeline Run Report for what this build actually contains._"
    else
      BODY="$COMMITS"
    fi
    BODY="${BODY}

_Bullets above are the real commit subjects in \`${RANGE}\`, generated by \`scripts/pipeline/phase-05a-changelog-entry.sh\` — they are a factual commit listing, not a curated list of verified user-visible fixes. Any user-visible fix CLAIM that needs §6.AK coverage belongs in the cycle-coverage-map, authored separately._"
  fi
  _log "[${APP}] body source: ${BODY_SOURCE}"

  TITLE="${APP_TITLE:-pipeline run ${RUN_ID}}"
  HEAD_SHA="$(git -C "$REPO_PATH" rev-parse HEAD 2>/dev/null || echo unknown)"
  TODAY="$(date -u +%Y-%m-%d)"

  # --- evidence summary from report.json (contract: this phase reads it) --
  EV_SUMMARY="unavailable (report.json unreadable)"
  if command -v jq >/dev/null 2>&1; then
    EV_SUMMARY="$(jq -r '.evidence_summary | "total=\(.total) passed=\(.passed) failed=\(.failed) skipped=\(.skipped) rejected_by_anti_bluff=\(.rejected_by_anti_bluff)"' "$REPORT_PATH" 2>/dev/null || echo "unavailable (jq could not read evidence_summary)")"
  fi

  # --- compose the two artifacts ----------------------------------------
  SNAPSHOT_CONTENT="# ${LABEL} — ${TITLE}

**Previous published:** ${PREV_LABEL}.

${BODY}

## Build info
- App versionName: ${APP_VERSION}
- App versionCode: ${APP_VERSION_CODE}
- Distribution channel: ${APP_CHANNEL}
- Commit: ${HEAD_SHA}

## Evidence
- Pipeline Run Report: \`.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json\`
- Pipeline evidence summary at authoring time: ${EV_SUMMARY}
- Authored by: \`scripts/pipeline/phase-05a-changelog-entry.sh\` (pipeline run \`${RUN_ID}\`)
- Entry body derived from: ${BODY_SOURCE}
"

  CHANGELOG_ENTRY="## ${LABEL} — ${TODAY} (${TITLE})

**Previous published:** ${PREV_LABEL}.

${BODY}

**Coverage status (§6.AK / §6.Z):** per-version snapshot at \`.lava-ci-evidence/distribute-changelog/${APP_CHANNEL}/${APP_VERSION}-${APP_VERSION_CODE}.md\`; full pipeline evidence at \`.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json\` (${EV_SUMMARY}).
"

  # --- CHANGELOG.md: insert unless an entry already matches the gate -----
  ENTRY_ACTION=""
  if [[ ! -f "$CHANGELOG_MD" ]]; then
    _fail "[${APP}] ${CHANGELOG_MD} does not exist — refusing to create the project CHANGELOG from scratch inside a distribute-gate phase"
    continue
  fi
  if grep -qE "$CHANGELOG_PATTERN" "$CHANGELOG_MD"; then
    ENTRY_ACTION="already-present"
    _log "[${APP}] CHANGELOG.md already contains an entry matching the Gate-2 pattern — leaving it untouched (no duplicate)"
  else
    ENTRY_ACTION="inserted"
  fi

  SNAPSHOT_ACTION=""
  if [[ -f "$SNAPSHOT_FILE" && "$FORCE" != "true" ]]; then
    SNAPSHOT_ACTION="already-present"
  elif [[ -f "$SNAPSHOT_FILE" ]]; then
    SNAPSHOT_ACTION="overwritten (--force)"
  else
    SNAPSHOT_ACTION="written"
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    _log ""
    _log "--- [${APP}] DRY RUN: CHANGELOG.md entry action=${ENTRY_ACTION} (inserted at the top, before the first existing '## ' heading) ---"
    _log "$CHANGELOG_ENTRY"
    _log "--- [${APP}] DRY RUN: snapshot action=${SNAPSHOT_ACTION} at ${SNAPSHOT_FILE}, content ---"
    _log "$SNAPSHOT_CONTENT"
    _log "--- [${APP}] DRY RUN: end ---"
    SUMMARY_LINES+=("${APP}: DRY RUN — entry action=${ENTRY_ACTION}, snapshot action=${SNAPSHOT_ACTION} (${LABEL})")
    _log ""
    continue
  fi

  # --- real write: CHANGELOG.md ------------------------------------------
  if [[ "$ENTRY_ACTION" == "inserted" ]]; then
    TMP_CHANGELOG="${CHANGELOG_MD}.phase05a.$$"
    if ! ENTRY="$CHANGELOG_ENTRY" python3 - "$CHANGELOG_MD" "$TMP_CHANGELOG" <<'PYEOF'
import os
import sys

src, dst = sys.argv[1], sys.argv[2]
entry = os.environ["ENTRY"].rstrip("\n") + "\n"

with open(src, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Insert immediately before the first existing "## " release heading, so the
# newest release is first and the "# Changelog" H1 (plus any preamble) stays
# on top. If there is no "## " heading yet, append at the end of the file.
insert_at = len(lines)
for i, line in enumerate(lines):
    if line.startswith("## "):
        insert_at = i
        break

out = lines[:insert_at] + [entry, "\n"] + lines[insert_at:]
with open(dst, "w", encoding="utf-8") as f:
    f.writelines(out)
PYEOF
    then
      _fail "[${APP}] failed to compose the updated CHANGELOG.md"
      rm -f -- "$TMP_CHANGELOG"
      continue
    fi
    if ! mv -f -- "$TMP_CHANGELOG" "$CHANGELOG_MD"; then
      _fail "[${APP}] failed to move the updated CHANGELOG.md into place"
      rm -f -- "$TMP_CHANGELOG"
      continue
    fi
    _log "[${APP}] CHANGELOG.md entry inserted"
  fi

  # --- real write: snapshot ---------------------------------------------
  if [[ "$SNAPSHOT_ACTION" != "already-present" ]]; then
    if ! mkdir -p "$CHANGELOG_DIR"; then
      _fail "[${APP}] could not create ${CHANGELOG_DIR}"
      continue
    fi
    if ! printf '%s' "$SNAPSHOT_CONTENT" > "$SNAPSHOT_FILE"; then
      _fail "[${APP}] could not write ${SNAPSHOT_FILE}"
      continue
    fi
    _log "[${APP}] snapshot ${SNAPSHOT_ACTION}: ${SNAPSHOT_FILE}"
  else
    _log "[${APP}] snapshot already present (use --force to overwrite): ${SNAPSHOT_FILE}"
  fi

  # --- SELF-VERIFICATION: re-run the real gate's own two checks ----------
  GATE2_OK="false"
  GATE3_OK="false"
  if grep -qE "$CHANGELOG_PATTERN" "$CHANGELOG_MD"; then
    GATE2_OK="true"
  fi
  if [[ -f "$SNAPSHOT_FILE" ]]; then
    GATE3_OK="true"
  fi
  _log "[${APP}] self-verify Gate 2 (grep -qE '<pattern>' CHANGELOG.md): ${GATE2_OK}"
  _log "[${APP}] self-verify Gate 3 ([[ -f '<snapshot>' ]]): ${GATE3_OK}"

  if [[ "$GATE2_OK" != "true" || "$GATE3_OK" != "true" ]]; then
    _fail "[${APP}] post-write self-verification failed (Gate2=${GATE2_OK} Gate3=${GATE3_OK}) — firebase-distribute.sh WOULD refuse this version. Treating as this script's own bug, not as success."
    continue
  fi

  # The REAL size of the file that is really on disk. This used to be
  # ${#SNAPSHOT_CONTENT} — the length of the text this script composed in
  # memory — which is wrong twice over. On the already-present path the script
  # deliberately writes nothing, so that number described notes that were
  # never written (a 0-byte snapshot on disk was reported as "1084 bytes of
  # release notes"); and even on the write path ${#VAR} counts CHARACTERS, not
  # bytes, so the multi-byte UTF-8 in these notes made it wrong anyway. An
  # Evidence Record may only state measurements taken from the artifact that
  # exists.
  SNAPSHOT_BYTES="$(wc -c < "$SNAPSHOT_FILE" 2>/dev/null | tr -d '[:space:]')"
  [[ -n "$SNAPSHOT_BYTES" ]] || SNAPSHOT_BYTES=0

  # Gate 3 in firebase-distribute.sh is only `[[ -f "$SNAPSHOT_FILE" ]]`, an
  # existence test an EMPTY file passes — the same shape
  # anti-bluff-validate.sh Rule 3 already rejects for raw_output_ref ("exists
  # but is empty (0 bytes)"). A zero-byte per-version snapshot is not release
  # notes, and certifying one would hand the distribute gate an input that
  # satisfies its check while containing nothing.
  if [[ "$SNAPSHOT_BYTES" -eq 0 ]]; then
    _fail "[${APP}] the per-version snapshot at ${SNAPSHOT_FILE#"$REPO_PATH"/} is 0 bytes (${SNAPSHOT_ACTION}). firebase-distribute.sh Gate 3 is only an existence test, which an empty file passes, but an empty release-notes file is not release notes — re-run with --force to overwrite it with this run's composed notes."
    continue
  fi

  # --- Evidence Record ---------------------------------------------------
  ASSERTION_SUMMARY="firebase-distribute.sh Gate 2 re-run against the real ${CHANGELOG_MD#"$REPO_PATH"/} with that script's own resolved regex '${CHANGELOG_PATTERN}' matched (entry ${ENTRY_ACTION}); Gate 3 re-run confirmed the per-version snapshot exists at ${SNAPSHOT_FILE#"$REPO_PATH"/} (${SNAPSHOT_ACTION}), measured on disk at ${SNAPSHOT_BYTES} bytes of release notes for ${LABEL}; drift-check confirmed channel '${APP_CHANNEL}' and the Gate-2 pattern template are still verbatim present in scripts/firebase-distribute.sh"

  RECORD_PATH=""
  if ! RECORD_PATH="$(write_evidence_record \
      "$PHASE_DIR" \
      "changelog-entry-${APP}-${APP_VERSION}-${APP_VERSION_CODE}" \
      "hermetic-script" \
      "grep -qE '${CHANGELOG_PATTERN}' CHANGELOG.md && test -f '${SNAPSHOT_FILE#"$REPO_PATH"/}'" \
      "PASS" \
      "$ASSERTION_SUMMARY" \
      "$APP_LOG")"; then
    _fail "[${APP}] write_evidence_record failed"
    continue
  fi

  if validate_evidence_record "$RECORD_PATH" >/dev/null 2>&1; then
    _log "[${APP}] Evidence Record anti_bluff_status=validated (${RECORD_PATH})"
  else
    _abs="$(jq -r '.anti_bluff_status' "$RECORD_PATH" 2>/dev/null || echo "REJECTED: unknown")"
    _fail "[${APP}] Evidence Record REJECTED by anti-bluff-validate.sh: ${_abs} (${RECORD_PATH})"
    continue
  fi

  RECORD_PATHS+=("$RECORD_PATH")
  SUMMARY_LINES+=("${APP}: ${LABEL} — entry ${ENTRY_ACTION}, snapshot ${SNAPSHOT_ACTION}, Gate2=OK Gate3=OK")
  _log ""
done

# Back to the whole-run narrative: nothing after this point belongs to a
# single app's raw evidence file.
APP_LOG=""

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

PHASE_RESULT="PASS"
if [[ "$OVERALL_OK" != "true" ]]; then
  PHASE_RESULT="FAIL"
fi

if [[ "$DRY_RUN" != "true" ]]; then
  append_phase_result "$RUN_ID" "changelog_entry" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"
fi

echo ""
echo "phase-05a-changelog-entry: SUMMARY"
for line in "${SUMMARY_LINES[@]}"; do
  echo "  ${line}"
done
if [[ ${#RECORD_PATHS[@]} -gt 0 ]]; then
  for rp in "${RECORD_PATHS[@]}"; do
    echo "  Evidence Record: ${rp}"
  done
fi
echo "  dry run:      ${DRY_RUN}"
echo "  phase result: ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-05a-changelog-entry: FAILED —" >&2
  for r in "${FAILURE_REASONS[@]}"; do
    echo "  ${r}" >&2
  done
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "phase-05a-changelog-entry: DRY RUN complete — nothing was written"
else
  echo "phase-05a-changelog-entry: CHANGELOG entry + per-version snapshot present and gate-verified for every selected app"
fi
exit 0
