#!/usr/bin/env bash
# tests/vm-distro/boot-and-probe.sh — runs INSIDE each VM in the matrix.
# Starts proxy.jar + lava-api-go in background; probes 4 endpoints;
# writes /tmp/probe-output.json with 4 booleans.
#
# The 4 probes:
#   - proxy_health     — proxy /health endpoint
#   - proxy_search     — proxy /search?q=test endpoint (sanity-check
#                        rutracker scraper wiring; doesn't require
#                        real upstream — proxy returns its own error
#                        path which counts as "responsive")
#   - goapi_health     — lava-api-go /health (HTTPS on 8443)
#   - goapi_metrics    — lava-api-go /metrics (Prometheus)
set -uo pipefail

# JRE + tooling install (idempotent across distro families).
if ! command -v java >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache openjdk17-jre-headless curl jq
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y --no-install-recommends openjdk-17-jre-headless curl jq
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-17-openjdk-headless curl jq
  fi
fi

# Start proxy in background.
java -jar /tmp/proxy.jar >/tmp/proxy.log 2>&1 &
PROXY_PID=$!

# Start go-api in background.
chmod +x /tmp/lava-api-go
/tmp/lava-api-go >/tmp/goapi.log 2>&1 &
GOAPI_PID=$!

# Wait up to 60s for both to come up. We don't poll readiness here
# because the matrix gate's purpose is "did the binaries start AND
# serve their endpoints" — a long sleep is the simplest reproducible
# strategy across distros. Refine in a follow-up if matrix runtime
# becomes a concern.
sleep 60

proxy_health=false
proxy_search=false
goapi_health=false
goapi_metrics=false
if curl -fsS http://localhost:8080/health >/dev/null 2>&1; then proxy_health=true; fi
if curl -fsS "http://localhost:8080/search?q=test" >/dev/null 2>&1; then proxy_search=true; fi
if curl -fsSk https://localhost:8443/health >/dev/null 2>&1 \
  || curl -fsS http://localhost:8443/health >/dev/null 2>&1; then goapi_health=true; fi
if curl -fsS http://localhost:9000/metrics >/dev/null 2>&1; then goapi_metrics=true; fi

jq -n \
  --argjson ph $proxy_health \
  --argjson ps $proxy_search \
  --argjson gh $goapi_health \
  --argjson gm $goapi_metrics \
  '{proxy_health:$ph, proxy_search:$ps, goapi_health:$gh, goapi_metrics:$gm}' \
  > /tmp/probe-output.json

kill $PROXY_PID $GOAPI_PID 2>/dev/null || true
echo "Probed: proxy_health=$proxy_health proxy_search=$proxy_search goapi_health=$goapi_health goapi_metrics=$goapi_metrics"
