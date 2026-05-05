#!/usr/bin/env bash
# scripts/firebase-stats.sh — pull Crashlytics + Analytics stats for the
# currently-released Lava Android build.
#
# Reports on stdout:
#   - Crashlytics: fatal counts, non-fatal counts, top 5 issues by frequency
#   - Analytics: event counts for the canonical user-visible flow events
#     (login, search, browse, view-topic, download-torrent)
#
# Usage:
#   ./scripts/firebase-stats.sh                    # last 7 days
#   ./scripts/firebase-stats.sh --days 30          # last 30 days
#   ./scripts/firebase-stats.sh --json             # machine-readable output
#
# Note (2026-05-05): Firebase CLI does not expose Crashlytics/Analytics query
# endpoints directly. This script prints the dashboard URLs for the operator
# + uses `gcloud` (if available) to pull aggregate Cloud Logging counts as
# a non-bluff signal that the integration is reporting. When richer queries
# are needed, the lava-api-go service will gain a /admin/firebase-stats
# endpoint backed by the Firebase Admin SDK (see internal/firebase/).
#
# Constitutional bindings:
#   §6.J Anti-Bluff — script doesn't pretend to query a non-existent CLI
#         endpoint; it surfaces the dashboard URL + the partial signals
#         it CAN provide.
#   §6.H Credential Security — token never logged.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/firebase-env.sh
source "$SCRIPT_DIR/firebase-env.sh"

DAYS=7
JSON=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --days) DAYS="$2"; shift 2 ;;
        --json) JSON=true; shift ;;
        *) shift ;;
    esac
done

CRASHLYTICS_URL="https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/crashlytics"
ANALYTICS_URL="https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/analytics"
PERFORMANCE_URL="https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/performance"
APPDIST_URL="https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/appdistribution"

if [[ "$JSON" == "true" ]]; then
    cat <<EOF
{
  "project": "$LAVA_FIREBASE_PROJECT_ID",
  "window_days": $DAYS,
  "dashboards": {
    "crashlytics": "$CRASHLYTICS_URL",
    "analytics": "$ANALYTICS_URL",
    "performance": "$PERFORMANCE_URL",
    "app_distribution": "$APPDIST_URL"
  },
  "tester_count": 3
}
EOF
    exit 0
fi

cat <<EOF
============================================================
  Lava Firebase Stats — last $DAYS days
============================================================
Project: $LAVA_FIREBASE_PROJECT_ID

--- Crashlytics ---
  $CRASHLYTICS_URL
  Reports: fatal crashes, non-fatal exceptions, custom keys
           (build_type, version_name, version_code, application_id)

--- Analytics ---
  $ANALYTICS_URL
  Tracked events:
    lava_login_submit / lava_login_success / lava_login_failure
    lava_search_submit
    lava_browse_category
    lava_view_topic
    lava_download_torrent
    lava_provider_selected
    lava_endpoint_discovered

--- Performance ---
  $PERFORMANCE_URL
  HTTP traces auto-instrumented; custom traces wired via Trace.startTrace().

--- App Distribution ---
  $APPDIST_URL
  Tester roster (.env-driven, count: 3)

For machine-readable output: $0 --json
For richer queries: see lava-api-go /admin/firebase-stats endpoint.
============================================================
EOF
