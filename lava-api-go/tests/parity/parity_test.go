// Package parity is the SP-2 Phase 10 / Task 10.3 cross-backend parity gate.
//
// It is the Sixth Law load-bearing acceptance gate per the design doc
// (`docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md` §11.1):
// the migration's correctness signal is "the new Go API produces the SAME
// wire bytes as the legacy Ktor proxy". Unit tests on the Go side alone
// cannot catch divergence because they don't know what the Ktor side
// actually emits — only a side-by-side comparison does.
//
// The full gate (the comprehensive 16 endpoints x {anon, authenticated} x
// {empty/tiny/medium body} matrix) runs in Phase 14, when the compose stack
// brings up both backends. This file ships the framework now and a small
// starter fixture set so the framework grows from a stable base instead of
// being authored under release pressure.
//
// Sixth Law alignment:
//   - Clause 1 (same surfaces the user touches): the framework speaks raw
//     HTTP to both backends — the same wire the Android client speaks.
//     No shortcut into either backend's internals.
//   - Clause 2 (provably falsifiable): the comparator's correctness is
//     proven below by 9 unit tests on synthetic inputs that drive each
//     failure mode (body byte diff, JSON-key reorder under exact mode,
//     missing allowlisted header, status mismatch, structural JSON diff).
//     The three plan-mandated rehearsals against real backends (corrupt
//     body, reorder JSON keys, drop header) are deferred to Phase 14
//     because they require real running services; see the commit body.
//   - Clause 3 (primary assertion on user-visible state): the chief
//     failure signal is the wire bytes returned to the HTTP client —
//     status code, response body, headers — i.e. exactly what an Android
//     client sees.
//   - Clause 4 (load-bearing acceptance gate): TestParity is the gate.
//     A green run against both backends means a real client sees
//     byte-identical (or canonically equivalent) responses regardless of
//     which backend it points at.
//
// Skip semantics: TestParity skips cleanly when either LAVA_PARITY_KTOR_URL
// or LAVA_PARITY_GO_URL is unset. The comparator unit tests run
// unconditionally and need no env vars.
package parity

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"gopkg.in/yaml.v3"
)

// ComparisonMode selects how the response body is compared between the two
// backends. Three modes are supported:
//
//   - CompareExact: byte-for-byte equality. Right for binary endpoints
//     (/captcha/{path}, /download/{id}) and for cases where the wire shape
//     itself (including key order, whitespace, trailing newline) is part of
//     the contract.
//   - CompareJSON: structural deep-equal after JSON decoding. Tolerates
//     whitespace differences but NOT key reordering for objects parsed into
//     map[string]any (Go's json.Unmarshal makes that order-independent
//     anyway). Use when you want to assert "same data" without committing
//     to byte equality.
//   - CompareJSONUnordered: canonicalise both sides (decode → re-encode
//     with encoding/json's deterministic key order), then byte-compare.
//     Functionally equivalent to CompareJSON for JSON inputs but produces
//     a clearer diff in the failure message because the canonicalised
//     bytes are diffed directly.
type ComparisonMode string

const (
	CompareExact         ComparisonMode = "exact"
	CompareJSON          ComparisonMode = "json"
	CompareJSONUnordered ComparisonMode = "json_unordered"
)

// ParityCase is the on-disk shape of a *.yaml fixture under
// tests/fixtures/parity/. Each fixture is one concrete request to send
// to both backends. Path is concrete (e.g. "/forum/123") rather than
// templated (e.g. "/forum/{id}") because the parity gate compares wire
// bytes — there is no template substitution at this layer.
type ParityCase struct {
	Name            string            `yaml:"name"`
	Method          string            `yaml:"method"`
	Path            string            `yaml:"path"`
	Query           map[string]string `yaml:"query"`
	Headers         map[string]string `yaml:"headers"`
	Body            string            `yaml:"body"`
	ContentType     string            `yaml:"content_type"`
	ExpectedStatus  int               `yaml:"expected_status"`
	Compare         ComparisonMode    `yaml:"compare"`
	HeaderAllowList []string          `yaml:"header_allowlist"`
}

// fixturesDir is the on-disk location of the parity fixtures, relative
// to this test file. Resolved via filepath.Join so the test works whether
// `go test` is run from the repo root or from this package directory.
const fixturesDir = "../fixtures/parity"

// requestTimeout caps each round-trip. Both backends are local in Phase 14
// (compose stack on the same host), so 30s is more than enough; the goal
// is to surface hangs as failures rather than letting `go test` time out.
const requestTimeout = 30 * time.Second

// loadCases walks fixturesDir and decodes every *.yaml file into a
// ParityCase. The fixture set is intentionally small in this commit; the
// 16-endpoint matrix gets populated in Phase 14.
func loadCases(t *testing.T) []ParityCase {
	t.Helper()
	pattern := filepath.Join(fixturesDir, "*.yaml")
	matches, err := filepath.Glob(pattern)
	if err != nil {
		t.Fatalf("glob %s: %v", pattern, err)
	}
	cases := make([]ParityCase, 0, len(matches))
	for _, path := range matches {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		var c ParityCase
		if err := yaml.Unmarshal(raw, &c); err != nil {
			t.Fatalf("yaml %s: %v", path, err)
		}
		if c.Name == "" {
			t.Fatalf("fixture %s: empty name", path)
		}
		if c.Method == "" {
			t.Fatalf("fixture %s (%s): empty method", path, c.Name)
		}
		if c.Path == "" {
			t.Fatalf("fixture %s (%s): empty path", path, c.Name)
		}
		if c.Compare == "" {
			c.Compare = CompareJSONUnordered
		}
		cases = append(cases, c)
	}
	return cases
}

// runRequest builds and executes the HTTP request described by c against
// baseURL. It returns the wire-level outcome — status, body bytes, and
// headers — which is exactly what compareResponses needs.
func runRequest(t *testing.T, baseURL string, c ParityCase) (status int, body []byte, headers http.Header) {
	t.Helper()
	u, err := url.Parse(baseURL)
	if err != nil {
		t.Fatalf("parse base %q: %v", baseURL, err)
	}
	// Append c.Path to the base path so the test works with a base URL
	// that includes a path prefix (e.g. http://host:8080/api/v1).
	u.Path = strings.TrimRight(u.Path, "/") + c.Path
	if len(c.Query) > 0 {
		q := u.Query()
		for k, v := range c.Query {
			q.Set(k, v)
		}
		u.RawQuery = q.Encode()
	}
	var bodyReader io.Reader
	if c.Body != "" {
		bodyReader = strings.NewReader(c.Body)
	}
	req, err := http.NewRequest(c.Method, u.String(), bodyReader)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if c.ContentType != "" {
		req.Header.Set("Content-Type", c.ContentType)
	}
	for k, v := range c.Headers {
		req.Header.Set(k, v)
	}
	client := &http.Client{Timeout: requestTimeout}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request %s %s: %v", c.Method, u.String(), err)
	}
	defer resp.Body.Close()
	body, err = io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return resp.StatusCode, body, resp.Header
}

// response bundles the wire-level outcome of one HTTP round-trip. Defined
// at package level (rather than inline at every call site) so the
// comparator unit tests can construct synthetic responses without the
// noise of repeated anonymous-struct literals.
type response struct {
	Status  int
	Body    []byte
	Headers http.Header
}

// failer is the minimal subset of testing.TB the comparator actually uses.
// Threading the comparator through this interface (rather than *testing.T
// directly) lets the comparator unit tests below capture the produced
// failure messages without spawning subtests or driving testing.TB
// machinery — which would otherwise force us to either call t.Run inside
// every assertion or accept a real failure on the surrounding test.
type failer interface {
	Helper()
	Errorf(format string, args ...interface{})
}

// compareResponses is the heart of the parity gate. It compares status
// (always exact), body (per c.Compare), and the subset of headers in
// c.HeaderAllowList. A failure in any of the three trips the test with a
// message that names the specific divergence.
func compareResponses(t failer, c ParityCase, ktor, goapi response) {
	t.Helper()
	if ktor.Status != goapi.Status {
		t.Errorf("status mismatch: ktor=%d go=%d", ktor.Status, goapi.Status)
	}
	if c.ExpectedStatus != 0 && ktor.Status != c.ExpectedStatus {
		t.Errorf("ktor status %d differs from expected %d", ktor.Status, c.ExpectedStatus)
	}
	switch c.Compare {
	case CompareExact:
		if !bytes.Equal(ktor.Body, goapi.Body) {
			t.Errorf("body bytes differ at offset %d (ktor=%dB go=%dB):\nktor: %s\ngo:   %s",
				firstDiffOffset(ktor.Body, goapi.Body), len(ktor.Body), len(goapi.Body),
				truncate(ktor.Body, 200), truncate(goapi.Body, 200))
		}
	case CompareJSON, "":
		var kAny, gAny any
		if err := json.Unmarshal(ktor.Body, &kAny); err != nil {
			t.Errorf("ktor body is not JSON: %v", err)
			return
		}
		if err := json.Unmarshal(goapi.Body, &gAny); err != nil {
			t.Errorf("go body is not JSON: %v", err)
			return
		}
		if !reflect.DeepEqual(kAny, gAny) {
			t.Errorf("JSON structural diff:\nktor: %s\ngo:   %s",
				truncate(ktor.Body, 400), truncate(goapi.Body, 400))
		}
	case CompareJSONUnordered:
		kCanon, err := canonicaliseJSON(ktor.Body)
		if err != nil {
			t.Errorf("canonicalise ktor body: %v", err)
			return
		}
		gCanon, err := canonicaliseJSON(goapi.Body)
		if err != nil {
			t.Errorf("canonicalise go body: %v", err)
			return
		}
		if !bytes.Equal(kCanon, gCanon) {
			t.Errorf("canonical JSON differs at offset %d:\nktor: %s\ngo:   %s",
				firstDiffOffset(kCanon, gCanon), truncate(kCanon, 400), truncate(gCanon, 400))
		}
	default:
		t.Errorf("unknown comparison mode %q", c.Compare)
	}
	for _, h := range c.HeaderAllowList {
		kv := ktor.Headers.Get(h)
		gv := goapi.Headers.Get(h)
		if kv != gv {
			t.Errorf("header %q differs: ktor=%q go=%q", h, kv, gv)
		}
		// A header that was supposed to be in the allowlist but is empty on
		// BOTH sides is almost certainly a fixture authoring error — surface
		// it so the fixture gets corrected.
		if kv == "" && gv == "" {
			t.Errorf("header %q is in the allowlist but neither backend emits it", h)
		}
	}
}

// canonicaliseJSON decodes b and re-encodes it with encoding/json, which
// (per the package docs) writes map keys in sorted ascending order. Slices
// preserve order — JSON arrays are ordered by spec, so this is correct.
// The output is the canonical form used by CompareJSONUnordered.
func canonicaliseJSON(b []byte) ([]byte, error) {
	if len(bytes.TrimSpace(b)) == 0 {
		return b, nil
	}
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		return nil, fmt.Errorf("unmarshal: %w", err)
	}
	out, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}
	return out, nil
}

// firstDiffOffset returns the byte index where a and b first differ.
// Returns -1 if they are equal. Used to make exact-mismatch errors point
// at the offending bytes instead of dumping the whole body.
func firstDiffOffset(a, b []byte) int {
	n := len(a)
	if len(b) < n {
		n = len(b)
	}
	for i := 0; i < n; i++ {
		if a[i] != b[i] {
			return i
		}
	}
	if len(a) != len(b) {
		return n
	}
	return -1
}

// truncate clips b to at most n bytes for inclusion in error messages.
func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "...(truncated)"
}

// TestParity is the parity gate. It SKIPS unless both backend URLs are
// set; in Phase 14 the compose stack starts both backends and exports
// the env vars before running this test.
func TestParity(t *testing.T) {
	ktorURL := os.Getenv("LAVA_PARITY_KTOR_URL")
	goURL := os.Getenv("LAVA_PARITY_GO_URL")
	if ktorURL == "" || goURL == "" {
		t.Skip("LAVA_PARITY_KTOR_URL and LAVA_PARITY_GO_URL must be set; parity gate runs in Phase 14")
	}
	cases := loadCases(t)
	if len(cases) == 0 {
		t.Fatal("no parity cases found in tests/fixtures/parity/*.yaml")
	}
	for _, c := range cases {
		c := c
		t.Run(c.Name, func(t *testing.T) {
			ks, kb, kh := runRequest(t, ktorURL, c)
			gs, gb, gh := runRequest(t, goURL, c)
			compareResponses(t, c,
				response{Status: ks, Body: kb, Headers: kh},
				response{Status: gs, Body: gb, Headers: gh},
			)
		})
	}
}

// ----------------------------------------------------------------------
// Comparator unit tests
//
// These run unconditionally — no env vars, no backends — and prove the
// comparator's correctness on synthetic inputs. They are the Sixth Law
// clause-2 falsifiability evidence for the framework itself; the three
// plan-mandated rehearsals against real backends (corrupt response body,
// reorder JSON keys, drop a header) are deferred to Phase 14 because they
// require real running services.
// ----------------------------------------------------------------------

// stubFailer captures the failure messages produced by compareResponses
// so the comparator unit tests below can assert on them without failing
// the surrounding test.
type stubFailer struct {
	errors []string
}

func (s *stubFailer) Helper() {}
func (s *stubFailer) Errorf(format string, args ...interface{}) {
	s.errors = append(s.errors, fmt.Sprintf(format, args...))
}
func (s *stubFailer) Failed() bool { return len(s.errors) > 0 }

func TestCompareResponses_StatusMatch_BodyMatch_PassesAll(t *testing.T) {
	body := []byte(`{"a":1,"b":2}`)
	hdr := http.Header{"Content-Type": []string{"application/json"}}
	for _, mode := range []ComparisonMode{CompareExact, CompareJSON, CompareJSONUnordered} {
		c := ParityCase{Name: "ok", Compare: mode, HeaderAllowList: []string{"Content-Type"}}
		st := &stubFailer{}
		compareResponses(st, c,
			response{Status: 200, Body: body, Headers: hdr},
			response{Status: 200, Body: body, Headers: hdr},
		)
		if st.Failed() {
			t.Errorf("mode %s: expected no failures, got %v", mode, st.errors)
		}
	}
}

func TestCompareResponses_StatusMismatch_FailsAll(t *testing.T) {
	body := []byte(`{"a":1}`)
	for _, mode := range []ComparisonMode{CompareExact, CompareJSON, CompareJSONUnordered} {
		c := ParityCase{Name: "x", Compare: mode}
		st := &stubFailer{}
		compareResponses(st, c,
			response{Status: 200, Body: body},
			response{Status: 500, Body: body},
		)
		if !st.Failed() {
			t.Errorf("mode %s: expected status failure", mode)
			continue
		}
		if !containsAny(st.errors, "status mismatch") {
			t.Errorf("mode %s: failure message %v lacks 'status mismatch'", mode, st.errors)
		}
	}
}

func TestCompareResponses_BodyByteDiff_ExactFails_JSONUnorderedPasses(t *testing.T) {
	// Same JSON object content; different key order. CompareExact must
	// flag the byte-level divergence; CompareJSONUnordered must accept
	// because canonicalisation collapses the order.
	ktor := []byte(`{"a":1,"b":2}`)
	goapi := []byte(`{"b":2,"a":1}`)

	cExact := ParityCase{Name: "x", Compare: CompareExact}
	stExact := &stubFailer{}
	compareResponses(stExact, cExact,
		response{Status: 200, Body: ktor},
		response{Status: 200, Body: goapi},
	)
	if !stExact.Failed() {
		t.Fatalf("CompareExact: expected failure on byte diff, got none")
	}
	if !containsAny(stExact.errors, "body bytes differ") {
		t.Fatalf("CompareExact: expected 'body bytes differ' message, got %v", stExact.errors)
	}

	cUnord := ParityCase{Name: "x", Compare: CompareJSONUnordered}
	stUnord := &stubFailer{}
	compareResponses(stUnord, cUnord,
		response{Status: 200, Body: ktor},
		response{Status: 200, Body: goapi},
	)
	if stUnord.Failed() {
		t.Fatalf("CompareJSONUnordered: expected no failure on key reorder, got %v", stUnord.errors)
	}
}

func TestCompareResponses_BodyJSONStructuralDiff_AllJSONFail(t *testing.T) {
	// Same shape, different value. Both JSON modes must fail; exact
	// also fails (different bytes), but is irrelevant here.
	ktor := []byte(`{"a":1,"b":2}`)
	goapi := []byte(`{"a":1,"b":3}`)
	for _, mode := range []ComparisonMode{CompareJSON, CompareJSONUnordered} {
		c := ParityCase{Name: "x", Compare: mode}
		st := &stubFailer{}
		compareResponses(st, c,
			response{Status: 200, Body: ktor},
			response{Status: 200, Body: goapi},
		)
		if !st.Failed() {
			t.Errorf("mode %s: expected JSON-structural failure, got none", mode)
		}
	}
}

func TestCompareResponses_HeaderInAllowlistMissing_Fails(t *testing.T) {
	body := []byte(`{}`)
	c := ParityCase{Name: "x", Compare: CompareJSONUnordered, HeaderAllowList: []string{"Content-Type"}}
	st := &stubFailer{}
	compareResponses(st, c,
		response{Status: 200, Body: body, Headers: http.Header{"Content-Type": []string{"application/json"}}},
		response{Status: 200, Body: body, Headers: http.Header{}},
	)
	if !st.Failed() {
		t.Fatal("expected failure when allowlisted header missing on one side")
	}
	if !containsAny(st.errors, `header "Content-Type" differs`) {
		t.Fatalf("expected 'header \"Content-Type\" differs' message, got %v", st.errors)
	}
}

func TestCompareResponses_HeaderNotInAllowlistMissing_Passes(t *testing.T) {
	body := []byte(`{}`)
	c := ParityCase{Name: "x", Compare: CompareJSONUnordered, HeaderAllowList: nil}
	st := &stubFailer{}
	// Different X-Trace-Id but no allowlist → must pass.
	compareResponses(st, c,
		response{Status: 200, Body: body, Headers: http.Header{"X-Trace-Id": []string{"abc"}}},
		response{Status: 200, Body: body, Headers: http.Header{"X-Trace-Id": []string{"xyz"}}},
	)
	if st.Failed() {
		t.Fatalf("expected no failure for non-allowlisted header diff, got %v", st.errors)
	}
}

func TestCanonicaliseJSON_NestedReorders(t *testing.T) {
	a := []byte(`{"b": 1, "a": [{"d": 2, "c": 3}]}`)
	b := []byte(`{"a": [{"c": 3, "d": 2}], "b": 1}`)
	ca, err := canonicaliseJSON(a)
	if err != nil {
		t.Fatalf("canonicalise a: %v", err)
	}
	cb, err := canonicaliseJSON(b)
	if err != nil {
		t.Fatalf("canonicalise b: %v", err)
	}
	if !bytes.Equal(ca, cb) {
		t.Fatalf("expected canonicalised forms to match:\na=%s\nb=%s", ca, cb)
	}
}

func TestLoadCases_ValidFixtures(t *testing.T) {
	cases := loadCases(t)
	if len(cases) == 0 {
		t.Fatal("no fixtures loaded — starter set should ship at least 6")
	}
	if len(cases) < 6 {
		t.Fatalf("starter set should be >= 6 fixtures, got %d", len(cases))
	}
	seen := map[string]bool{}
	for _, c := range cases {
		if c.Name == "" {
			t.Errorf("fixture with empty name: %+v", c)
		}
		if seen[c.Name] {
			t.Errorf("duplicate fixture name %q", c.Name)
		}
		seen[c.Name] = true
		if c.Method == "" {
			t.Errorf("fixture %q: empty method", c.Name)
		}
		if c.Path == "" {
			t.Errorf("fixture %q: empty path", c.Name)
		}
	}
}

func TestLoadCases_AllPathsAreConcrete(t *testing.T) {
	// The parity layer compares wire bytes — there is no template
	// substitution. Any fixture path containing an OpenAPI-style {param}
	// placeholder is a fixture authoring bug.
	cases := loadCases(t)
	for _, c := range cases {
		if strings.Contains(c.Path, "{") || strings.Contains(c.Path, "}") {
			t.Errorf("fixture %q: path %q contains template placeholder; must be concrete", c.Name, c.Path)
		}
	}
}

// ---- helpers --------------------------------------------------------

// containsAny returns true iff any element of msgs contains needle.
func containsAny(msgs []string, needle string) bool {
	for _, m := range msgs {
		if strings.Contains(m, needle) {
			return true
		}
	}
	return false
}
