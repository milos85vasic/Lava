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
DEFAULT_REMOTES=(github gitflic gitlab gitverse)

# Apps registry.
SUPPORTED_APPS=(android api api-go)

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
    Lava-API-1.0.1-1001
    Lava-API-Go-2.0.0-2000

OPTIONS
  -h, --help              Show this help and exit.
  -n, --dry-run           Print every action; perform no git or file changes.
  -a, --app <name>        Restrict to a single app: 'android', 'api', 'api-go',
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
                          configured is used: github, gitflic, gitlab, gitverse.

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
read_api_version_name() {
  read_value "$REPO_ROOT/proxy/build.gradle.kts" \
    '^val apiVersionName *= *"[^"]+"' \
    's/^val apiVersionName *= *"([^"]+)".*/\1/' \
    "API apiVersionName"
}
read_api_version_code() {
  read_value "$REPO_ROOT/proxy/build.gradle.kts" \
    '^val apiVersionCode *= *[0-9]+' \
    's/^val apiVersionCode *= *([0-9]+).*/\1/' \
    "API apiVersionCode"
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
#   - the Submodules/Tracker-SDK mirror smoke test passed
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
  local missing=()
  [[ -f "$pack_dir/ci.sh.json" ]] || missing+=("ci.sh.json")
  [[ -d "$pack_dir/challenges" ]] || missing+=("challenges/")
  [[ -d "$pack_dir/bluff-audit" ]] || missing+=("bluff-audit/")
  [[ -d "$pack_dir/mirror-smoke" ]] || missing+=("mirror-smoke/")
  [[ -f "$pack_dir/real-device-verification.md" ]] || missing+=("real-device-verification.md")

  if (( ${#missing[@]} > 0 )); then
    die "Cannot tag $tag_id: evidence pack incomplete. Missing: ${missing[*]} under $pack_dir"
  fi

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

  log "[android] SP-3a evidence pack OK: $pack_dir"
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

  # Parse compileSdk from buildSrc — single source of truth. Falls back
  # to 35 if the file changes shape (with a warning, never silent
  # passing). This dynamic parse fixes the 2026-05-05 latent helper
  # bluff: the prior hardcoded `api >= 36` would have silently false-
  # passed any future compileSdk=37 release with API-36-only evidence.
  local compile_sdk=35
  local convention_file="$REPO_ROOT/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
  if [[ -f "$convention_file" ]]; then
    local parsed
    parsed=$(grep -oE 'compileSdk[[:space:]]*=[[:space:]]*[0-9]+' "$convention_file" | head -1 | grep -oE '[0-9]+$')
    if [[ -n "$parsed" ]]; then
      compile_sdk=$parsed
    else
      warn "[android] could not parse compileSdk from $convention_file — defaulting to 35"
    fi
  fi

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
    if git diff --name-only "${ancestor_with_evidence}..HEAD" -- | grep -qvE '^\.lava-ci-evidence/'; then
      die "Cannot tag api-go: evidence is from $ancestor_with_evidence but non-evidence files have changed since. Re-run lava-api-go/scripts/pretag-verify.sh."
    fi
    log "[api-go] pretag evidence found at ancestor $ancestor_with_evidence (only .lava-ci-evidence/ changed since)"
  else
    log "[api-go] pretag evidence found: .lava-ci-evidence/${head_commit}.json"
  fi
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

write_api_versions() {
  local new_name="$1" new_code="$2"
  local f="$REPO_ROOT/proxy/build.gradle.kts"
  if $DRY_RUN; then
    dry "would update $f → apiVersionName=\"$new_name\", apiVersionCode=$new_code"
    return
  fi
  sed -i -E "s|(^val apiVersionName *= *\")[^\"]+(\")|\1$new_name\2|" "$f"
  sed -i -E "s|(^val apiVersionCode *= *)[0-9]+|\1$new_code|" "$f"
  [[ "$(read_api_version_name)" == "$new_name" ]] || die "Failed to write API apiVersionName"
  [[ "$(read_api_version_code)" == "$new_code" ]] || die "Failed to write API apiVersionCode"
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
    api)
      tag_suffix="API"
      vname=$(read_api_version_name)
      vcode=$(read_api_version_code)
      writer=write_api_versions
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
