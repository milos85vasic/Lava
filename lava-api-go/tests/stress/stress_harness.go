//go:build stress

// Package stress is the lava-api-go stress + chaos test harness (Lava equivalent of
// HelixConstitution §11.4.85 — Stress + Chaos Test Mandate).
//
// It is build-tagged `stress` so it never runs in the default `go test ./...` suite.
// Run it via: go test -tags stress ./tests/stress/... -v
// Or via the Lava-side glue: scripts/run-chaos-stress.sh
//
// The harness is deliberately self-contained: it drives a real Gin handler through a real
// httptest.Server (a real loopback HTTP socket, real request/response cycle), records the
// latency of every single request, computes p50/p95/p99/max, and writes a JSON + Markdown
// evidence file. It reimplements NO production capability (§11.4.74) — it is test
// infrastructure: load generation + percentile recording + evidence emission.
//
// No fabricated numbers (§6.J / §11.4.6): every field in the evidence file comes from the
// actual run. Operator-gated dimensions that did not run are recorded as ran=false.
package stress

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// §11.4.85 minimums (closed-set, mechanically auditable).
const (
	minSustainedIters = 100 // def.1 sustained: N >= 100 sequential iterations
	minConcurrency    = 10  // def.1 concurrent: N >= 10 parallel invocations
)

// LatencyStats is the recorded latency distribution for one dimension.
type LatencyStats struct {
	Count   int     `json:"count"`
	MinNs   int64   `json:"min_ns"`
	MeanNs  int64   `json:"mean_ns"`
	P50Ns   int64   `json:"p50_ns"`
	P95Ns   int64   `json:"p95_ns"`
	P99Ns   int64   `json:"p99_ns"`
	MaxNs   int64   `json:"max_ns"`
	MinMs   float64 `json:"min_ms"`
	MeanMs  float64 `json:"mean_ms"`
	P50Ms   float64 `json:"p50_ms"`
	P95Ms   float64 `json:"p95_ms"`
	P99Ms   float64 `json:"p99_ms"`
	MaxMs   float64 `json:"max_ms"`
	WallSec float64 `json:"wall_clock_sec"`
}

// computeLatency turns a slice of per-request durations into a percentile distribution.
// Percentiles are nearest-rank on the sorted sample — deterministic and reproducible.
func computeLatency(samples []time.Duration, wall time.Duration) LatencyStats {
	n := len(samples)
	st := LatencyStats{Count: n, WallSec: wall.Seconds()}
	if n == 0 {
		return st
	}
	sorted := make([]time.Duration, n)
	copy(sorted, samples)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	var sum int64
	for _, d := range sorted {
		sum += d.Nanoseconds()
	}
	pick := func(p float64) time.Duration {
		// nearest-rank: idx = ceil(p/100 * n) - 1, clamped
		idx := int(p/100.0*float64(n)+0.999999) - 1
		if idx < 0 {
			idx = 0
		}
		if idx >= n {
			idx = n - 1
		}
		return sorted[idx]
	}
	st.MinNs = sorted[0].Nanoseconds()
	st.MaxNs = sorted[n-1].Nanoseconds()
	st.MeanNs = sum / int64(n)
	st.P50Ns = pick(50).Nanoseconds()
	st.P95Ns = pick(95).Nanoseconds()
	st.P99Ns = pick(99).Nanoseconds()
	ms := func(ns int64) float64 { return float64(ns) / 1e6 }
	st.MinMs, st.MeanMs = ms(st.MinNs), ms(st.MeanNs)
	st.P50Ms, st.P95Ms, st.P99Ms, st.MaxMs = ms(st.P50Ns), ms(st.P95Ns), ms(st.P99Ns), ms(st.MaxNs)
	return st
}

// DimensionResult is one stress/chaos dimension's outcome.
type DimensionResult struct {
	ID        string       `json:"id"` // S1 / S2 / C1 / C2 / C4a / C5 / C3 / C4b
	Name      string       `json:"name"`
	Ran       bool         `json:"ran"`    // false => OPERATOR_GATED or skipped; no fabricated metrics
	Status    string       `json:"status"` // PASS / FAIL / OPERATOR_GATED
	Requests  int          `json:"requests"`
	Status2xx int          `json:"status_2xx"`
	Status4xx int          `json:"status_4xx"`
	Status5xx int          `json:"status_5xx"`
	ErrorRate float64      `json:"error_rate"`
	Latency   LatencyStats `json:"latency"`
	// Chaos-specific fields (zero/empty for pure-stress dimensions).
	FaultType            string  `json:"fault_type,omitempty"`
	ErrorRateDuringFault float64 `json:"error_rate_during_fault,omitempty"`
	ErrorRateAfterFault  float64 `json:"error_rate_after_fault,omitempty"`
	RecoveryRequests     int     `json:"recovery_requests,omitempty"` // reqs until first 2xx after fault clears
	Notes                string  `json:"notes,omitempty"`
}

// Evidence is the full per-run evidence document.
type Evidence struct {
	Clause           string            `json:"clause"`
	GitSHA           string            `json:"git_sha"`
	GoVersion        string            `json:"go_version"`
	GOOS             string            `json:"goos"`
	GOARCH           string            `json:"goarch"`
	Host             string            `json:"host"`
	StartedUTC       string            `json:"started_utc"`
	WallSec          float64           `json:"wall_clock_sec"`
	GoroutinesBefore int               `json:"goroutines_before"`
	GoroutinesAfter  int               `json:"goroutines_after"`
	FDBefore         int               `json:"open_fd_before"`
	FDAfter          int               `json:"open_fd_after"`
	Dimensions       []DimensionResult `json:"dimensions"`
	Verdict          string            `json:"verdict"` // PASS / FAIL
	startTime        time.Time
}

func NewEvidence() *Evidence {
	host, _ := os.Hostname()
	return &Evidence{
		Clause:           "HelixConstitution §11.4.85 (Lava equivalent) — Stress + Chaos",
		GitSHA:           gitSHA(),
		GoVersion:        runtime.Version(),
		GOOS:             runtime.GOOS,
		GOARCH:           runtime.GOARCH,
		Host:             host,
		StartedUTC:       time.Now().UTC().Format(time.RFC3339),
		GoroutinesBefore: runtime.NumGoroutine(),
		FDBefore:         openFDCount(),
		startTime:        time.Now(),
	}
}

func (e *Evidence) Add(d DimensionResult) { e.Dimensions = append(e.Dimensions, d) }

// Finalize computes the run-level verdict per the §11.4.85 minimums and the leak guards.
func (e *Evidence) Finalize() {
	e.GoroutinesAfter = runtime.NumGoroutine()
	e.FDAfter = openFDCount()
	e.WallSec = time.Since(e.startTime).Seconds()
	verdict := "PASS"
	for _, d := range e.Dimensions {
		if d.Ran && d.Status == "FAIL" {
			verdict = "FAIL"
		}
	}
	// Leak guard: a large FD-count growth across the run is a resource leak (§11.4.85 def.1).
	if e.FDBefore > 0 && e.FDAfter > e.FDBefore+16 {
		verdict = "FAIL"
	}
	e.Verdict = verdict
}

// Write emits the JSON + Markdown evidence pair under tests/stress/evidence/<ts>/.
func (e *Evidence) Write() (string, error) {
	ts := time.Now().UTC().Format("2006-01-02T15-04-05Z")
	dir := filepath.Join("evidence", ts)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	jsonPath := filepath.Join(dir, "stress-chaos.json")
	b, err := json.MarshalIndent(e, "", "  ")
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(jsonPath, b, 0o644); err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(dir, "stress-chaos.md"), []byte(e.markdown()), 0o644); err != nil {
		return "", err
	}
	return jsonPath, nil
}

func (e *Evidence) markdown() string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "# Stress + Chaos evidence — %s\n\n", e.Clause)
	fmt.Fprintf(&sb, "- git SHA: `%s`\n- %s %s/%s on `%s`\n- started: %s | wall: %.2fs\n",
		e.GitSHA, e.GoVersion, e.GOOS, e.GOARCH, e.Host, e.StartedUTC, e.WallSec)
	fmt.Fprintf(&sb, "- goroutines: %d → %d | open FDs: %d → %d\n- **VERDICT: %s**\n\n",
		e.GoroutinesBefore, e.GoroutinesAfter, e.FDBefore, e.FDAfter, e.Verdict)
	fmt.Fprintf(&sb, "| Dim | Name | Ran | Status | Reqs | 2xx | 4xx | 5xx | errRate | p50ms | p95ms | p99ms | maxms |\n")
	fmt.Fprintf(&sb, "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
	for _, d := range e.Dimensions {
		fmt.Fprintf(&sb, "| %s | %s | %v | %s | %d | %d | %d | %d | %.3f | %.2f | %.2f | %.2f | %.2f |\n",
			d.ID, d.Name, d.Ran, d.Status, d.Requests, d.Status2xx, d.Status4xx, d.Status5xx,
			d.ErrorRate, d.Latency.P50Ms, d.Latency.P95Ms, d.Latency.P99Ms, d.Latency.MaxMs)
	}
	sb.WriteString("\n")
	for _, d := range e.Dimensions {
		if d.FaultType != "" {
			fmt.Fprintf(&sb, "- **%s chaos** (%s): errRate during fault = %.3f, after fault = %.3f, recovery in %d req(s). %s\n",
				d.ID, d.FaultType, d.ErrorRateDuringFault, d.ErrorRateAfterFault, d.RecoveryRequests, d.Notes)
		}
		if !d.Ran {
			fmt.Fprintf(&sb, "- **%s %s**: %s — %s\n", d.ID, d.Name, d.Status, d.Notes)
		}
	}
	return sb.String()
}

// --- load drivers -----------------------------------------------------------------------

type reqOutcome struct {
	dur  time.Duration
	code int
	err  error
}

// driveSustained issues `iters` sequential GET requests against url, recording each latency.
func driveSustained(client *http.Client, url string, iters int) (LatencyStats, []reqOutcome) {
	out := make([]reqOutcome, 0, iters)
	lat := make([]time.Duration, 0, iters)
	start := time.Now()
	for i := 0; i < iters; i++ {
		t0 := time.Now()
		resp, err := client.Get(url)
		d := time.Since(t0)
		code := 0
		if err == nil {
			code = resp.StatusCode
			resp.Body.Close()
		}
		out = append(out, reqOutcome{dur: d, code: code, err: err})
		lat = append(lat, d)
	}
	return computeLatency(lat, time.Since(start)), out
}

// driveConcurrent issues `n` parallel GET requests; all must complete.
func driveConcurrent(client *http.Client, url string, n int) (LatencyStats, []reqOutcome) {
	out := make([]reqOutcome, n)
	var lat sync.Mutex
	lats := make([]time.Duration, 0, n)
	var wg sync.WaitGroup
	var done int32
	start := time.Now()
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			t0 := time.Now()
			resp, err := client.Get(url)
			d := time.Since(t0)
			code := 0
			if err == nil {
				code = resp.StatusCode
				resp.Body.Close()
			}
			out[idx] = reqOutcome{dur: d, code: code, err: err}
			lat.Lock()
			lats = append(lats, d)
			lat.Unlock()
			atomic.AddInt32(&done, 1)
		}(i)
	}
	wg.Wait()
	return computeLatency(lats, time.Since(start)), out
}

// summarize folds outcomes into status-code counts + error rate.
func summarize(outs []reqOutcome) (r2, r4, r5 int, errRate float64) {
	total := len(outs)
	if total == 0 {
		return
	}
	bad := 0
	for _, o := range outs {
		switch {
		case o.err != nil:
			bad++
		case o.code >= 200 && o.code < 300:
			r2++
		case o.code >= 400 && o.code < 500:
			r4++
		case o.code >= 500:
			r5++
			bad++
		}
	}
	errRate = float64(bad) / float64(total)
	return
}

// --- host introspection (no sudo, read-only) --------------------------------------------

func gitSHA() string {
	out, err := exec.Command("git", "rev-parse", "HEAD").Output()
	if err != nil {
		return "UNKNOWN"
	}
	return strings.TrimSpace(string(out))
}

// openFDCount best-effort counts open file descriptors for the current process.
// On darwin/linux /dev/fd lists them. Returns 0 if unreadable (recorded as "unknown", not faked).
func openFDCount() int {
	entries, err := os.ReadDir("/dev/fd")
	if err != nil {
		return 0
	}
	return len(entries)
}
