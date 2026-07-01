#!/usr/bin/env bash
# scripts/autonomous-qa/lib-subsets.sh
# ---------------------------------------------------------------------------
# Emit every non-empty subset of the 5 matrix providers as the autonomous-QA
# provider-mix matrix. Operator decision (2026-06-30): broaden to ALL backed
# providers = {client-onboardable} ∩ {API-backed} (see QA_PROVIDERS below).
# EXHAUSTIVE — all 31 subsets × backends × queries.
#
# Each line: "<csv>|<slug>|<statehash>"
#   csv       comma-joined provider ids   (passed to Challenge70 qa_providers)
#   slug      dash-joined provider ids    (evidence dir name)
#   statehash short sha1 of csv           (state-hash per §11.4.128 layout)
#
# Plan: docs/superpowers/plans/2026-06-29-autonomous-qa-backend-provider-matrix.md
# ---------------------------------------------------------------------------
set -euo pipefail

# Provider ids — MUST match ProviderSpec.forId() in Challenge70 + the descriptors.
# Set = {client-onboardable} ∩ {API-backed via the live GET /providers list}:
#   - client-onboardable: providers with a native bundled descriptor under
#     core/tracker/* (registered in :core:tracker:client). In the production
#     onboarding flow the picker is repopulated from the chosen API's catalogue
#     (RemoteTrackerDescriptor, verified+apiSupported) which REPLACES the bundled
#     set — so a native provider is onboardable iff the API also vends it.
#   - API-backed: the ids the live lava-api-go /providers vends.
#   Intersection EXCLUDES rutor (native but NOT in /providers → not API-backed)
#   and iptorrents (Jackett-only, not in /providers; enabled separately).
QA_PROVIDERS=(rutracker nnmclub kinozal archiveorg gutenberg)

# qa_emit_subsets: print all 2^n - 1 non-empty subsets, one per line.
qa_emit_subsets() {
  local n=${#QA_PROVIDERS[@]}
  local total=$(( (1 << n) - 1 ))
  local mask i csv slug hash
  for (( mask=1; mask<=total; mask++ )); do
    csv=""; slug=""
    for (( i=0; i<n; i++ )); do
      if (( (mask >> i) & 1 )); then
        csv="${csv:+$csv,}${QA_PROVIDERS[i]}"
        slug="${slug:+$slug-}${QA_PROVIDERS[i]}"
      fi
    done
    hash="$(printf '%s' "$csv" | sha1sum | cut -c1-8)"
    printf '%s|%s|%s\n' "$csv" "$slug" "$hash"
  done
}

# Run directly to inspect the matrix (31 lines expected).
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  qa_emit_subsets
  echo "# total: $(qa_emit_subsets | wc -l) subsets" >&2
fi
