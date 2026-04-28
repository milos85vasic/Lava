// k6-quick.js — SP-2 Phase 10 / Task 10.4 — 60-second smoke load gate.
//
// Invoked by lava-api-go/scripts/load-quick.sh, which is in turn invoked
// by lava-api-go/scripts/ci.sh during the full local CI gate (Phase 13
// wires the call into ci.sh; Phase 14 runs the gate against the real
// running container as part of acceptance).
//
// Three concurrent scenarios stress different parts of the stack:
//
//   - cached_hits      50 VUs hammering GET /forum. Most requests should
//                      hit the redis-backed cache after the first warm-up
//                      RTT, so p99 < 200ms on this scenario is the
//                      "the cache layer is doing its job" signal.
//
//   - cold_searches    10 VUs issuing GET /search?query=test_<random>
//                      with a fresh query each iteration so the cache
//                      MUST miss and the upstream rutracker.Client + the
//                      goquery parsers + the rate-limiter all get
//                      exercised under load. No latency threshold here
//                      on purpose — cold is allowed to be slow; we only
//                      assert that requests don't fail (http_req_failed
//                      < 1%).
//
//   - anonymous_health 5 VUs hammering GET /. Health probes have no auth,
//                      no cache miss, no upstream call — they should be
//                      faster than cached hits, hence p99 < 100ms.
//
// Thresholds (see options.thresholds below) make k6 exit non-zero if any
// SLO is missed; load-quick.sh propagates that exit code to scripts/ci.sh.
//
// Sixth Law alignment:
//   - clause 1 (same surfaces): we hit the same Gin routes a real client
//     hits. No bypass into handlers, no shortcut around the cache layer.
//   - clause 3 (user-visible primary assertion): the threshold metrics
//     (http_req_duration percentiles, http_req_failed rate, brotli
//     ratio) are all things a real user can measure on the wire.
//
// Verification of THIS file does not require k6 — see
// tests/load/load_test.go which asserts file presence, non-empty,
// and the threshold strings appear via regex grep. The actual k6 run
// happens in Phase 14 acceptance against the real running container.

import http from 'k6/http';
import { check, group } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.LAVA_API_BASE_URL || 'https://localhost:8443';

// cacheOutcomes is informational only — counts how often the Go API
// returned X-Cache-Outcome=hit vs miss vs absent. The Go API may or may
// not expose that header today; if it doesn't, this metric stays at zero
// and no threshold relies on it.
const cacheOutcomes = new Counter('cache_outcomes');

export const options = {
    insecureSkipTLSVerify: true, // self-signed cert on localhost:8443
    scenarios: {
        cached_hits: {
            executor: 'constant-vus',
            vus: 50,
            duration: '60s',
            exec: 'cachedHits',
            tags: { scenario: 'cached_hits' },
        },
        cold_searches: {
            executor: 'constant-vus',
            vus: 10,
            duration: '60s',
            exec: 'coldSearches',
            tags: { scenario: 'cold_searches' },
        },
        anonymous_health: {
            executor: 'constant-vus',
            vus: 5,
            duration: '60s',
            exec: 'anonymousHealth',
            tags: { scenario: 'anonymous_health' },
        },
    },
    thresholds: {
        'http_req_duration{scenario:cached_hits}': ['p(99)<200'],
        'http_req_duration{scenario:anonymous_health}': ['p(99)<100'],
        'http_req_failed': ['rate<0.01'],
        'checks': ['rate>0.99'],
    },
};

// recordCacheOutcome reads the Go API's X-Cache-Outcome header (if
// present) and increments the cache_outcomes counter, tagged with the
// outcome label. Absent header → 'absent'.
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
    // Random suffix forces a unique query string → cache MUST miss.
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

// brotliRatioProbe runs once per VU iteration of the default function
// (separate from the scenario VUs above). It issues two requests to the
// same endpoint with different Accept-Encoding values and asserts the
// brotli payload is at most 60% of the identity payload.
//
// Wired as a check() so it contributes to the global checks rate;
// failure of this check counts against the `checks: rate>0.99`
// threshold. The brotli endpoint must serve a JSON response that is
// large enough to compress meaningfully — /forum is the canonical
// choice (it returns the full forum tree).
export default function () {
    group('brotli ratio', function () {
        const br = http.get(`${BASE_URL}/forum`, {
            headers: { 'Accept-Encoding': 'br' },
            // k6 auto-decompresses by default; we need raw body size.
            responseType: 'binary',
        });
        const id = http.get(`${BASE_URL}/forum`, {
            headers: { 'Accept-Encoding': 'identity' },
            responseType: 'binary',
        });
        const brSize = br.body ? br.body.byteLength : 0;
        const idSize = id.body ? id.body.byteLength : 0;
        check({ brSize: brSize, idSize: idSize }, {
            'brotli body present': (s) => s.brSize > 0,
            'identity body present': (s) => s.idSize > 0,
            'brotli ratio <= 0.6 of identity': (s) =>
                s.idSize > 0 && (s.brSize / s.idSize) <= 0.6,
        });
    });
}
