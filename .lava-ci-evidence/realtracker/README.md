# Real-tracker crown-jewel evidence

This directory holds the REAL-network evidence produced by the crown-jewel
verification suite — the per-provider integration tests that replace manual
human testing of "does a download option actually work" against the four real
Russian trackers (rutracker, rutor, kinozal, nnmclub).

Each `<provider>-<date>.json` file is written by
`lava.tracker.testing.RealTrackerHarness.writeEvidence` ONLY when a run actually
reached the network and downloaded a real `.torrent`. The mere presence of a
file is therefore proof that an outbound real-tracker run happened on that date.

Run the suite (gated OFF by default; honest SKIP when unreachable):

```bash
# Export the real credentials from the gitignored .env first, e.g.:
#   set -a; source .env; set +a
./gradlew \
  :core:tracker:rutracker:test \
  :core:tracker:rutor:test \
  :core:tracker:kinozal:test \
  :core:tracker:nnmclub:test \
  -PrealTrackers=true --no-daemon --max-workers=2
```

Without `-PrealTrackers=true` the tests `assumeTrue`-SKIP and make no outbound
calls. Credentials are NEVER hardcoded (§6.R); they are read at runtime from the
environment (`RUTRACKER_USERNAME/PASSWORD`, `RUTOR_USERNAME/PASSWORD`,
`KINOZAL_USERNAME/PASSWORD`, `NNMCLUB_USERNAME/PASSWORD`). Passwords are never
written into the evidence JSON.

A file records: downloaded byte length + sha256, the `TorrentFileValidator`
verdict + info-hash, the magnet + `MagnetLinkValidator` verdict + btih, and the
cross-check between the two info-hashes when both surfaces exist.
