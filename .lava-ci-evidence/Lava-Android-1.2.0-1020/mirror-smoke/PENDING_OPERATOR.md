# Mirror smoke (Phase 5 evidence pack)

status: PENDING_OPERATOR

The operator MUST run `./scripts/sync-tracker-sdk-mirrors.sh --check`
(or the manual equivalent) and verify that:

1. The Tracker-SDK pin in this repo matches the HEAD of
   `vasic-digital/Tracker-SDK` on both GitHub and GitLab.
2. The two upstreams report the same SHA for that pin (clause 6.C
   mirror-state mismatch check).

The check output JSON goes in this directory; this stub will be
replaced.
