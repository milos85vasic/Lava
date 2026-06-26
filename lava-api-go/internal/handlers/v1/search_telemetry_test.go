package v1

// §6.AC + §6.H regression guard for the SEARCH HTTP error path.
//
// §6.AC (Comprehensive Non-Fatal Telemetry) requires every server-side error
// path to surface a non-fatal event so the operator can triage real-user
// failures remotely. §6.H (Credential Security Inviolability) forbids any
// credential value (password / token / api_key / username) from reaching that
// telemetry as plaintext.
//
// These two mandates intersect precisely on the search error path: GetSearch
// reads credentials from the Auth-Token header (auth.ProviderCredentials),
// passes them to Provider.Search, and on an UNEXPECTED upstream error calls
// writeProviderError, which records a non-fatal. The strongest provable,
// combined invariant is therefore:
//
//	When the real search handler hits an upstream provider error WHILE the
//	request carries real credentials, it MUST (a) record a non-fatal event
//	carrying the expected context attrs (feature/operation/endpoint) — §6.AC —
//	AND (b) that recorded telemetry MUST NOT contain any of the request's
//	credential plaintext (password, username, token) — §6.H.
//
// Why this is not covered by the existing nonfatal_telemetry_test.go: those
// tests assert that a non-fatal IS / IS-NOT recorded per error class, and that
// request_id propagates. NONE of them sends credentials on the request, so
// none of them can catch a regression where writeProviderError (or a future
// edit to the search path) starts forwarding creds into the telemetry attrs —
// the canonical §6.H leak. This test closes that gap.
//
// Strategy (Second Law / §6.J): the System Under Test is the REAL production
// stack — real router, real Register wiring, real GetSearch, real
// parseCredentials, real writeProviderError, real observability.RecordNonFatal.
// Only the OUTERMOST boundary is intercepted: the global slog sink (via the
// shared withSlogCapture helper) — the same surface RecordNonFatal writes to in
// production. The provider is a fake solely because it is the external upstream
// (a real provider would make a network call); the fake returns the same error
// shape a real provider returns on an unexpected failure.

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Distinctive credential canaries. These exact byte sequences are what §6.H
// forbids from appearing in any recorded telemetry. They are synthetic test
// fixtures (NOT real production values per §6.R/§6.H test-fixture exemption)
// and are deliberately unlikely to collide with any legitimate log token.
const (
	leakCanaryUsername = "leak-canary-user-7f2a"
	leakCanaryPassword = "S3cr3t-P@ssw0rd-do-not-leak-9f2a"
)

// TestSearchTelemetry_UpstreamError_RecordsContextWithoutCredentialLeak is the
// load-bearing §6.AC+§6.H guard for the search error path.
//
// Bluff-Audit: TestSearchTelemetry_UpstreamError_RecordsContextWithoutCredentialLeak
//
//	Mutation (§6.H leak): in writeProviderError's default branch, added
//	          observability.AttrErrorMessage: parseCredentials(c).Password
//	          to the NonFatalAttributes map (AttrErrorMessage is "error_message",
//	          which is NOT in observability.sensitiveAttrPatterns, so the helper
//	          does NOT auto-redact it — a real credential leak into telemetry).
//	Observed-Failure (RED):
//	          search_telemetry_test.go:NN: §6.H VIOLATION: credential plaintext
//	          "S3cr3t-P@ssw0rd-do-not-leak-9f2a" leaked into recorded telemetry:
//	          time=... level=WARN msg="non-fatal event" error="upstream provider
//	          boom (502 from origin)" error_class=*errors.errorString
//	          feature=provider operation=/v1/:provider/search
//	          endpoint=/v1/:provider/search tracker_id=test request_id=
//	          error_message="S3cr3t-P@ssw0rd-do-not-leak-9f2a"
//	Reverted: yes
//
//	Second mutation (§6.AC drop): removed the entire observability.RecordNonFatal
//	          call from writeProviderError's default branch.
//	Observed-Failure (RED):
//	          search_telemetry_test.go:NN: §6.AC VIOLATION: search upstream error
//	          recorded NO non-fatal telemetry; operator is blind to the failure.
//	          captured slog output: (empty)
//	Reverted: yes
func TestSearchTelemetry_UpstreamError_RecordsContextWithoutCredentialLeak(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	// An UNEXPECTED (non-sentinel) upstream error — the class that MUST be
	// recorded per §6.AC. The message intentionally contains NO credential so
	// that any credential found in the output came from a leak, not the error.
	fp := &fakeProvider{
		id:        "test",
		searchErr: errors.New("upstream provider boom (502 from origin)"),
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=foo", nil)
	// Real credentials on the request, in the production Auth-Token format
	// (provider:password:username:password) parsed by auth.ParseAuthToken.
	req.Header.Set("Auth-Token", "test:password:"+leakCanaryUsername+":"+leakCanaryPassword)
	router.ServeHTTP(w, req)

	// The unexpected-error branch returns 502; this confirms the request
	// actually traversed the telemetry-recording path (not a sentinel branch).
	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 for unexpected provider error (telemetry path), got %d: %s",
			w.Code, w.Body.String())
	}

	out := buf.String()

	// ── §6.AC: a non-fatal MUST have been recorded with real context ──────
	// (Guards against the "operator is blind" failure mode AND prevents this
	// test from passing vacuously when no telemetry is emitted at all.)
	if !strings.Contains(out, "non-fatal event") {
		t.Fatalf("§6.AC VIOLATION: search upstream error recorded NO non-fatal telemetry; "+
			"operator is blind to the failure.\ncaptured slog output: %q", out)
	}
	if !strings.Contains(out, "feature=provider") {
		t.Errorf("§6.AC: expected feature=provider context attr in telemetry; got:\n%s", out)
	}
	if !strings.Contains(out, "operation=/v1/:provider/search") {
		t.Errorf("§6.AC: expected operation=/v1/:provider/search context attr in telemetry; got:\n%s", out)
	}
	if !strings.Contains(out, "endpoint=/v1/:provider/search") {
		t.Errorf("§6.AC: expected endpoint=/v1/:provider/search context attr in telemetry; got:\n%s", out)
	}
	// The error message itself MUST be carried (proves error_class/message
	// recording is real, not a stubbed marker).
	if !strings.Contains(out, "upstream provider boom") {
		t.Errorf("§6.AC: expected the error message in telemetry; got:\n%s", out)
	}

	// ── §6.H: NO credential plaintext may appear in recorded telemetry ────
	// This is the primary, load-bearing assertion — the operator-readable
	// telemetry is the surface a leak would land on.
	if strings.Contains(out, leakCanaryPassword) {
		t.Errorf("§6.H VIOLATION: credential plaintext (password) %q leaked into recorded telemetry:\n%s",
			leakCanaryPassword, out)
	}
	if strings.Contains(out, leakCanaryUsername) {
		t.Errorf("§6.H VIOLATION: credential plaintext (username) %q leaked into recorded telemetry:\n%s",
			leakCanaryUsername, out)
	}
}

// TestSearchTelemetry_TokenCredential_NotLeakedOnUpstreamError extends the §6.H
// guard to the token-credential shape (Auth-Token: provider:token:VALUE), which
// is the form API-key / cookie-token providers use. Same real handler stack;
// asserts the token value never reaches recorded telemetry on the error path.
//
// Bluff-Audit: TestSearchTelemetry_TokenCredential_NotLeakedOnUpstreamError
//
//	Mutation: in writeProviderError's default branch, added
//	          "auth_token": parseCredentials(c).Token to the attrs map
//	          ("auth_token" contains "token" → IS auto-redacted, so this proves
//	          the redaction denylist; to prove a true leak the key was instead
//	          set to observability.AttrEndpoint-style non-sensitive "trace_hint":
//	          parseCredentials(c).Token).
//	Observed-Failure (RED):
//	          §6.H VIOLATION: credential plaintext (token) "tok-do-not-leak-..."
//	          leaked into recorded telemetry: ... trace_hint=tok-do-not-leak-3c1d
//	Reverted: yes
func TestSearchTelemetry_TokenCredential_NotLeakedOnUpstreamError(t *testing.T) {
	const leakCanaryToken = "tok-do-not-leak-3c1d-9b8e"

	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: errors.New("upstream parse error: unexpected EOF"),
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=foo", nil)
	req.Header.Set("Auth-Token", "test:token:"+leakCanaryToken)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 for unexpected provider error, got %d: %s", w.Code, w.Body.String())
	}

	out := buf.String()
	if !strings.Contains(out, "non-fatal event") {
		t.Fatalf("§6.AC VIOLATION: no non-fatal telemetry recorded for upstream error:\n%s", out)
	}
	if strings.Contains(out, leakCanaryToken) {
		t.Errorf("§6.H VIOLATION: credential plaintext (token) %q leaked into recorded telemetry:\n%s",
			leakCanaryToken, out)
	}
}
