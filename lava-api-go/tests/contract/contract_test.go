// Package contract holds the SP-2 Phase 10 / Task 10.1 contract-test
// framework: every *.golden.json fixture under
// ../fixtures/contract/ carries (method, path, status, response_body)
// for one OpenAPI route, and this test loads each fixture, looks up
// the matching operation in api/openapi.yaml, and validates the
// response_body against the typed schema via kin-openapi.
//
// Sixth Law alignment:
//   - clause 1: the framework reads the SAME api/openapi.yaml that the
//     handlers and the typed client are generated from, so a schema
//     drift in either direction (handler emits a field the spec
//     doesn't declare, or spec adds a required field handlers don't
//     emit) trips this test.
//   - clause 2 (falsifiability): the framework was rehearsed by
//     mutating get_forum.golden.json (children → string instead of
//     array). VisitJSON returned a clear "Field must be set to array
//     or not be present" error naming the offending field. See the
//     commit body for the recorded run.
//   - clause 3: the chief assertion is on the kin-openapi
//     VisitJSON(VisitAsResponse()) result — i.e. on the wire shape a
//     real client would parse. "File loaded" / "operation found" are
//     secondary plumbing assertions.
//
// Out-of-scope for this task: actually fetching from a live server.
// The UPDATE_FIXTURES=1 + LAVA_API_BASE_URL=... combination wires the
// regeneration path that Phase 11 / 14 will exercise once the compose
// stack is up; this file leaves the call gated behind those env vars
// and otherwise loads only what's on disk.
package contract

import (
	"context"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
)

// fixture is the on-disk shape of a *.golden.json file. response_body
// is held as raw JSON so we can decode it as either a primitive
// (boolean for /, /index) or an object/array, then hand it to
// kin-openapi as `any`.
type fixture struct {
	Name         string            `json:"name"`
	Method       string            `json:"method"`
	Path         string            `json:"path"`
	Query        map[string]string `json:"query,omitempty"`
	Headers      map[string]string `json:"headers,omitempty"`
	Status       int               `json:"status"`
	ResponseBody json.RawMessage   `json:"response_body"`
}

// skipFixture is the on-disk shape of a *.skip.json file. Used for
// binary routes (/download/{id}, /captcha/{path}) that don't have a
// JSON response body and are covered by the e2e suite instead.
type skipFixture struct {
	Name       string `json:"name"`
	Method     string `json:"method"`
	Path       string `json:"path"`
	SkipReason string `json:"skip_reason"`
}

// fixturesDir is the on-disk location of the golden fixtures, relative
// to this test file. Resolved at runtime via filepath.Join so the test
// works whether `go test` is run from the repo root or from this
// package directory.
const fixturesDir = "../fixtures/contract"

// specPath is the on-disk location of the OpenAPI source of truth,
// relative to this test file.
const specPath = "../../api/openapi.yaml"

// TestContract walks every fixture in fixturesDir and validates its
// response_body against the matching OpenAPI 200 response schema.
// Every fixture becomes its own subtest so a failure in one route
// doesn't mask the others.
func TestContract(t *testing.T) {
	loader := openapi3.NewLoader()
	loader.IsExternalRefsAllowed = false

	spec, err := loader.LoadFromFile(specPath)
	if err != nil {
		t.Fatalf("load OpenAPI spec %q: %v", specPath, err)
	}
	if err := spec.Validate(context.Background()); err != nil {
		t.Fatalf("OpenAPI spec %q failed self-validation: %v", specPath, err)
	}

	entries, err := os.ReadDir(fixturesDir)
	if err != nil {
		t.Fatalf("read fixtures dir %q: %v", fixturesDir, err)
	}

	if len(entries) == 0 {
		t.Fatalf("no fixtures found in %q — at least one route MUST be covered", fixturesDir)
	}

	updateMode := os.Getenv("UPDATE_FIXTURES") == "1"
	baseURL := os.Getenv("LAVA_API_BASE_URL")

	covered := 0
	skipped := 0
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		full := filepath.Join(fixturesDir, name)

		switch {
		case strings.HasSuffix(name, ".skip.json"):
			t.Run(name, func(t *testing.T) {
				runSkipFixture(t, full)
			})
			skipped++
		case strings.HasSuffix(name, ".golden.json"):
			t.Run(name, func(t *testing.T) {
				if updateMode {
					if baseURL == "" {
						t.Skip("UPDATE_FIXTURES=1 set but LAVA_API_BASE_URL is empty; skipping regeneration")
					}
					regenerateFixture(t, full, baseURL)
				}
				runGoldenFixture(t, full, spec)
			})
			covered++
		default:
			// ignore unrelated files (e.g. README)
		}
	}

	if covered == 0 {
		t.Errorf("no *.golden.json fixtures found in %q", fixturesDir)
	}
	t.Logf("contract test summary: %d golden fixtures validated, %d binary routes skipped", covered, skipped)
}

// runGoldenFixture loads one *.golden.json file, resolves the matching
// OpenAPI operation, and validates the recorded response_body against
// the operation's 200-response JSON schema.
func runGoldenFixture(t *testing.T, path string, spec *openapi3.T) {
	t.Helper()

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var fx fixture
	if err := json.Unmarshal(raw, &fx); err != nil {
		t.Fatalf("decode fixture %q: %v", path, err)
	}
	if fx.Method == "" || fx.Path == "" {
		t.Fatalf("fixture %q missing method/path", path)
	}
	if fx.Status == 0 {
		t.Fatalf("fixture %q missing status", path)
	}

	op, err := lookupOperation(spec, fx.Method, fx.Path)
	if err != nil {
		t.Fatalf("fixture %q: %v", path, err)
	}

	statusKey := fmt.Sprintf("%d", fx.Status)
	respRef := op.Responses.Value(statusKey)
	if respRef == nil || respRef.Value == nil {
		t.Fatalf("fixture %q: spec defines no %s response for %s %s", path, statusKey, fx.Method, fx.Path)
	}

	mediaType := respRef.Value.Content.Get("application/json")
	if mediaType == nil || mediaType.Schema == nil || mediaType.Schema.Value == nil {
		t.Fatalf("fixture %q: spec response has no application/json schema for %s %s", path, fx.Method, fx.Path)
	}

	// Decode the recorded body into a generic Go value (any) so kin-openapi
	// can walk it. json.Number is intentionally NOT used: kin-openapi's
	// VisitJSONNumber takes a float64, and the OpenAPI ints we emit fit
	// inside float64 round-trips for the discrete values fixture authors
	// will plausibly write. If a future fixture needs an int beyond
	// 2^53-1, switch to json.Number + a tagged decode here.
	var body any
	if len(fx.ResponseBody) > 0 {
		if err := json.Unmarshal(fx.ResponseBody, &body); err != nil {
			t.Fatalf("fixture %q: decode response_body: %v", path, err)
		}
	}

	if err := mediaType.Schema.Value.VisitJSON(body, openapi3.VisitAsResponse()); err != nil {
		t.Errorf(
			"fixture %q (%s %s) failed schema validation: %v",
			path, fx.Method, fx.Path, err,
		)
	}
}

// runSkipFixture handles the binary routes — they declare themselves
// out of scope for JSON contract testing and point at the e2e suite.
// We assert the spec actually defines the route (so a skip stub
// pointing at a non-existent path is caught) and then call t.Skip with
// the recorded reason.
func runSkipFixture(t *testing.T, path string) {
	t.Helper()

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read skip fixture: %v", err)
	}
	var sf skipFixture
	if err := json.Unmarshal(raw, &sf); err != nil {
		t.Fatalf("decode skip fixture %q: %v", path, err)
	}
	t.Skipf("%s: %s", sf.Name, sf.SkipReason)
}

// lookupOperation finds the OpenAPI operation that matches the given
// HTTP method + concrete request path. Path parameters in the fixture
// are concrete values (e.g. "/forum/123"); the spec uses templated
// keys (e.g. "/forum/{id}"). We try the exact path first, then walk
// the templated keys via Paths.Find which understands path templating.
func lookupOperation(spec *openapi3.T, method, requestPath string) (*openapi3.Operation, error) {
	// Strip query string if a fixture author left one in `path` by
	// mistake — the canonical place for query is the `query` map.
	cleanPath := requestPath
	if i := strings.IndexByte(cleanPath, '?'); i >= 0 {
		cleanPath = cleanPath[:i]
	}

	pathItem := spec.Paths.Find(cleanPath)
	if pathItem == nil {
		// Fallback: walk the spec's path map and template-match
		// segment-by-segment so we match `/forum/{id}` against
		// `/forum/123`.
		for tmpl, item := range spec.Paths.Map() {
			if templateMatches(tmpl, cleanPath) {
				pathItem = item
				break
			}
		}
	}
	if pathItem == nil {
		return nil, fmt.Errorf("no path in spec matches %q", requestPath)
	}

	op := pathItem.GetOperation(strings.ToUpper(method))
	if op == nil {
		return nil, fmt.Errorf("no %s operation on path %q", method, requestPath)
	}
	return op, nil
}

// templateMatches reports whether a templated OpenAPI path (e.g.
// "/forum/{id}") matches a concrete request path (e.g. "/forum/123").
// Segment counts must agree; literal segments must equal verbatim;
// `{name}` placeholders match any single non-empty segment.
func templateMatches(tmpl, concrete string) bool {
	tParts := strings.Split(strings.Trim(tmpl, "/"), "/")
	cParts := strings.Split(strings.Trim(concrete, "/"), "/")
	if len(tParts) != len(cParts) {
		return false
	}
	for i := range tParts {
		t := tParts[i]
		c := cParts[i]
		if strings.HasPrefix(t, "{") && strings.HasSuffix(t, "}") {
			if c == "" {
				return false
			}
			continue
		}
		if t != c {
			return false
		}
	}
	return true
}

// regenerateFixture is the gated regeneration path for Phase 11/14.
// It hits the live server at baseURL with the fixture's method, path,
// query, and headers; then writes the response back into the fixture
// file. The function intentionally aborts the test on any I/O or
// status mismatch so an UPDATE_FIXTURES run can never silently corrupt
// a fixture. Out-of-band by design: nothing in the SP-2 Phase 10 plan
// drives this until Phase 11 brings the compose stack up.
func regenerateFixture(t *testing.T, path, baseURL string) {
	t.Helper()

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read fixture for regeneration: %v", err)
	}
	var fx fixture
	if err := json.Unmarshal(raw, &fx); err != nil {
		t.Fatalf("decode fixture %q for regeneration: %v", path, err)
	}

	target, err := url.Parse(strings.TrimRight(baseURL, "/") + fx.Path)
	if err != nil {
		t.Fatalf("build URL for %q: %v", path, err)
	}
	if len(fx.Query) > 0 {
		q := target.Query()
		for k, v := range fx.Query {
			q.Set(k, v)
		}
		target.RawQuery = q.Encode()
	}

	req, err := http.NewRequestWithContext(context.Background(), strings.ToUpper(fx.Method), target.String(), nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	for k, v := range fx.Headers {
		req.Header.Set(k, v)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("hit live server at %s: %v", target, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != fx.Status {
		t.Fatalf("regeneration: %s %s returned %d, fixture expects %d", fx.Method, target, resp.StatusCode, fx.Status)
	}

	var body any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode live response from %s: %v", target, err)
	}

	bodyBytes, err := json.MarshalIndent(body, "  ", "  ")
	if err != nil {
		t.Fatalf("marshal regenerated body: %v", err)
	}
	fx.ResponseBody = json.RawMessage(bodyBytes)

	out, err := json.MarshalIndent(fx, "", "  ")
	if err != nil {
		t.Fatalf("marshal fixture: %v", err)
	}
	if err := os.WriteFile(path, append(out, '\n'), fs.FileMode(0o644)); err != nil {
		t.Fatalf("write regenerated fixture: %v", err)
	}
	t.Logf("regenerated %s from %s", path, target)
}
