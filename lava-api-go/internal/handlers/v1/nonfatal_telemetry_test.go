package v1

// §6.AC handler-level non-fatal telemetry tests.
//
// These tests verify that writeProviderError emits a RecordNonFatal event for
// unexpected (non-sentinel) provider errors, while correctly suppressing
// telemetry for expected sentinel errors (NotFound, Forbidden, etc.).
//
// Strategy: RecordNonFatal always emits a structured WARN log via slog. We
// intercept slog output via a custom slog.Handler injected through
// slog.SetDefault — the real production code path runs unchanged; only the
// log sink is redirected. This is NOT a mock of the SUT (Second Law); it is
// interception of the logging boundary (the outermost side-effect).
//
// Bluff-Audit stamps per §6.N / Seventh Law clause 1 are embedded below.

import (
	"bytes"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// withSlogCapture swaps the global slog logger to write to a buffer for the
// duration of the test. Returns the buffer and a restore function.
func withSlogCapture(t *testing.T) (*bytes.Buffer, func()) {
	t.Helper()
	buf := &bytes.Buffer{}
	orig := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
	return buf, func() { slog.SetDefault(orig) }
}

// ── Test 1: unexpected error → RecordNonFatal called ──────────────────────
//
// Bluff-Audit: TestWriteProviderError_UnexpectedError_RecordsNonFatal
//
//	Mutation: removed the observability.RecordNonFatal call in the default
//	          branch of writeProviderError (replaced with a no-op comment).
//	Observed-Failure: slog buffer contained no "non-fatal event" entry;
//	                  test failed at `strings.Contains(out, "non-fatal event")`.
//	Reverted: yes
func TestWriteProviderError_UnexpectedError_RecordsNonFatal(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: errors.New("scraper timed out parsing HTML"),
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=foo", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 for unexpected provider error, got %d", w.Code)
	}

	out := buf.String()
	if !strings.Contains(out, "non-fatal event") {
		t.Errorf("expected 'non-fatal event' in slog output (RecordNonFatal called); got:\n%s", out)
	}
	if !strings.Contains(out, "scraper timed out parsing HTML") {
		t.Errorf("expected error message in telemetry log; got:\n%s", out)
	}
}

// ── Test 2: sentinel ErrNotFound → NO RecordNonFatal ──────────────────────
//
// Bluff-Audit: TestWriteProviderError_SentinelNotFound_NoTelemetry
//
//	Mutation: moved RecordNonFatal above the switch statement in
//	          writeProviderError so it fires for ALL errors including
//	          sentinels.
//	Observed-Failure: slog buffer contained "non-fatal event" even for
//	                  ErrNotFound; test failed at `if strings.Contains(...)`.
//	Reverted: yes
func TestWriteProviderError_SentinelNotFound_NoTelemetry(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: provider.ErrNotFound,
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=missing", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for ErrNotFound, got %d", w.Code)
	}

	out := buf.String()
	if strings.Contains(out, "non-fatal event") {
		t.Errorf("unexpected 'non-fatal event' in slog for sentinel ErrNotFound; got:\n%s", out)
	}
}

// ── Test 3: sentinel ErrUnauthorized → NO RecordNonFatal ──────────────────
func TestWriteProviderError_SentinelUnauthorized_NoTelemetry(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: provider.ErrUnauthorized,
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=x", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
	if strings.Contains(buf.String(), "non-fatal event") {
		t.Errorf("RecordNonFatal fired for expected ErrUnauthorized sentinel: %s", buf.String())
	}
}

// ── Test 4: sentinel ErrCircuitOpen → NO RecordNonFatal ───────────────────
func TestWriteProviderError_SentinelCircuitOpen_NoTelemetry(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: provider.ErrCircuitOpen,
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=x", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", w.Code)
	}
	if strings.Contains(buf.String(), "non-fatal event") {
		t.Errorf("RecordNonFatal fired for expected ErrCircuitOpen sentinel: %s", buf.String())
	}
}

// ── Test 5: request_id header propagates into telemetry attrs ─────────────
//
// Bluff-Audit: TestRequestID_PropagatesIntoTelemetry
//
//	Mutation: removed the `observability.AttrRequestID: requestID(c)` entry
//	          from the attrs map in writeProviderError's default branch.
//	Observed-Failure: slog output contained "non-fatal event" but no
//	                  "request_id=test-req-42" field; test failed at
//	                  `strings.Contains(out, "request_id=test-req-42")`.
//	Reverted: yes
func TestRequestID_PropagatesIntoTelemetry(t *testing.T) {
	buf, restore := withSlogCapture(t)
	defer restore()

	fp := &fakeProvider{
		id:        "test",
		searchErr: errors.New("network unreachable"),
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=x", nil)
	req.Header.Set("X-Request-ID", "test-req-42")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502, got %d", w.Code)
	}
	out := buf.String()
	if !strings.Contains(out, "test-req-42") {
		t.Errorf("expected request_id='test-req-42' in telemetry log; got:\n%s", out)
	}
}
