#!/usr/bin/env bash
# Hermetic test for scripts/scan-no-hardcoded-ipv4.sh — the §6.R IPv4 scanner.
#
# This test did not exist before LVA-062: of the three §6.R hardcoded scanners
# (uuid / ipv4 / hostport) the IPv4 one was the only one with NO hermetic
# coverage at all. The scanner has several distinct branches — a real-IPv4
# detector, a reserved-address allowlist (loopback / wildcard / broadcast /
# RFC 5737 documentation ranges), a file-extension exemption set, and (added
# 2026-06) the `\.db$` exemption — none of which had a falsifiability proof. A
# refactor could have silently broken detection or dropped an exemption and the
# only signal would have been a future false build break (or, worse, a missed
# real literal).
#
# Each fixture is a throwaway git repo into which the real scanner is copied
# (its `cd "$(dirname "$0")/.."` then resolves to the fixture root), mirroring
# the sibling test_no_hardcoded_hostport.sh / test_no_hardcoded_uuid.sh style.
#
# Coverage:
#   1. live repo passes (the standing tree is clean)
#   2. NEGATIVE: a real routable IPv4 literal in a code file IS flagged
#   3. POSITIVE: loopback 127.0.0.1 is exempt (reserved-address branch)
#   4. POSITIVE: RFC 5737 documentation range 192.0.2.x is exempt
#   5. POSITIVE: a *.md / *.json config/doc file is exempt (file-ext branch)
#   6. POSITIVE (LVA-058/062): a tracked .db file is exempt (`\.db$` branch) —
#      proven falsifiable by removing `\.db$` from the scanner exclusion regex
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/scan-no-hardcoded-ipv4.sh"

fail=0

run_fixture() {
  local relpath="$1"; shift
  local body="$1"; shift
  local dir
  dir="$(mktemp -d)"
  (
    cd "$dir"
    git init -q
    git config user.email t@example.com
    git config user.name t
    mkdir -p "$(dirname "$relpath")" scripts
    printf '%s\n' "$body" > "$relpath"
    cp "$SCANNER" scripts/scan-no-hardcoded-ipv4.sh
    git add -A
    git -c commit.gpgsign=false commit -qm fixture
    bash scripts/scan-no-hardcoded-ipv4.sh >/dev/null 2>&1
    echo "$?"
  )
  rm -rf "$dir"
}

# Test 1: live repo passes.
if bash "$SCANNER" >/dev/null 2>&1; then
  echo "PASS test_live_repo_passes"
else
  echo "FAIL test_live_repo_passes: scanner flagged the live tree" >&2
  bash "$SCANNER" >&2 || true
  fail=1
fi

# Test 2 (falsifiability): a real routable IPv4 literal in a code file IS
# flagged. A code file (.kt) is NOT in the file-ext exemption set, so the
# detector branch must fire. 10.10.20.30 is a routable RFC 1918 address — not a
# reserved/documentation address — so it is a genuine §6.R violation.
rc=$(run_fixture core/data/src/main/kotlin/lava/data/Net.kt \
  'const val HOST = "10.10.20.30"')
if [[ "$rc" == "1" ]]; then
  echo "PASS test_real_ipv4_flagged"
else
  echo "FAIL test_real_ipv4_flagged: expected 1, got $rc" >&2
  fail=1
fi

# Test 3: loopback 127.0.0.1 is universally permitted (reserved-address branch).
rc=$(run_fixture core/data/src/main/kotlin/lava/data/Loop.kt \
  'const val LOCAL = "127.0.0.1"')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_loopback_exempt"
else
  echo "FAIL test_loopback_exempt: expected 0 (loopback reserved), got $rc" >&2
  fail=1
fi

# Test 4: RFC 5737 documentation range 192.0.2.x is exempt.
rc=$(run_fixture core/data/src/main/kotlin/lava/data/Docs.kt \
  'const val EXAMPLE = "192.0.2.42"')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_rfc5737_doc_range_exempt"
else
  echo "FAIL test_rfc5737_doc_range_exempt: expected 0 (RFC 5737 doc range), got $rc" >&2
  fail=1
fi

# Test 5: a config/doc file (.json) is a legitimate home for connection literals
# and is exempt via the file-extension branch — even with a routable IP.
rc=$(run_fixture tools/lava-containers/vm-images.json \
  '{ "endpoint": "10.10.20.30" }')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_json_config_exempt"
else
  echo "FAIL test_json_config_exempt: expected 0 (.json config exempt), got $rc" >&2
  fail=1
fi

# Test 6 (LVA-058/062): a tracked .db file is exempt via the `\.db$` branch.
# The §11.4.95 git-tracked docs/workable_items.db is a binary SQLite DB; a
# routable-IP-shaped byte sequence in it must not fail the gate. Proven
# falsifiable: removing `|\.db$` from the scanner's exclusion regex makes this
# fixture FAIL.
rc=$(run_fixture docs/workable_items.db \
  'row|host=10.10.20.30|open')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_db_file_exempt"
else
  echo "FAIL test_db_file_exempt: expected 0 (.db exempt), got $rc" >&2
  fail=1
fi

if [[ "$fail" == "0" ]]; then
  echo "ALL PASS test_no_hardcoded_ipv4"
  exit 0
fi
echo "FAIL test_no_hardcoded_ipv4 (one or more cases failed)" >&2
exit 1
