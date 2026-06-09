# Jackett new-version validation — 2026-06-09T13:14:03Z
## Operator directive: new Jackett version released + code changes pushed
Validated image: lscr.io/linuxserver/jackett:latest
Resolved version label: v0.24.2040-ls426 (built 2026-06-09 08:52:54Z — the new release)

## §6.B real-protocol probe (Jackett booted in loopback topology via podman 5.8.2)
- First-run api_key bootstrap: 32-char key generated into /config/Jackett/ServerConfig.json — OK
- Torznab aggregate caps endpoint (the EXACT path IPTorrentsJackettApi + the compose healthcheck use):
  GET /api/v2.0/indexers/all/results/torznab/api?t=caps&apikey=<key>
  → HTTP 200, valid Torznab <caps> XML:
    <?xml version="1.0" encoding="UTF-8"?>
    <caps>
      <server title="Jackett" />
      <limits default="1000" max="1000" />
      <searching>
        <search available="yes" supportedParams="q" />
        <tv-search available="no" supportedParams="q" />
        <movie-search available="no" supportedParams="q" />
        <music-search available="no" supportedParams="q" />
        <audio-search available="no" supportedParams="q" />
        <book-search available="no" supportedParams="q" />
      </searching>
      <categories />
    </caps>
## Verdict: the new Jackett version serves the Torznab contract our integration parses (JackettSearchDto/JackettResultMapper).
## JVM parser tests GREEN: JackettResultMapperTest 3/0/0, IPTorrentsSearchDelegationTest 2/0/0.
## OPERATOR-GATED (real creds): full IPTorrents search through Jackett+FlareSolverr needs real IPTorrents creds + the api_key bootstrapped in the live /config volume (scripts/validate-jackett-sidecar.sh --cloudflare).
