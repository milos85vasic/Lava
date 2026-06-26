#!/usr/bin/env bash
# check-cycle-coverage.sh — CM-CYCLE-COVERAGE-INTERSECTION gate (§6.AK / §6.AK-debt)
#
# §6.AK clause 1 (added 2026-06-26, commit 627a0d58; spec
# docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md §5.1):
#   No artifact may be distributed unless EVERY CHANGELOG-claimed user-visible
#   fix for the current version has an EXECUTED+PASSED covering device Challenge
#   in the matching §6.Z evidence file for the SAME commit SHA.
#
# This is the mechanical gate that would have CAUGHT the 1076 incident
# (commit 627a0d58): the §6.Z device gate executed ONLY Challenge00 (cold-start)
# while the CHANGELOG claimed fixes to search / provider-selection / onboarding.
# A passing gate proved nothing about the shipped value.
#
# The gate intersects two sets:
#   * CLAIM-SET  — the covering Challenge for each CHANGELOG-claimed user-visible
#                  fix, declared in a per-cycle `cycle-coverage-map` (see §3.1).
#   * EXECUTED-PASS SET — the Challenges that ran and PASSED on the §6.Z device
#                  evidence file (same version + same commit SHA + ≤24h).
# It REQUIRES claim-set ⊆ executed-pass set. Any uncovered claim → reject.
#
# ──────────────────────────────────────────────────────────────────────────
# FILE FORMATS (documented contract — the simplest robust approach, no `yq`)
#
# 1. cycle-coverage-map (§3.1) — a small YAML block. Only three keys are read,
#    parsed line-by-line (no nested-YAML dependency):
#        version: "1.3.12-1077"
#        claims:
#          - bullet: "Search now returns real results"
#            covering_challenge: "Challenge58SearchReturnsResults"
#          - bullet: "Search filters follow onboarded providers"
#            covering_challenge: "Challenge59SearchUsesOnboardedProviders"
#    Each `covering_challenge:` value is a REQUIRED claim. The preceding
#    `bullet:` (if any) is used only for human-readable FAIL messages.
#    A claim whose covering_challenge is empty/absent is itself an uncovered
#    claim (exit 1) — that is the "CHANGELOG claims a fix but no Challenge
#    was written" case the incident is about.
#
# 2. §6.Z evidence file — must carry machine-readable result lines that the
#    gate consumes (this is the NEW per-cycle requirement §6.AK adds; the
#    cycle author writes them alongside the prose evidence). Two line kinds:
#        cycle-coverage: version=1.3.12-1077 commit=<sha40> channel=debug timestamp=2026-06-26T12:00:00Z
#        challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
#    * exactly ONE `cycle-coverage:` header line carries version/commit/
#      timestamp/channel.
#    * one `challenge:` line per executed Challenge with its verdict
#      (PASS | FAIL | SKIP) and the device runner. A Challenge counts as
#      covered ONLY when a `challenge:` line whose fqn CONTAINS the map's
#      covering_challenge name has verdict=PASS and runner != host-direct
#      (§6.AH — host-direct rows are not gate-eligible).
#
# Matching is substring (map carries short names e.g. "Challenge58Search…",
# evidence carries the full FQN "lava.app.challenges.Challenge58Search…Test").
#
# ──────────────────────────────────────────────────────────────────────────
# EXIT CODES (spec §5.1):
#   0 — all claims covered by executed+PASSED, same-SHA, fresh device rows.
#   1 — one or more claims lack a covering executed+PASSED Challenge
#       (missing / FAIL / SKIP / host-direct), OR evidence timestamp > 24h.
#   2 — structural: evidence or map file missing, version mismatch, or the
#       evidence commit SHA != working-tree HEAD.
#   3 — internal error (malformed map / malformed evidence header).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── defaults (all overridable for hermetic testing) ────────────────────────
VERSION=""
CHANNEL="debug"
EVIDENCE_DIR=""
MAP_PATH=""
# Expected working-tree HEAD. Default = real git HEAD; overridable so the
# hermetic test can assert SHA matching without a real commit.
HEAD_SHA="${LAVA_CYCLE_COVERAGE_HEAD:-}"
# "Now" epoch for the ≤24h freshness check; overridable for determinism.
NOW_EPOCH="${LAVA_CYCLE_COVERAGE_NOW_EPOCH:-}"
STRICT=1
MAX_AGE_SECONDS=$(( 24 * 60 * 60 ))

usage() {
  cat >&2 <<'EOF'
Usage: check-cycle-coverage.sh [options]
  --version  <name-code>   e.g. "1.3.12-1077" (default: auto-detect from app/build.gradle.kts)
  --channel  <channel>     "debug" or "release" (default: debug)
  --evidence-dir <path>    dir holding <version>-test-evidence.{md,json}
  --map      <path>        cycle-coverage-map YAML
  --head     <sha>         expected working-tree HEAD (default: git rev-parse HEAD)
  --now-epoch <epoch>      "now" for the ≤24h check (default: date +%s)
  --strict                 reject on any uncovered claim (default: on)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)      VERSION="$2"; shift 2 ;;
    --version=*)    VERSION="${1#*=}"; shift ;;
    --channel)      CHANNEL="$2"; shift 2 ;;
    --channel=*)    CHANNEL="${1#*=}"; shift ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --evidence-dir=*) EVIDENCE_DIR="${1#*=}"; shift ;;
    --map)          MAP_PATH="$2"; shift 2 ;;
    --map=*)        MAP_PATH="${1#*=}"; shift ;;
    --head)         HEAD_SHA="$2"; shift 2 ;;
    --head=*)       HEAD_SHA="${1#*=}"; shift ;;
    --now-epoch)    NOW_EPOCH="$2"; shift 2 ;;
    --now-epoch=*)  NOW_EPOCH="${1#*=}"; shift ;;
    --strict)       STRICT=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "check-cycle-coverage.sh: unknown argument '$1'" >&2; usage; exit 3 ;;
  esac
done

fail()  { echo "CM-CYCLE-COVERAGE FAIL — $*" >&2; }
fatal() { echo "CM-CYCLE-COVERAGE FATAL — $*" >&2; }

# ── resolve version ────────────────────────────────────────────────────────
if [[ -z "$VERSION" ]]; then
  gradle="$ROOT/app/build.gradle.kts"
  if [[ ! -f "$gradle" ]]; then
    fatal "cannot auto-detect version: $gradle missing"; exit 3
  fi
  vname="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$gradle" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
  vcode="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$gradle" | head -1 | sed -E 's/[^0-9]//g')"
  if [[ -z "$vname" || -z "$vcode" ]]; then
    fatal "cannot parse versionName/versionCode from $gradle"; exit 3
  fi
  VERSION="${vname}-${vcode}"
fi

# ── resolve HEAD ───────────────────────────────────────────────────────────
if [[ -z "$HEAD_SHA" ]]; then
  HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
  if [[ -z "$HEAD_SHA" ]]; then
    fatal "cannot resolve working-tree HEAD (not a git repo?); pass --head"; exit 3
  fi
fi

# ── resolve now ────────────────────────────────────────────────────────────
if [[ -z "$NOW_EPOCH" ]]; then NOW_EPOCH="$(date +%s)"; fi

# ── resolve evidence file + map ────────────────────────────────────────────
if [[ -z "$EVIDENCE_DIR" ]]; then
  case "$CHANNEL" in
    release) EVIDENCE_DIR="$ROOT/.lava-ci-evidence/distribute-changelog/firebase-app-distribution" ;;
    *)       EVIDENCE_DIR="$ROOT/.lava-ci-evidence/distribute-changelog/firebase-app-distribution-dev" ;;
  esac
fi

EVIDENCE_FILE=""
for ext in md json; do
  cand="$EVIDENCE_DIR/${VERSION}-test-evidence.$ext"
  if [[ -f "$cand" ]]; then EVIDENCE_FILE="$cand"; break; fi
done
if [[ -z "$EVIDENCE_FILE" ]]; then
  fatal "§6.Z evidence file missing: expected ${EVIDENCE_DIR}/${VERSION}-test-evidence.{md,json}"
  exit 2
fi

if [[ -z "$MAP_PATH" ]]; then
  for cand in \
    "$EVIDENCE_DIR/${VERSION}-cycle-coverage-map.yaml" \
    "$ROOT/.lava-ci-evidence/${VERSION}/cycle-coverage-map.yaml"; do
    if [[ -f "$cand" ]]; then MAP_PATH="$cand"; break; fi
  done
fi
if [[ -z "$MAP_PATH" || ! -f "$MAP_PATH" ]]; then
  fatal "cycle-coverage-map missing for ${VERSION} (looked next to evidence + .lava-ci-evidence/${VERSION}/); pass --map"
  exit 2
fi

# ── parse evidence header (the single cycle-coverage: line) ────────────────
header="$(grep -E '^[[:space:]]*cycle-coverage:' "$EVIDENCE_FILE" | head -1 || true)"
if [[ -z "$header" ]]; then
  fatal "evidence file $EVIDENCE_FILE has no 'cycle-coverage:' header line"; exit 3
fi
ev_version="$(sed -nE 's/.*(^|[[:space:]])version=([^[:space:]]+).*/\2/p' <<<"$header")"
ev_commit="$(sed -nE 's/.*(^|[[:space:]])commit=([^[:space:]]+).*/\2/p' <<<"$header")"
ev_ts="$(sed -nE 's/.*(^|[[:space:]])timestamp=([^[:space:]]+).*/\2/p' <<<"$header")"
if [[ -z "$ev_version" || -z "$ev_commit" || -z "$ev_ts" ]]; then
  fatal "evidence header malformed (need version=/commit=/timestamp=): $header"; exit 3
fi

# ── structural checks: version + SHA ───────────────────────────────────────
if [[ "$ev_version" != "$VERSION" ]]; then
  fatal "evidence version '$ev_version' != requested '$VERSION'"; exit 2
fi
# SHA match — short-or-long tolerant (prefix compare against the longer one).
short_len=${#ev_commit}; head_short="${HEAD_SHA:0:$short_len}"
if [[ "$ev_commit" != "$head_short" && "$ev_commit" != "$HEAD_SHA" ]]; then
  fatal "evidence commit '$ev_commit' != working-tree HEAD '$HEAD_SHA' (stale evidence — re-run the device gate on THIS commit)"
  exit 2
fi

# ── freshness: timestamp within 24h ────────────────────────────────────────
iso_to_epoch() {
  # Try GNU date first, then BSD/macOS date.
  date -u -d "$1" +%s 2>/dev/null && return 0
  date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$1" +%s 2>/dev/null && return 0
  date -u -j -f "%Y-%m-%dT%H:%M:%S%z" "$1" +%s 2>/dev/null && return 0
  return 1
}
ev_epoch="$(iso_to_epoch "$ev_ts" || true)"
if [[ -z "$ev_epoch" ]]; then
  fatal "cannot parse evidence timestamp '$ev_ts' (expected ISO-8601 e.g. 2026-06-26T12:00:00Z)"; exit 3
fi
age=$(( NOW_EPOCH - ev_epoch ))
if (( age < 0 )); then age=$(( -age )); fi
if (( age > MAX_AGE_SECONDS )); then
  fail "evidence timestamp '$ev_ts' is older than 24h (${age}s) — re-run the device gate before distribute"
  exit 1
fi

# ── collect executed-PASS challenge lines from evidence ────────────────────
# Each gate-eligible PASS row: verdict=PASS AND runner != host-direct (§6.AH).
pass_lines="$(grep -E '^[[:space:]]*challenge:' "$EVIDENCE_FILE" || true)"

challenge_is_passing() {
  # $1 = covering_challenge name (substring to match against fqn=)
  local needle="$1" line fqn verdict runner
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    fqn="$(sed -nE 's/.*(^|[[:space:]])fqn=([^[:space:]]+).*/\2/p' <<<"$line")"
    [[ "$fqn" != *"$needle"* ]] && continue
    verdict="$(sed -nE 's/.*(^|[[:space:]])verdict=([^[:space:]]+).*/\2/p' <<<"$line")"
    runner="$(sed -nE 's/.*(^|[[:space:]])runner=([^[:space:]]+).*/\2/p' <<<"$line")"
    if [[ "$verdict" == "PASS" && "$runner" != "host-direct" ]]; then
      return 0
    fi
  done <<<"$pass_lines"
  return 1
}

# ── walk the map's claims ──────────────────────────────────────────────────
# Parse line-by-line: remember the most recent bullet; on each
# covering_challenge, emit a (bullet, challenge) claim.
uncovered=0
total=0
current_bullet=""

strip_quotes() { local s="$1"; s="${s#\"}"; s="${s%\"}"; s="${s#\'}"; s="${s%\'}"; echo "$s"; }

while IFS= read -r raw || [[ -n "$raw" ]]; do
  line="${raw%$'\r'}"
  case "$line" in
    *bullet:*)
      current_bullet="$(strip_quotes "$(sed -E 's/.*bullet:[[:space:]]*//' <<<"$line")")"
      ;;
    *covering_challenge:*)
      chal="$(strip_quotes "$(sed -E 's/.*covering_challenge:[[:space:]]*//' <<<"$line")")"
      total=$(( total + 1 ))
      if [[ -z "$chal" ]]; then
        fail "claim has NO covering Challenge: \"${current_bullet:-<unnamed bullet>}\" (write the device Challenge and run it on the gate)"
        uncovered=$(( uncovered + 1 ))
      elif ! challenge_is_passing "$chal"; then
        fail "claim NOT covered by an executed+PASSED device Challenge: \"${current_bullet:-<unnamed bullet>}\" → expected PASS row for '$chal' in $EVIDENCE_FILE (found none / FAIL / SKIP / host-direct)"
        uncovered=$(( uncovered + 1 ))
      fi
      current_bullet=""
      ;;
  esac
done < "$MAP_PATH"

if (( total == 0 )); then
  fatal "cycle-coverage-map $MAP_PATH declares ZERO claims (no covering_challenge: entries) — malformed"
  exit 3
fi

if (( uncovered > 0 )); then
  fail "${uncovered}/${total} CHANGELOG claim(s) lack an executed+PASSED covering device Challenge for ${VERSION} — distribute BLOCKED (§6.AK clause 1)"
  exit 1
fi

echo "CM-CYCLE-COVERAGE OK — ${VERSION} (${CHANNEL}): all ${total} CHANGELOG claim(s) covered by executed+PASSED device Challenges @ ${ev_commit} (≤24h)"
exit 0
