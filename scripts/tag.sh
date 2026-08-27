#!/usr/bin/env bash
#
# tag.sh — Lava release-tagging tool.
#
# Tags each app/service with `Lava-<App>-<versionName>-<versionCode>`,
# pushes every tag to all configured upstream remotes, then bumps the
# corresponding versionName/versionCode in source files and pushes the
# bump commit.
#
# See docs/TAGGING.md for the full operator guide.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ----------------------------------------------------------------------
# Defaults
# ----------------------------------------------------------------------
DRY_RUN=false
DO_BUMP=true
DO_PUSH=true
BUMP_PART="patch"
TARGET_APP="all"
NO_EVIDENCE_REQUIRED=false
declare -a EXPLICIT_REMOTES=()

# Default upstreams (used if --remote is not given).
DEFAULT_REMOTES=(github gitlab)

# Apps registry.
SUPPORTED_APPS=(android api-go)

# ----------------------------------------------------------------------
# Logging
# ----------------------------------------------------------------------
_color() { [[ -t 1 ]]; }

log()   { if _color; then printf '\033[1;36m[tag]\033[0m %s\n'      "$*"; else printf '[tag] %s\n'      "$*"; fi; }
warn()  { if _color; then printf '\033[1;33m[tag:warn]\033[0m %s\n' "$*" >&2; else printf '[tag:warn] %s\n' "$*" >&2; fi; }
err()   { if _color; then printf '\033[1;31m[tag:err]\033[0m %s\n'  "$*" >&2; else printf '[tag:err] %s\n'  "$*" >&2; fi; }
dry()   { if _color; then printf '\033[1;35m[dry]\033[0m %s\n'      "$*"; else printf '[dry] %s\n'      "$*"; fi; }
die()   { err "$*"; exit 1; }

# ----------------------------------------------------------------------
# Help
# ----------------------------------------------------------------------
print_help() {
  cat <<'EOF'
Usage: scripts/tag.sh [OPTIONS]

Tag every Lava app/service at its current version, push the tags to all
configured upstream remotes, then bump versionName/versionCode for the
tagged apps and push the bump commit.

Tag format
    Lava-<App>-<versionName>-<versionCode>
  examples
    Lava-Android-1.0.0-1008
    Lava-API-Go-2.0.0-2000

OPTIONS
  -h, --help              Show this help and exit.
  -n, --dry-run           Print every action; perform no git or file changes.
  -a, --app <name>        Restrict to a single app: 'android', 'api-go',
                          or 'all' (default: all).
      --bump <part>       Which semver part of versionName to bump after
                          tagging: 'major' | 'minor' | 'patch'   (default: patch).
                          versionCode is always incremented by 1.
      --no-bump           Skip the post-tag version bump.
      --no-push           Do not push tags or bump commit; tag/commit locally only.
      --no-evidence-required
                          (api-go only) Bypass the .lava-ci-evidence/<commit>.json
                          requirement. Reserved for --dry-run rehearsals and
                          documented operator emergencies; routine releases
                          MUST run lava-api-go/scripts/pretag-verify.sh first
                          to produce the evidence file.
      --remote <name>     Push only to this named git remote. Repeat to push to
                          a custom subset (e.g. --remote github --remote gitlab).
                          When omitted, every default upstream that is
                           configured is used: github, gitlab.

EXAMPLES
  # Preview a full tag pass; no git mutations are performed.
  scripts/tag.sh --dry-run

  # Tag the Android app only and bump its minor version afterwards.
  scripts/tag.sh --app android --bump minor

  # Tag locally without pushing anywhere (useful for rehearsals).
  scripts/tag.sh --no-push

  # Tag and push only to GitHub and GitLab.
  scripts/tag.sh --remote github --remote gitlab

  # Tag without bumping (useful for re-tagging the same release commit).
  scripts/tag.sh --no-bump

EXIT CODES
  0   Success.
  1   Misuse, validation failure, dirty working tree, or git error.

For the full operator guide see docs/TAGGING.md.
EOF
}

# ----------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)        print_help; exit 0 ;;
    -n|--dry-run)     DRY_RUN=true; shift ;;
    -a|--app)         TARGET_APP="${2:-}"; shift 2 ;;
    --bump)           BUMP_PART="${2:-}"; shift 2 ;;
    --no-bump)        DO_BUMP=false; shift ;;
    --no-push)        DO_PUSH=false; shift ;;
    --no-evidence-required) NO_EVIDENCE_REQUIRED=true; shift ;;
    --remote)         EXPLICIT_REMOTES+=("${2:-}"); shift 2 ;;
    *)                die "Unknown option: $1 (try --help)" ;;
  esac
done

case "$BUMP_PART" in
  major|minor|patch) ;;
  *) die "--bump must be one of: major, minor, patch (got: '$BUMP_PART')" ;;
esac

# Validate --app early (before any git operations).
case "$TARGET_APP" in
  all) ;;
  *)
    _ok=false
    for s in "${SUPPORTED_APPS[@]}"; do
      [[ "$s" == "$TARGET_APP" ]] && _ok=true
    done
    $_ok || die "--app must be one of: ${SUPPORTED_APPS[*]}, all (got: '$TARGET_APP')"
    ;;
esac

# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
run() {
  if $DRY_RUN; then
    dry "$*"
  else
    log "$*"
    "$@"
  fi
}

# Read a value from a build file; abort the script if it cannot be parsed.
read_value() {
  local file="$1" pattern="$2" extract="$3" label="$4"
  local val
  val="$(grep -E "$pattern" "$file" | head -n1 | sed -E "$extract" || true)"
  [[ -n "$val" ]] || die "Failed to read $label from $file (pattern: $pattern)"
  printf '%s' "$val"
}

read_android_version_name() {
  read_value "$REPO_ROOT/app/build.gradle.kts" \
    'versionName *= *"[^"]+"' \
    's/.*versionName *= *"([^"]+)".*/\1/' \
    "Android versionName"
}
read_android_version_code() {
  read_value "$REPO_ROOT/app/build.gradle.kts" \
    'versionCode *= *[0-9]+' \
    's/.*versionCode *= *([0-9]+).*/\1/' \
    "Android versionCode"
}
read_apigo_version_name() {
  read_value "$REPO_ROOT/lava-api-go/internal/version/version.go" \
    '^[[:space:]]*Name *= *"[^"]+"' \
    's/.*Name *= *"([^"]+)".*/\1/' \
    "API-Go Name"
}
read_apigo_version_code() {
  read_value "$REPO_ROOT/lava-api-go/internal/version/version.go" \
    '^[[:space:]]*Code *= *[0-9]+' \
    's/.*Code *= *([0-9]+).*/\1/' \
    "API-Go Code"
}

# ----------------------------------------------------------------------
# Evidence-content assertions (anti-vacuity helpers)
# ----------------------------------------------------------------------
# Presence-of-path is not evidence. Every helper below asserts that an
# artifact carries real, parseable content of the shape its gate's own
# contract claims for it, so that a gate cannot be satisfied by `mkdir`
# plus `touch`.

# _scan_changed_since <base_commit>
#
# Lists the paths changed between <base_commit> and HEAD, and partitions
# them into "under .lava-ci-evidence/" and everything else. Sets:
#   _STALENESS_EXAMINED   count of changed paths actually examined
#   _STALENESS_OFFENDERS  the non-evidence paths among them
# Returns 0 on a successful scan, 2 if git could not produce the listing
# (a failed listing is NEVER reported as "nothing changed").
#
# LVA-135 shape — why this is not a pipeline. The obvious spelling,
#     git diff --name-only "$base..HEAD" -- | grep -qvE '^\.lava-ci-evidence/'
# is size-dependent under this script's `set -Eeuo pipefail` (line 12):
# `grep -q` exits at the FIRST matching path and closes the pipe, so once
# the listing exceeds the 64 KiB pipe buffer git is killed by SIGPIPE and
# exits 141; `pipefail` promotes 141 to the pipeline's status and the
# enclosing `if` reads FALSE. A MATCH is thereby delivered to the caller
# as a NO-MATCH. Measured 2026-08-26 against the real gate: 1 changed
# file -> caught 10/10; 2000 changed files -> caught 0/10, with the gate
# printing the positively false claim "only .lava-ci-evidence/ changed
# since". The gate was weakest exactly where the evidence was most stale.
# The listing is therefore captured whole, git's own status is checked
# explicitly, and the scan uses a reader that consumes to EOF: no pipe,
# no early-exiting consumer, no size-dependent verdict.
_STALENESS_EXAMINED=0
declare -a _STALENESS_OFFENDERS=()
_scan_changed_since() {
  local base="$1" listing line
  _STALENESS_EXAMINED=0
  _STALENESS_OFFENDERS=()
  if ! listing="$(git diff --name-only "${base}..HEAD" --)"; then
    return 2
  fi
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    _STALENESS_EXAMINED=$((_STALENESS_EXAMINED + 1))
    [[ "$line" == .lava-ci-evidence/* ]] && continue
    _STALENESS_OFFENDERS+=("$line")
  done <<< "$listing"
  return 0
}

# _pack_json_object_ok <path>
#
# True iff <path> is a non-empty REGULAR file whose bytes parse as a JSON
# object. A directory fails (-d check, and -f would already reject it); a
# zero-byte file fails (-s); `null`, `[]`, a bare number or string fail
# the type check. Sets _PACK_JSON_REASON to a human-readable reason on
# failure.
_PACK_JSON_REASON=""
_pack_json_object_ok() {
  local p="$1" t
  _PACK_JSON_REASON=""
  if [[ -d "$p" ]]; then _PACK_JSON_REASON="is a directory, not a file"; return 1; fi
  if [[ ! -f "$p" ]]; then _PACK_JSON_REASON="missing"; return 1; fi
  if [[ ! -s "$p" ]]; then _PACK_JSON_REASON="is zero bytes — an empty file certifies nothing"; return 1; fi
  if ! command -v jq >/dev/null 2>&1; then
    _PACK_JSON_REASON="jq is required to verify evidence-pack content and is not on PATH"
    return 1
  fi
  t="$(jq -r 'type' "$p" 2>/dev/null || true)"
  if [[ "$t" != "object" ]]; then
    _PACK_JSON_REASON="does not parse as a JSON object (jq reports: ${t:-parse-error})"
    return 1
  fi
  return 0
}

# _pack_dir_has_json <dir>
#
# True iff <dir> is a directory containing at least one *.json that is
# itself a non-empty regular file parsing as a JSON object. An EMPTY
# directory fails: the pack contract names "<recent>.json" INSIDE these
# directories as the evidence, and `-d` never looks inside.
_pack_dir_has_json() {
  local d="$1" j
  local -a jsons=()
  _PACK_JSON_REASON=""
  if [[ ! -d "$d" ]]; then _PACK_JSON_REASON="missing"; return 1; fi
  mapfile -t jsons < <(find "$d" -maxdepth 1 -type f -name '*.json' 2>/dev/null | sort)
  if (( ${#jsons[@]} == 0 )); then
    _PACK_JSON_REASON="contains no *.json file — an empty directory is not evidence"
    return 1
  fi
  for j in "${jsons[@]}"; do
    if _pack_json_object_ok "$j"; then
      _PACK_JSON_REASON=""
      return 0
    fi
  done
  _PACK_JSON_REASON="contains ${#jsons[@]} *.json file(s), none of which is a non-empty JSON object"
  return 1
}

# _require_ci_sh_json <tag_id> <path>
#
# Asserts the three facts the evidence-pack header claims ci.sh.json
# certifies: WHICH mode ran, that every gate passed, and WHICH commit it
# ran against. scripts/ci.sh writes `mode` + `sha` under its own
# timestamped evidence directory; this file is the operator's summary of
# that run, and this gate IS its contract:
#
#   { "mode": "--full", "all_gates_passed": true, "sha": "<40-hex>" }
#
# `sha` must be HEAD or an ancestor of HEAD, and nothing outside
# .lava-ci-evidence/ may have changed between it and HEAD — the same
# freshness rule require_evidence_for_apigo applies, for the same reason:
# a CI run against a commit whose code has since changed certifies the
# code that was tested, not the code about to be tagged.
_require_ci_sh_json() {
  local tag_id="$1" f="$2"
  local mode passed sha

  mode="$(jq -r '.mode // empty' "$f" 2>/dev/null || true)"
  # NOTE: `.all_gates_passed // false` cannot be used — jq's `//` also
  # fires on the boolean `false`, which would make an ABSENT field and an
  # explicit `false` indistinguishable. Same hazard, same explicit
  # has()-based spelling, as the Group B `gating` gate below.
  passed="$(jq -r 'if has("all_gates_passed") then (.all_gates_passed | tostring) else "<absent>" end' "$f" 2>/dev/null || true)"
  sha="$(jq -r '.sha // empty' "$f" 2>/dev/null || true)"

  if [[ "$mode" != "--full" && "$mode" != "full" ]]; then
    die "Cannot tag $tag_id: $f does not record a full CI run (.mode = '${mode:-<absent>}', expected '--full'). The evidence pack certifies that scripts/ci.sh --full ran green; a file that does not say which mode ran certifies nothing."
  fi
  if [[ "$passed" != "true" ]]; then
    die "Cannot tag $tag_id: $f does not record a green CI run (.all_gates_passed = '${passed}', expected true)."
  fi
  if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    die "Cannot tag $tag_id: $f does not record which commit CI ran against (.sha = '${sha:-<absent>}', expected a 40-character commit SHA)."
  fi
  if ! git cat-file -e "${sha}^{commit}" 2>/dev/null; then
    die "Cannot tag $tag_id: $f records .sha = $sha, which is not a commit in this repository."
  fi
  if ! git merge-base --is-ancestor "$sha" HEAD 2>/dev/null; then
    die "Cannot tag $tag_id: $f records .sha = $sha, which is not HEAD nor an ancestor of HEAD. CI evidence from an unrelated commit does not certify the commit being tagged."
  fi
  local rc=0
  _scan_changed_since "$sha" || rc=$?
  if (( rc == 2 )); then
    die "Cannot tag $tag_id: 'git diff --name-only ${sha}..HEAD' failed, so the freshness of $f cannot be established. Refusing to tag on unverifiable CI evidence."
  fi
  if (( ${#_STALENESS_OFFENDERS[@]} > 0 )); then
    die "Cannot tag $tag_id: $f records a CI run at $sha but non-evidence files have changed since (${#_STALENESS_OFFENDERS[@]} of ${_STALENESS_EXAMINED} changed path(s), e.g. ${_STALENESS_OFFENDERS[0]}). Re-run scripts/ci.sh --full against the commit being tagged."
  fi
  log "[android] ci.sh evidence OK: mode=$mode, all_gates_passed=true, sha=$sha (examined ${_STALENESS_EXAMINED} changed path(s) since, all under .lava-ci-evidence/)"
}

# SP-3a Phase 5 Task 5.21 — Android evidence-pack gate.
#
# Refuse to tag the Android app at version V unless
# .lava-ci-evidence/Lava-Android-<V>/ exists with the required
# subfiles certifying that:
#   - scripts/ci.sh --full ran green against this commit (ci.sh.json)
#   - all 8 SP-3a Challenge Tests have an attestation file
#     (challenges/C{1..8}.json with status: VERIFIED)
#   - the bluff-audit hunt has been run since the commit
#     (bluff-audit/<recent>.json)
#   - the submodules/tracker_sdk mirror smoke test passed
#     (mirror-smoke/<recent>.json)
#   - operator real-device verification per Task 5.22 is complete
#     (real-device-verification.md status: VERIFIED)
#
# Per Sixth Law clause 5: this is the mechanical gate that prevents
# a green-CI / broken-on-real-device tag. It implements the Seventh
# Law clause 3 pre-tag real-device attestation in mechanical form.
require_evidence_for_android() {
  local tag_suffix="Android" vname="$1" vcode="$2"
  if $NO_EVIDENCE_REQUIRED; then
    warn "[android] --no-evidence-required: bypassing SP-3a evidence-pack gate"
    return 0
  fi
  if $DRY_RUN; then
    warn "[android] --dry-run: bypassing SP-3a evidence-pack gate"
    return 0
  fi
  local tag_id="Lava-${tag_suffix}-${vname}-${vcode}"
  local pack_dir="$REPO_ROOT/.lava-ci-evidence/${tag_id}"

  if [[ ! -d "$pack_dir" ]]; then
    die "Cannot tag $tag_id: missing evidence pack at $pack_dir. Operator MUST run scripts/ci.sh --full and complete real-device verification per SP-3a Task 5.22 before tagging."
  fi

  # Required subfiles (SP-3a Phase 5 Task 5.21 contract).
  #
  # These tests assert CONTENT, not the existence of a path. `-f`/`-d`
  # alone answer only "does a path of the right type exist", which two
  # `mkdir`s and a `touch` satisfy. Measured 2026-08-26 against the real
  # gate: a pack whose ci.sh.json was ZERO BYTES and whose bluff-audit/
  # and mirror-smoke/ were EMPTY DIRECTORIES reached "SP-3a evidence pack
  # OK" and exit 0 — byte-for-byte the same verdict as a pack carrying
  # real evidence. This function's own header states that the pack
  # certifies scripts/ci.sh --full ran green, that the bluff-audit hunt
  # ran, and that the mirror smoke test passed. An empty path certifies
  # none of those. Presence is not evidence.
  local missing=()
  _pack_json_object_ok "$pack_dir/ci.sh.json" || missing+=("ci.sh.json (${_PACK_JSON_REASON})")
  [[ -d "$pack_dir/challenges" ]] || missing+=("challenges/")
  _pack_dir_has_json "$pack_dir/bluff-audit" || missing+=("bluff-audit/ (${_PACK_JSON_REASON})")
  _pack_dir_has_json "$pack_dir/mirror-smoke" || missing+=("mirror-smoke/ (${_PACK_JSON_REASON})")
  if [[ ! -f "$pack_dir/real-device-verification.md" ]]; then
    missing+=("real-device-verification.md")
  elif [[ ! -s "$pack_dir/real-device-verification.md" ]]; then
    missing+=("real-device-verification.md (is zero bytes)")
  fi

  if (( ${#missing[@]} > 0 )); then
    die "Cannot tag $tag_id: evidence pack incomplete or contentless. Missing: ${missing[*]} under $pack_dir"
  fi

  # ci.sh.json must certify a green --full run against this commit.
  _require_ci_sh_json "$tag_id" "$pack_dir/ci.sh.json"

  # Each Challenge Test C1-C8 MUST have an attestation file with
  # status: VERIFIED (not PENDING_OPERATOR).
  local i missing_challenge=()
  for i in 1 2 3 4 5 6 7 8; do
    local f="$pack_dir/challenges/C${i}.json"
    if [[ ! -f "$f" ]]; then
      missing_challenge+=("C${i}.json")
      continue
    fi
    if ! grep -qE '"status"[[:space:]]*:[[:space:]]*"VERIFIED"' "$f"; then
      missing_challenge+=("C${i}.json (status not VERIFIED)")
    fi
  done
  if (( ${#missing_challenge[@]} > 0 )); then
    die "Cannot tag $tag_id: Challenge Test attestations incomplete: ${missing_challenge[*]}"
  fi

  # real-device-verification.md MUST report status: VERIFIED.
  if ! grep -qE '^status:[[:space:]]*VERIFIED' "$pack_dir/real-device-verification.md"; then
    die "Cannot tag $tag_id: real-device-verification.md status is not VERIFIED. Operator must complete Task 5.22 before tagging."
  fi

  # Constitutional clause 6.I clause 7 — Multi-Emulator Container Matrix
  # gate. The evidence pack MUST contain at least one matrix-runner
  # attestation (real-device-verification.json) covering the minimum
  # AVD set per 6.I clause 2 (API 28, 30, 34, latest stable ≥ 36).
  # Phone form factor is the minimum per 6.I clause 3 for features
  # whose layout is not exercised; features that touch layout MUST
  # add tablet rows and (where TvActivity / leanback applies) TV rows.
  # Missing rows = missing evidence. all_passed=false in any matrix
  # JSON = release blocker.
  require_matrix_attestation_clause_6_I "$tag_id" "$pack_dir"
  require_matrix_attestation_group_b_gates "$tag_id" "$pack_dir"

  # Constitutional clause 6.P — Distribution Versioning + Changelog
  # Mandate. Tag MUST refuse if CHANGELOG.md lacks an entry for the
  # version OR the per-version distribute-changelog snapshot is missing.
  require_changelog_clause_6_P "$tag_id" "$vname" "$vcode" "Android"

  log "[android] SP-3a evidence pack OK: $pack_dir"
}

# Constitutional clause 6.P helper. Asserts:
#   1. CHANGELOG.md contains a heading mentioning the version
#   2. .lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md
#      exists for the firebase-app-distribution channel (Android only;
#      Go API + proxy use different channels but the rule is the same).
#
# Falsifiability rehearsal: rename CHANGELOG.md → CHANGELOG.md.bak;
# tag.sh must die with "missing CHANGELOG.md entry".
require_changelog_clause_6_P() {
  local tag_id="$1" vname="$2" vcode="$3" channel_label="$4"
  local changelog="$REPO_ROOT/CHANGELOG.md"

  if [[ ! -f "$changelog" ]]; then
    die "Cannot tag $tag_id: §6.P violation — CHANGELOG.md is missing."
  fi
  if ! grep -qE "${tag_id}|${vname} \\(${vcode}\\)|${vname}-${vcode}" "$changelog"; then
    die "Cannot tag $tag_id: §6.P violation — CHANGELOG.md does not contain an entry for $tag_id (version $vname, code $vcode). Add an entry before tagging."
  fi

  case "$channel_label" in
    Android) local channel_dir="firebase-app-distribution" ;;
    APIGo) local channel_dir="container-registry" ;;
    Proxy) local channel_dir="releases-proxy" ;;
    *) local channel_dir="generic" ;;
  esac
  local snapshot="$REPO_ROOT/.lava-ci-evidence/distribute-changelog/${channel_dir}/${vname}-${vcode}.md"
  if [[ ! -f "$snapshot" ]]; then
    die "Cannot tag $tag_id: §6.P violation — per-version distribute-changelog snapshot missing at $snapshot. Author this file BEFORE running scripts/firebase-distribute.sh; it is the App Distribution release-notes payload."
  fi

  log "[$channel_label] §6.P CHANGELOG gate passed: entry in CHANGELOG.md + snapshot at $snapshot"
}

# Constitutional clause 6.I clause 7 helper. Walks every
# real-device-verification.json file under the pack directory and
# asserts: (a) all_passed=true, (b) the UNION of api_level fields
# across the matrix files covers {28, 30, 34, ≥36}, (c) phone form
# factor is present.
#
# Falsifiability rehearsal recorded in this commit body.
require_matrix_attestation_clause_6_I() {
  local tag_id="$1" pack_dir="$2"

  if ! command -v jq >/dev/null 2>&1; then
    die "Cannot tag $tag_id: jq is required for clause 6.I matrix-attestation gate. Install jq and re-run."
  fi

  # Find every matrix attestation under the pack dir. Matrix runner
  # writes EvidenceDir/real-device-verification.json; the pack-dir
  # convention is to copy / symlink it under
  # <pack_dir>/matrix/<run-id>/real-device-verification.json. We
  # accept any depth so operators can organise their pack however.
  local files
  mapfile -t files < <(find "$pack_dir" -type f -name 'real-device-verification.json' 2>/dev/null)
  if (( ${#files[@]} == 0 )); then
    die "Cannot tag $tag_id: clause 6.I clause 7 — no matrix attestation (real-device-verification.json) under $pack_dir. Run scripts/run-emulator-tests.sh and copy the resulting evidence directory under the pack dir before tagging."
  fi

  # Per-file gate: all_passed MUST be true. Aggregate api_levels +
  # form_factors across all matrix files for the coverage check.
  local api_levels=() form_factors=() failing_files=() f
  for f in "${files[@]}"; do
    local all_passed
    all_passed=$(jq -r '.all_passed' "$f" 2>/dev/null || echo "parse-error")
    if [[ "$all_passed" != "true" ]]; then
      failing_files+=("$f (all_passed=$all_passed)")
      continue
    fi
    while IFS= read -r api; do
      api_levels+=("$api")
    done < <(jq -r '.rows[]?.api_level // empty' "$f" 2>/dev/null)
    while IFS= read -r ff; do
      form_factors+=("$ff")
    done < <(jq -r '.rows[]?.form_factor // empty' "$f" 2>/dev/null)
  done

  if (( ${#failing_files[@]} > 0 )); then
    die "Cannot tag $tag_id: clause 6.I clause 7 — matrix attestation has all_passed!=true in: ${failing_files[*]}"
  fi

  # --- compileSdk DERIVATION FLOOR: derive it, or refuse -------------------
  # BEGIN compileSdk derivation floor (regression-harness sentinel)
  #
  # compileSdk is this gate's own EXPECTATION: §6.I clause 2 requires the
  # matrix to cover API 28, 30, 34 AND the project's current compileSdk, so a
  # wrong compileSdk silently redefines what "complete coverage" means. It is
  # therefore DERIVED from buildSrc — the project's single source of truth —
  # and never defaulted. This is the 2026-05-05 latent-helper bluff's fix
  # (the prior hardcoded `api >= 36` would have false-passed a compileSdk=37
  # release on API-36-only evidence) carried to its conclusion.
  #
  # THE DEFAULT ITSELF WAS THE SAME BLUFF (F18, corpus-floor sweep 2026-08-26).
  # `compile_sdk=35` was seeded before the parse and the missing-file branch
  # had no `else` at all, so an ABSENT AndroidCommon.kt left the gate asserting
  # a coverage requirement no manifest supports — silently, with no warning.
  # Measured, decisive control (identical evidence, identical pack, only the
  # manifest's presence differing):
  #
  #   AndroidCommon.kt PRESENT, declares compileSdk = 36; rows 28 30 34 35
  #     FATAL … matrix coverage incomplete. Missing API levels: 36
  #     (project's compileSdk requirement) …                          EXIT=1
  #   SAME evidence, AndroidCommon.kt ABSENT
  #     VERDICT: matrix coverage gate PASSED (compile_sdk=35 …)        EXIT=0
  #
  # The worse state — no manifest at all — passed where the known state failed.
  #
  # The parse-failure branch is refused too, for two reasons. A floor with one
  # stair is not a floor: a truncated or reshaped file is the likelier drift
  # and would have kept the same silent 35. And its `warn`-then-continue arm
  # was in fact UNREACHABLE: under this script's own `set -Eeuo pipefail`, a
  # no-match `grep | head | grep` assignment aborts the shell outright, so a
  # reshaped file killed tag.sh with no message at all (verified 2026-08-26,
  # exit 1, zero output) — non-zero, but not a diagnosis anybody can act on.
  local convention_file="$REPO_ROOT/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
  local compile_sdk="" compile_sdk_cause=""
  if [[ ! -f "$convention_file" ]]; then
    compile_sdk_cause="no file exists at that path (checkout artifact, or the convention module moved)"
  else
    compile_sdk="$(grep -oE 'compileSdk[[:space:]]*=[[:space:]]*[0-9]+' "$convention_file" 2>/dev/null | head -1 | grep -oE '[0-9]+$' || true)"
    if [[ -z "$compile_sdk" ]]; then
      compile_sdk_cause="the file exists but declares no 'compileSdk = <n>' this gate can read (its shape changed)"
    fi
  fi
  if [[ ! "$compile_sdk" =~ ^[0-9]+$ ]]; then
    die "Cannot tag $tag_id: clause 6.I clause 2 — the project's compileSdk could NOT be derived, so this gate cannot state which API level the matrix is required to cover. Examined: $convention_file — $compile_sdk_cause. Expected: a 'compileSdk = <n>' declaration to derive the required API level from. Refusing rather than falling back to a built-in default: a default asserts a coverage requirement no manifest supports, and goes stale the moment the project moves to a newer compileSdk — measured 2026-08-26, an evidence pack carrying only API 28/30/34/35 rows was REFUSED while this file declared compileSdk=36 and ACCEPTED byte-identically once the file was removed. Do: restore the file (git checkout -- buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt), or update this gate's parser if the convention module legitimately moved or changed shape."
  fi
  # END-OF-BLOCK compileSdk derivation floor (regression-harness sentinel)

  # Coverage check: every minimum API level MUST be represented.
  # Per root §6.I clause 2: API 28, API 30, API 34, AND the project's
  # current compileSdk. Forward-compat (api > compile_sdk) is permitted
  # but not required.
  local required_apis=(28 30 34 "$compile_sdk") missing_apis=()
  local api required
  # Deduplicate (compile_sdk could equal one of 28/30/34 in odd cases)
  local seen_apis=()
  for required in "${required_apis[@]}"; do
    local already_seen=false
    for s in "${seen_apis[@]}"; do
      [[ "$s" == "$required" ]] && already_seen=true && break
    done
    [[ "$already_seen" == "true" ]] && continue
    seen_apis+=("$required")
    local found=false
    for api in "${api_levels[@]}"; do
      if [[ "$api" == "$required" ]]; then found=true; break; fi
    done
    [[ "$found" == "true" ]] || missing_apis+=("$required (project's compileSdk requirement)")
  done

  if (( ${#missing_apis[@]} > 0 )); then
    die "Cannot tag $tag_id: clause 6.I clause 2 — matrix coverage incomplete. Missing API levels: ${missing_apis[*]}. Found: ${api_levels[*]} across ${#files[@]} matrix file(s) under $pack_dir. Project compileSdk = $compile_sdk."
  fi

  # Form-factor minimum: phone MUST be present.
  local has_phone=false ff
  for ff in "${form_factors[@]}"; do
    if [[ "$ff" == "phone" ]]; then has_phone=true; break; fi
  done
  if [[ "$has_phone" != "true" ]]; then
    die "Cannot tag $tag_id: clause 6.I clause 3 — matrix is missing the phone form factor. Found: ${form_factors[*]}"
  fi

  log "[android] clause 6.I matrix gate OK: ${#files[@]} matrix file(s), API levels: $(printf '%s ' "${api_levels[@]}"), form factors: $(printf '%s ' "${form_factors[@]}")"
}

# Group B clause 6.I extension — three additional gates on every
# matrix attestation. Asserts:
#
#   Gate 1: no row carries concurrent != 1
#           (developer-iteration evidence cannot gate a tag)
#   Gate 2: run-level gating == true
#           (run was --concurrent or --dev)
#   Gate 3: per-row diag.sdk == row.api_level
#           (the "AVD shadow" bluff — claimed API-N row but the
#            running emulator reported a different SDK)
#
# Each gate is a hard die() — tag.sh refuses the tag and prints the
# violating rows.
#
# Backward-compat carve-outs (limited to fields whose absence is
# benign on pre-Group-B attestations already shipped under
# .lava-ci-evidence/):
#   - Gate 2: absent `gating` field treated as "true".
#   - Gate 3: rows lacking `diag` or `api_level` are skipped.
require_matrix_attestation_group_b_gates() {
  local tag_id="$1" pack_dir="$2"

  local files
  mapfile -t files < <(find "$pack_dir" -type f -name 'real-device-verification.json' 2>/dev/null)
  if (( ${#files[@]} == 0 )); then
    # Already handled by require_matrix_attestation_clause_6_I; do
    # not double-die. Group B gates only apply when at least one
    # attestation exists.
    return 0
  fi

  local f
  for f in "${files[@]}"; do
    # Gate 1 — reject any row whose concurrent != 1.
    local bad_concurrent
    bad_concurrent=$(jq -r '.rows[] | select(.concurrent != null and .concurrent != 1) | "\(.avd) (concurrent=\(.concurrent))"' "$f" 2>/dev/null)
    if [[ -n "$bad_concurrent" ]]; then
      die "Cannot tag $tag_id: clause 6.I Group B Gate 1 — attestation $f has rows with concurrent != 1 (developer-iteration evidence cannot gate a tag): $bad_concurrent"
    fi

    # Gate 2 — reject if run-level gating is anything other than true.
    # Older attestations (pre-Group-B) lack the field; treat absent
    # (jq null) as true to remain backward-compatible with already-
    # shipped evidence under .lava-ci-evidence/. This is the ONLY
    # backward-compat carve-out — once Group B ships, every new
    # attestation MUST carry the field.
    #
    # NOTE: we cannot use jq's `// "true"` shortcut here because `//`
    # also fires on the boolean `false`, which would coerce
    # `gating:false` to `"true"` and silently let a non-gating run
    # past the gate. Use an explicit `if .gating == null` check.
    local gating
    gating=$(jq -r 'if (.gating == null) then "true" else (.gating | tostring) end' "$f" 2>/dev/null)
    if [[ "$gating" != "true" ]]; then
      die "Cannot tag $tag_id: clause 6.I Group B Gate 2 — attestation $f has gating: $gating (run was --concurrent or --dev)"
    fi

    # Gate 3 — reject if any row's diag.sdk does not equal its
    # api_level. Skip rows that lack diag (older attestations) for
    # the same backward-compat reason as Gate 2.
    local mismatches
    mismatches=$(jq -r '.rows[] | select(.diag.sdk != null and .api_level != null and .diag.sdk != .api_level) | "\(.avd): claimed api_level=\(.api_level) but diag.sdk=\(.diag.sdk)"' "$f" 2>/dev/null)
    if [[ -n "$mismatches" ]]; then
      die "Cannot tag $tag_id: clause 6.I Group B Gate 3 — attestation $f has rows whose diag.sdk does not match api_level (the AVD-shadow bluff):
$mismatches"
    fi
  done

  log "[android] Group B clause 6.I gates OK across ${#files[@]} attestation file(s)"
}

# Sixth Law clause 5: refuse to tag api-go without a matching pretag
# evidence file produced by lava-api-go/scripts/pretag-verify.sh against
# the current HEAD. Bypass with --no-evidence-required for --dry-run
# rehearsals and documented operator emergencies.
require_evidence_for_apigo() {
  if $NO_EVIDENCE_REQUIRED; then
    warn "[api-go] --no-evidence-required: bypassing .lava-ci-evidence/<commit>.json gate"
    return 0
  fi
  if $DRY_RUN; then
    warn "[api-go] --dry-run: bypassing .lava-ci-evidence/<commit>.json gate"
    return 0
  fi
  # Search up to 10 ancestors for an evidence file. If the evidence is
  # for an ancestor (not HEAD itself), every commit since that ancestor
  # MUST have only touched .lava-ci-evidence/ — otherwise code has
  # changed and the evidence is stale. This handles the natural workflow
  # where pretag-verify writes evidence for HEAD, the operator commits
  # the evidence file (changing HEAD), then runs tag.sh.
  local head_commit ancestor_with_evidence="" candidate
  head_commit="$(git rev-parse HEAD)"
  for candidate in $(git log -n 10 --format=%H); do
    if [[ -f "$REPO_ROOT/.lava-ci-evidence/${candidate}.json" ]]; then
      ancestor_with_evidence="$candidate"
      break
    fi
  done
  if [[ -z "$ancestor_with_evidence" ]]; then
    die "Cannot tag api-go: no pretag evidence file found in .lava-ci-evidence/ for HEAD or any of its 10 most-recent ancestors. Run lava-api-go/scripts/pretag-verify.sh first."
  fi
  if [[ "$ancestor_with_evidence" != "$head_commit" ]]; then
    # See _scan_changed_since's header for why this is not written as
    # `git diff ... | grep -qvE ...` — under pipefail that spelling
    # failed open in direct proportion to how stale the evidence was.
    local _stale_rc=0
    _scan_changed_since "$ancestor_with_evidence" || _stale_rc=$?
    if (( _stale_rc == 2 )); then
      die "Cannot tag api-go: 'git diff --name-only ${ancestor_with_evidence}..HEAD' failed, so the age of .lava-ci-evidence/${ancestor_with_evidence}.json cannot be established. Refusing to tag on unverifiable pretag evidence."
    fi
    if (( ${#_STALENESS_OFFENDERS[@]} > 0 )); then
      die "Cannot tag api-go: evidence is from $ancestor_with_evidence but non-evidence files have changed since (${#_STALENESS_OFFENDERS[@]} of ${_STALENESS_EXAMINED} changed path(s), e.g. ${_STALENESS_OFFENDERS[0]}). Re-run lava-api-go/scripts/pretag-verify.sh."
    fi
    log "[api-go] pretag evidence found at ancestor $ancestor_with_evidence (examined ${_STALENESS_EXAMINED} changed path(s) since; all under .lava-ci-evidence/)"
  else
    log "[api-go] pretag evidence found: .lava-ci-evidence/${head_commit}.json"
  fi

  # §6.P CHANGELOG gate for api-go.
  local apigo_vname apigo_vcode
  apigo_vname="$(read_apigo_version_name)"
  apigo_vcode="$(read_apigo_version_code)"
  require_changelog_clause_6_P "Lava-API-Go-${apigo_vname}-${apigo_vcode}" \
    "$apigo_vname" "$apigo_vcode" "APIGo"
}

bump_semver() {
  local v="$1" part="$2"
  if [[ ! "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    die "Cannot bump non-semver version: '$v'"
  fi
  local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
  case "$part" in
    major) printf '%d.0.0' $((major + 1)) ;;
    minor) printf '%d.%d.0' "$major" $((minor + 1)) ;;
    patch) printf '%d.%d.%d' "$major" "$minor" $((patch + 1)) ;;
  esac
}

write_android_versions() {
  local new_name="$1" new_code="$2"
  local f="$REPO_ROOT/app/build.gradle.kts"
  if $DRY_RUN; then
    dry "would update $f → versionName=\"$new_name\", versionCode=$new_code"
    return
  fi
  sed -i -E "s|(versionName *= *\")[^\"]+(\")|\1$new_name\2|" "$f"
  sed -i -E "s|(versionCode *= *)[0-9]+|\1$new_code|" "$f"
  # Verify changes landed.
  [[ "$(read_android_version_name)" == "$new_name" ]] || die "Failed to write Android versionName"
  [[ "$(read_android_version_code)" == "$new_code" ]] || die "Failed to write Android versionCode"
}

write_apigo_versions() {
  local new_name="$1" new_code="$2"
  local f="$REPO_ROOT/lava-api-go/internal/version/version.go"
  if $DRY_RUN; then
    dry "would update $f → Name=\"$new_name\", Code=$new_code"
    return
  fi
  sed -i -E "s|(^[[:space:]]*Name *= *\")[^\"]+(\")|\1$new_name\2|" "$f"
  sed -i -E "s|(^[[:space:]]*Code *= *)[0-9]+|\1$new_code|" "$f"
  [[ "$(read_apigo_version_name)" == "$new_name" ]] || die "Failed to write API-Go Name"
  [[ "$(read_apigo_version_code)" == "$new_code" ]] || die "Failed to write API-Go Code"
}

# ----------------------------------------------------------------------
# Pre-flight: working directory, target apps, remotes
# ----------------------------------------------------------------------
cd "$REPO_ROOT"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || die "Not inside a git working tree: $REPO_ROOT"

if [[ -n "$(git status --porcelain)" ]]; then
  if $DRY_RUN; then
    warn "Working tree is dirty — proceeding because --dry-run is set (no mutations will occur)."
  else
    die "Working tree is dirty. Commit or stash changes first (or run with --dry-run)."
  fi
fi

# Resolve target apps (validation already done above).
declare -a TARGETS=()
if [[ "$TARGET_APP" == "all" ]]; then
  TARGETS=("${SUPPORTED_APPS[@]}")
else
  TARGETS=("$TARGET_APP")
fi

# Resolve remotes.
declare -a REMOTES=()
if (( ${#EXPLICIT_REMOTES[@]} > 0 )); then
  REMOTES=("${EXPLICIT_REMOTES[@]}")
else
  while IFS= read -r r; do
    for d in "${DEFAULT_REMOTES[@]}"; do
      [[ "$r" == "$d" ]] && REMOTES+=("$r")
    done
  done < <(git remote)
fi

if $DO_PUSH; then
  (( ${#REMOTES[@]} > 0 )) \
    || die "No usable git remotes found (looked for: ${DEFAULT_REMOTES[*]})"
  for r in "${REMOTES[@]}"; do
    git remote get-url "$r" >/dev/null 2>&1 || die "Configured remote '$r' does not exist"
  done
fi

current_branch=$(git rev-parse --abbrev-ref HEAD)
log "Repo:        $REPO_ROOT"
log "Branch:      $current_branch"
log "Apps:        ${TARGETS[*]}"
log "Bump part:   $BUMP_PART (post-tag)"
log "Push:        $($DO_PUSH && echo enabled || echo disabled)"
log "Bump:        $($DO_BUMP && echo enabled || echo disabled)"
log "Dry run:     $($DRY_RUN && echo YES || echo no)"
$DO_PUSH && log "Remotes:     ${REMOTES[*]}"

# ----------------------------------------------------------------------
# Per-app: read versions, plan tag, create + push, bump
# ----------------------------------------------------------------------
declare -a CREATED_TAGS=()

for app in "${TARGETS[@]}"; do
  case "$app" in
    android)
      tag_suffix="Android"
      vname=$(read_android_version_name)
      vcode=$(read_android_version_code)
      writer=write_android_versions
      require_evidence_for_android "$vname" "$vcode"
      ;;
    api-go)
      require_evidence_for_apigo
      tag_suffix="API-Go"
      vname=$(read_apigo_version_name)
      vcode=$(read_apigo_version_code)
      writer=write_apigo_versions
      ;;
    *) die "Unsupported app: $app" ;;
  esac

  tag="Lava-${tag_suffix}-${vname}-${vcode}"
  log "[$app] current ${vname}-${vcode} → tag '$tag'"

  if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    warn "[$app] tag '$tag' already exists locally — skipping creation"
  else
    run git tag -a "$tag" -m "Release $tag_suffix $vname (versionCode $vcode)"
    CREATED_TAGS+=("$tag")
  fi

  if $DO_PUSH; then
    for remote in "${REMOTES[@]}"; do
      run git push "$remote" "refs/tags/$tag"
    done
  else
    log "[$app] --no-push: skipping tag push"
  fi

  if $DO_BUMP; then
    new_vname=$(bump_semver "$vname" "$BUMP_PART")
    new_vcode=$((vcode + 1))
    log "[$app] bump → ${new_vname}-${new_vcode}"
    "$writer" "$new_vname" "$new_vcode"
  fi
done

# ----------------------------------------------------------------------
# Commit + push the bump
# ----------------------------------------------------------------------
if $DO_BUMP; then
  if $DRY_RUN; then
    dry "would commit version bump for: ${TARGETS[*]} (--bump $BUMP_PART)"
    if $DO_PUSH; then
      for remote in "${REMOTES[@]}"; do
        dry "would push HEAD to $remote/$current_branch"
      done
    fi
  else
    if [[ -n "$(git status --porcelain)" ]]; then
      bump_msg="Bump versions after release: ${TARGETS[*]} (--bump $BUMP_PART)"
      run git add -A
      run git commit -m "$bump_msg"
      if $DO_PUSH; then
        for remote in "${REMOTES[@]}"; do
          run git push "$remote" "HEAD:$current_branch"
        done
      fi
    else
      warn "No version-file changes to commit (already at target versions?)"
    fi
  fi
fi

# ----------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------
log "----------------------------------------"
log "Summary:"
_label="created tag"
$DRY_RUN && _label="would create tag"
if (( ${#CREATED_TAGS[@]} > 0 )); then
  for t in "${CREATED_TAGS[@]}"; do log "  $_label: $t"; done
else
  log "  no new tags created"
fi
log "Done."
