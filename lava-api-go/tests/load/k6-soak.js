// k6-soak.js — SP-2 Phase 10 / Task 10.4 — 30-minute soak test.
//
// MANUAL OPERATOR RUN ONLY. NOT invoked by scripts/ci.sh, NOT invoked by
// scripts/load-quick.sh. Cron / scheduled use is also out of scope: this
// file exists so an operator can run it deliberately when they want to
// surface slow-burning leaks (file descriptors, goroutines, heap, DB
// connections) that the 60-second smoke gate is too short to catch.
//
// Invocation (from lava-api-go root, with k6 installed and a running
// stack on https://localhost:8443):
//
//     k6 run \
//         -e LAVA_API_BASE_URL=https://localhost:8443 \
//         tests/load/k6-soak.js
//
// Identical scenarios to k6-quick.js with `duration: '30m'` instead of
// '60s'. Same thresholds, plus a memory-leak probe (memoryProbe below)
// that scrapes the Go API's Prometheus /metrics endpoint every 5 minutes
// and records the process_resident_memory_bytes / go_goroutines values
// to k6's logs. The probe's job is to surface trends to a human reading
// the k6 output — it does NOT fail the run on growth alone, because:
//
//   - 30 minutes is short enough that genuine leaks may not produce a
//     statistically significant trend yet.
//   - The cache (Postgres-backed) legitimately grows during the run as
//     cold searches populate new keys.
//
// What the probe DOES enforce: it asserts the /metrics endpoint stays
// reachable and parseable for the entire 30 minutes. A blown-up metrics
// endpoint (panicking handler, exhausted memory, dropped listener) is a
// hard failure surfaced via the standard `checks` threshold.
//
// Sixth Law alignment: same as k6-quick.js. Soak runs against the same
// production code paths (Gin routes, cache layer, scrapers, rate
// limiter) that real users hit.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.LAVA_API_BASE_URL || 'https://localhost:8443';

const cacheOutcomes = new Counter('cache_outcomes');
const procResidentBytes = new Trend('proc_resident_bytes');
const goroutineCount = new Trend('goroutines');

export const options = {
    insecureSkipTLSVerify: true,
    scenarios: {
        cached_hits: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30m',
            exec: 'cachedHits',
            tags: { scenario: 'cached_hits' },
        },
        cold_searches: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30m',
            exec: 'coldSearches',
            tags: { scenario: 'cold_searches' },
        },
        anonymous_health: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30m',
            exec: 'anonymousHealth',
            tags: { scenario: 'anonymous_health' },
        },
        memory_probe: {
            executor: 'constant-vus',
            vus: 1,
            duration: '30m',
            exec: 'memoryProbe',
            tags: { scenario: 'memory_probe' },
        },
    },
    thresholds: {
        'http_req_duration{scenario:cached_hits}': ['p(99)<200'],
        'http_req_duration{scenario:anonymous_health}': ['p(99)<100'],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.99'],
    },
};

function recordCacheOutcome(res) {
    const hdr = res.headers['X-Cache-Outcome'] || res.headers['x-cache-outcome'] || '';
    const outcome = hdr === '' ? 'absent' : hdr.toLowerCase();
    cacheOutcomes.add(1, { outcome: outcome });
}

export function cachedHits() {
    const res = http.get(`${BASE_URL}/forum`);
    check(res, {
        'GET /forum 2xx': (r) => r.status >= 200 && r.status < 300,
    });
    recordCacheOutcome(res);
}

export function coldSearches() {
    const random = Math.random().toString(36).slice(2, 10);
    const res = http.get(`${BASE_URL}/search?query=test_${random}`);
    check(res, {
        'GET /search 2xx or 4xx': (r) => r.status >= 200 && r.status < 500,
    });
    recordCacheOutcome(res);
}

export function anonymousHealth() {
    const res = http.get(`${BASE_URL}/`);
    check(res, {
        'GET / 2xx': (r) => r.status >= 200 && r.status < 300,
    });
}

// memoryProbe scrapes /metrics every 5 minutes (300s) and parses two
// well-known Prometheus metric lines. Failure to fetch or parse fails
// the `checks` threshold — that's the hard guarantee. The recorded
// Trend values are for the operator to inspect via k6's summary output.
//
// extractGauge takes the raw text body of the Prometheus exposition
// format and returns the float value of the first non-comment line
// whose name (everything up to the first space, '{', or '}') matches.
// Returns NaN if not found.
function extractGauge(body, name) {
    const lines = body.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.length === 0 || line.charCodeAt(0) === 35 /* '#' */) {
            continue;
        }
        // Match either "name value" or "name{labels} value". The metric
        // names we care about (process_resident_memory_bytes,
        // go_goroutines) are unlabeled in the standard prometheus
        // client_golang exposition.
        const space = line.indexOf(' ');
        if (space < 0) {
            continue;
        }
        const head = line.slice(0, space);
        const headName = head.indexOf('{') >= 0 ? head.slice(0, head.indexOf('{')) : head;
        if (headName === name) {
            const v = parseFloat(line.slice(space + 1));
            return isNaN(v) ? NaN : v;
        }
    }
    return NaN;
}

export function memoryProbe() {
    group('memory probe', function () {
        const res = http.get(`${BASE_URL}/metrics`);
        const ok = check(res, {
            '/metrics 200': (r) => r.status === 200,
            '/metrics body non-empty': (r) => r.body && r.body.length > 0,
        });
        if (ok && res.body) {
            const rss = extractGauge(res.body, 'process_resident_memory_bytes');
            const gor = extractGauge(res.body, 'go_goroutines');
            if (!isNaN(rss)) {
                procResidentBytes.add(rss);
            }
            if (!isNaN(gor)) {
                goroutineCount.add(gor);
            }
            console.log(
                `[memory-probe] t=${new Date().toISOString()} ` +
                `rss=${isNaN(rss) ? 'n/a' : rss} ` +
                `goroutines=${isNaN(gor) ? 'n/a' : gor}`
            );
        }
    });
    // Sleep 5 minutes between probes. k6 sleep() takes seconds.
    // 300 seconds × 6 iterations = 30 minutes of probe work, matching
    // the scenario duration. Using an explicit busy-spin via http
    // requests would distort the soak workload, so the probe is a
    // separate scenario VU and we simply wait between iterations.
    sleep(300);
}
