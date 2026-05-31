#!/usr/bin/env bash
# sync-markdown-exports.sh — §11.4.65 / CONST-066 Universal Markdown Export Sync
#
# Every in-scope committed markdown document MUST have synchronized .html + .pdf
# sibling exports kept current with the markdown source. This script is both the
# generator and the checker (the gate CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC wraps the
# --check-only mode).
#
#   HTML:  pandoc "$md" -o "$html" --standalone
#   PDF:   pandoc "$md" -o "$pdf" --pdf-engine=weasyprint
#
# Scope (authority: docs/chaos-stress/EXPORT-AUDIT.md):
#   INCLUDED: project-root *.md, docs/**/*.md, scripts/**/*.md
#   EXCLUDED: external/ prebuilts/ submodules/** constitution/** lava-api-go/**
#             build/ out/ .git/ node_modules/ app/ core/ feature/ source trees
#             (submodules + constitution sync their OWN exports — we never reach in)
#
# Modes:
#   --check-only        exit 1 if any in-scope .md lacks a sibling OR a sibling is
#                       older than its .md (default mode used by the gate)
#   --regenerate-all    generate/refresh ALL siblings
#   --regenerate <file> regenerate one specific .md's siblings
#
# §6.T.2 resource discipline: each pandoc conversion is sub-second; per-file
# `timeout 60` caps a runaway. The candidate set is capped at 500 (logged loudly
# if exceeded — §6.J no silent truncation).
#
# §11.4.18 companion doc: docs/scripts/sync-markdown-exports.sh.md
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MAX_CANDIDATES=500
PER_FILE_TIMEOUT=60

# ---------------------------------------------------------------------------
# Tooling preflight
# ---------------------------------------------------------------------------
if ! command -v pandoc >/dev/null 2>&1; then
  echo "[markdown-export] ERROR: pandoc not found on PATH" >&2
  exit 2
fi
if ! command -v weasyprint >/dev/null 2>&1; then
  echo "[markdown-export] ERROR: weasyprint not found on PATH" >&2
  exit 2
fi

# Resolve a `timeout` binary (GNU coreutils `timeout` or `gtimeout` on macOS).
TIMEOUT_BIN=""
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_BIN="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_BIN="gtimeout"
fi

run_with_timeout() {
  # Usage: run_with_timeout <cmd...>
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$PER_FILE_TIMEOUT" "$@"
  else
    # No timeout binary available; run directly. Conversions are sub-second so
    # the cap is a safety net, not a correctness requirement.
    "$@"
  fi
}

# ---------------------------------------------------------------------------
# Scope discovery — emit the in-scope .md list, one per line.
# ---------------------------------------------------------------------------
discover_candidates() {
  # Project-root *.md (depth 1 only)
  find . -maxdepth 1 -type f -name '*.md'
  # docs/**/*.md and scripts/**/*.md
  find docs scripts -type f -name '*.md' 2>/dev/null
}

# Apply exclusion filter to a path. Returns 0 (include) or 1 (exclude).
is_in_scope() {
  local p="$1"
  # Normalize leading ./
  p="${p#./}"
  case "$p" in
    external/*|prebuilts/*|submodules/*|constitution/*|lava-api-go/*) return 1 ;;
    build/*|out/*|.git/*|node_modules/*) return 1 ;;
    app/*|core/*|feature/*) return 1 ;;
  esac
  return 0
}

# Build the deduplicated, in-scope candidate array.
build_candidate_list() {
  local raw line
  CANDIDATES=()
  raw="$(discover_candidates | sort -u)"
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if is_in_scope "$line"; then
      CANDIDATES+=("${line#./}")
    fi
  done <<< "$raw"

  local n=${#CANDIDATES[@]}
  if (( n > MAX_CANDIDATES )); then
    echo "[markdown-export] ERROR: candidate count ($n) exceeds cap ($MAX_CANDIDATES)." >&2
    echo "[markdown-export] Refusing to silently truncate (§6.J). Raise MAX_CANDIDATES" >&2
    echo "[markdown-export] deliberately or narrow the scope, then re-run." >&2
    exit 2
  fi
}

# ---------------------------------------------------------------------------
# Per-file generation
# ---------------------------------------------------------------------------
generate_one() {
  local md="$1"
  local base="${md%.md}"
  local html="${base}.html"
  local pdf="${base}.pdf"

  # `--from gfm` reads the source as GitHub-Flavored Markdown, which does NOT
  # interpret a leading YAML metadata block. Plain `pandoc <md>` defaults to
  # pandoc-markdown, whose YAML-metadata parser chokes on documents that happen
  # to begin with `---`-fenced content containing YAML-special tokens (e.g.
  # CHANGELOG.md's `&`-alias-like line). GFM is the correct, robust reader for
  # arbitrary repo Markdown and eliminates that whole failure class.
  if ! run_with_timeout pandoc "$md" --from gfm -o "$html" --standalone; then
    echo "[markdown-export] ERROR: HTML generation failed/timed-out for $md" >&2
    return 1
  fi
  if ! run_with_timeout pandoc "$md" --from gfm -o "$pdf" --pdf-engine=weasyprint; then
    echo "[markdown-export] ERROR: PDF generation failed/timed-out for $md" >&2
    return 1
  fi
  echo "  generated: $html + $pdf"
  return 0
}

# ---------------------------------------------------------------------------
# Check-only: report missing or stale siblings.
# ---------------------------------------------------------------------------
check_only() {
  build_candidate_list
  local md base html pdf problems=0 checked=0

  for md in "${CANDIDATES[@]}"; do
    base="${md%.md}"
    html="${base}.html"
    pdf="${base}.pdf"
    checked=$((checked+1))

    if [[ ! -f "$html" ]]; then
      echo "  MISSING html: $html"
      problems=$((problems+1))
      continue
    fi
    if [[ ! -f "$pdf" ]]; then
      echo "  MISSING pdf:  $pdf"
      problems=$((problems+1))
      continue
    fi
    # Stale = sibling older than its .md (mtime).
    if [[ "$md" -nt "$html" ]]; then
      echo "  STALE html:   $html (older than $md)"
      problems=$((problems+1))
    fi
    if [[ "$md" -nt "$pdf" ]]; then
      echo "  STALE pdf:    $pdf (older than $md)"
      problems=$((problems+1))
    fi
  done

  echo "[markdown-export] checked $checked in-scope .md file(s); $problems problem(s)."
  if (( problems > 0 )); then
    return 1
  fi
  return 0
}

regenerate_all() {
  build_candidate_list
  local md generated=0 failed=0
  echo "[markdown-export] regenerating siblings for ${#CANDIDATES[@]} in-scope .md file(s)..."
  for md in "${CANDIDATES[@]}"; do
    if generate_one "$md"; then
      generated=$((generated+1))
    else
      failed=$((failed+1))
    fi
  done
  echo "[markdown-export] regenerate-all: $generated ok, $failed failed (of ${#CANDIDATES[@]})."
  echo "[markdown-export] sibling files written: $((generated*2)) (.html + .pdf per source)."
  (( failed == 0 ))
}

regenerate_one() {
  local md="$1"
  md="${md#./}"
  if [[ ! -f "$md" ]]; then
    echo "[markdown-export] ERROR: file not found: $md" >&2
    exit 2
  fi
  if ! is_in_scope "$md"; then
    echo "[markdown-export] ERROR: $md is out of scope (§11.4.65 exclusion)." >&2
    exit 2
  fi
  case "$md" in
    *.md) ;;
    *) echo "[markdown-export] ERROR: not a .md file: $md" >&2; exit 2 ;;
  esac
  generate_one "$md"
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
MODE="${1:---check-only}"
case "$MODE" in
  --check-only)
    check_only
    ;;
  --regenerate-all)
    regenerate_all
    ;;
  --regenerate)
    if [[ $# -lt 2 ]]; then
      echo "[markdown-export] ERROR: --regenerate requires a <file> argument" >&2
      exit 2
    fi
    regenerate_one "$2"
    ;;
  -h|--help)
    sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "[markdown-export] ERROR: unknown mode: $MODE" >&2
    echo "Usage: $0 [--check-only|--regenerate-all|--regenerate <file>]" >&2
    exit 2
    ;;
esac
