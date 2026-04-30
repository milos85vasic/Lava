#!/usr/bin/env bash
# scripts/check-fixture-freshness.sh — block stale tracker fixtures.
#
# Per the SP-3a plan Task 5.18 + the developer guide §4 testing
# requirements. Fixtures named with a YYYY-MM-DD date in the filename
# are considered "fresh" if the date is <30 days old (no warning),
# "stale" if 30-60 days old (warn), and "expired" if >60 days old
# (block — exit non-zero).
#
# Sixth Law clause 1 ("same surfaces the user touches") implies that
# parsers must be tested against HTML that resembles what the user
# actually sees today. Trackers change their HTML structure; an old
# fixture is a green-test-against-stale-shape bluff.

set -euo pipefail

cd "$(dirname "$0")/.."

THIRTY_DAYS_AGO=$(date -d '30 days ago' +%s)
SIXTY_DAYS_AGO=$(date -d '60 days ago' +%s)

warn=0
blocked=0

# Fixtures live under core/tracker/<id>/src/test/resources/fixtures/.
fixture_glob="core/tracker/*/src/test/resources/fixtures"

for fixture_dir in $fixture_glob; do
  if [[ ! -d "$fixture_dir" ]]; then continue; fi
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    fname=$(basename "$f")
    date_in_name=$(echo "$fname" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' || echo "")
    if [[ -z "$date_in_name" ]]; then
      # Fixture without a date in the name is allowed (e.g. constants).
      continue
    fi
    ts=$(date -d "$date_in_name" +%s 2>/dev/null || echo 0)
    if [[ "$ts" -eq 0 ]]; then continue; fi

    if [[ "$ts" -lt "$SIXTY_DAYS_AGO" ]]; then
      echo "BLOCK: $f (>60 days old, dated $date_in_name)" >&2
      blocked=$((blocked + 1))
    elif [[ "$ts" -lt "$THIRTY_DAYS_AGO" ]]; then
      echo "WARN: $f (>30 days old, dated $date_in_name)"
      warn=$((warn + 1))
    fi
  done < <(find "$fixture_dir" -type f \( -name '*.html' -o -name '*.htm' \))
done

if [[ "$blocked" -gt 0 ]]; then
  echo "" >&2
  echo "$blocked fixture(s) over 60 days old. Refresh from the live tracker before merging." >&2
  exit 1
fi

if [[ "$warn" -gt 0 ]]; then
  echo ""
  echo "$warn fixture(s) over 30 days old (warning, non-blocking). Plan a refresh."
fi

echo "Fixture freshness check passed."
exit 0
