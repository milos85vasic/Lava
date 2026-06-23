package observability

// §6.AC-debt closed: webhook bridge tests.
//
// These tests verify the TASK 1 implementation: the optional best-effort
// HTTP webhook forwarder gated on LAVA_API_FIREBASE_CRASHLYTICS_ENABLED.
//
// Bluff-Audit stamps are embedded per §6.N / Seventh Law clause 1.
//
// ── HONEST NOTE (repeated from nonfatal.go) ─────────────────────────────────
// Firebase Crashlytics has NO public server-side REST ingest API. The webhook
// target is operator-supplied (Loki push endpoint, Cloud Function, Slack, etc).
// ────────────────────────────────────────────────────────────────────────────

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// captureWebhook starts a test HTTP server that collects the first POST body
// it receives into the returned channel. The server is automatically closed
// when the test ends.
func captureWebhook(t *testing.T) (serverURL string, received <-chan []byte) {
	t.Helper()
	ch := make(chan []byte, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		select {
		case ch <- body:
		default:
		}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv.URL, ch
}

// ── Test 1: webhook posts when enabled ─────────────────────────────────────
//
// Bluff-Audit: TestWebhookBridge_PostsOnEnabled
//
//	Mutation: disabled the goroutine launch in webhookForward by replacing
//	          `go func()` with a no-op `if false { go func()`.
//	Observed-Failure: test timed out waiting on <-received: select reached
//	                  the `case <-time.After(2s)` branch and called
//	                  t.Fatal("no webhook POST received within 2 s").
//	Reverted: yes
func TestWebhookBridge_PostsOnEnabled(t *testing.T) {
	url, received := captureWebhook(t)

	t.Setenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED", "true")
	t.Setenv("LAVA_API_NONFATAL_WEBHOOK_URL", url)
	// Reset the one-time warn guard so it fires cleanly in isolation.
	webhookOnce = sync.Once{}

	RecordNonFatal(context.Background(), errors.New("probe error"), NonFatalAttributes{
		AttrFeature:   "test",
		AttrOperation: "webhook_test",
	})

	var body []byte
	select {
	case body = <-received:
	case <-time.After(2 * time.Second):
		t.Fatal("no webhook POST received within 2 s")
	}

	var parsed webhookBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("webhook body is not valid JSON: %v — body: %s", err, body)
	}
	if parsed.Event != "non_fatal" {
		t.Errorf("expected event='non_fatal', got %q", parsed.Event)
	}
	if parsed.Message != "probe error" {
		t.Errorf("expected error_message='probe error', got %q", parsed.Message)
	}
	if parsed.Timestamp == "" {
		t.Error("expected non-empty 'ts' field")
	}
	if parsed.Attrs["feature"] != "test" {
		t.Errorf("expected attrs.feature='test', got %q", parsed.Attrs["feature"])
	}
}

// ── Test 2: no POST when disabled ──────────────────────────────────────────
//
// Bluff-Audit: TestWebhookBridge_NoPostOnDisabled
//
//	Mutation: forced `os.Getenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED")`
//	          to always return "true" (bypassing the env-var check) to
//	          simulate the guard being absent.
//	Observed-Failure: test received an unexpected POST body (the webhook
//	                  was invoked), causing `t.Fatal("unexpected POST …")`.
//	Reverted: yes
func TestWebhookBridge_NoPostOnDisabled(t *testing.T) {
	url, received := captureWebhook(t)

	// Explicitly NOT setting LAVA_API_FIREBASE_CRASHLYTICS_ENABLED.
	t.Setenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED", "")
	t.Setenv("LAVA_API_NONFATAL_WEBHOOK_URL", url)
	webhookOnce = sync.Once{}

	RecordNonFatal(context.Background(), errors.New("should not forward"), NonFatalAttributes{})

	// Give the goroutine (if accidentally spawned) time to reach the server.
	select {
	case body := <-received:
		t.Fatalf("unexpected POST to webhook when disabled; body: %s", body)
	case <-time.After(300 * time.Millisecond):
		// correct: no POST
	}
}

// ── Test 3: sensitive attrs are redacted in webhook body ───────────────────
//
// Bluff-Audit: TestWebhookBridge_RedactsBody
//
//	Mutation: removed the `safe[k] = redactIfSensitive(k, v)` loop in
//	          webhookForward, replacing it with `safe[k] = v` (plaintext copy).
//	Observed-Failure: received JSON body contained "mysecrettoken" verbatim;
//	                  `strings.Contains(string(body), "mysecrettoken")` was
//	                  true, causing `t.Fatal("sensitive value LEAKED …")`.
//	Reverted: yes
func TestWebhookBridge_RedactsBody(t *testing.T) {
	url, received := captureWebhook(t)

	t.Setenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED", "true")
	t.Setenv("LAVA_API_NONFATAL_WEBHOOK_URL", url)
	webhookOnce = sync.Once{}

	RecordNonFatal(context.Background(), errors.New("auth failure"), NonFatalAttributes{
		AttrFeature: "auth",
		"token":     "mysecrettoken",
		"password":  "hunter2",
	})

	var body []byte
	select {
	case body = <-received:
	case <-time.After(2 * time.Second):
		t.Fatal("no webhook POST received within 2 s")
	}

	raw := string(body)
	if strings.Contains(raw, "mysecrettoken") {
		t.Fatalf("sensitive value LEAKED in webhook body: %s", raw)
	}
	if strings.Contains(raw, "hunter2") {
		t.Fatalf("password LEAKED in webhook body: %s", raw)
	}
	// JSON encodes '<' as '<' and '>' as '>'; the decoded value is
	// "<redacted>". Parse the JSON and check the decoded attr values directly.
	var parsed webhookBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("webhook body not valid JSON: %v — body: %s", err, raw)
	}
	for k, v := range parsed.Attrs {
		if v != "<redacted>" && (k == "token" || k == "password") {
			t.Errorf("sensitive attr %q not redacted; value=%q (raw body: %s)", k, v, raw)
		}
	}
}

// ── Test 4: server error does NOT propagate to caller ─────────────────────
//
// Bluff-Audit: TestWebhookBridge_ErrorDoesNotPropagate
//
//	Mutation: added `panic("webhook failed")` inside the goroutine after
//	          `resp, err := http.DefaultClient.Do(req)` (replacing the
//	          silent-swallow pattern with a deliberate panic).
//	Observed-Failure: the test goroutine recovered the panic (via the
//	          `defer func() { recover() }()` inside webhookForward), so
//	          the test STILL PASSED — confirming the panic-recovery guard
//	          works. The mutation was then changed to remove the
//	          `recover()` call entirely; that caused the test's goroutine
//	          to crash with `panic: webhook failed` and the test failed.
//	Reverted: yes
func TestWebhookBridge_ErrorDoesNotPropagate(t *testing.T) {
	// Point at a server that immediately returns 500.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	t.Setenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED", "true")
	t.Setenv("LAVA_API_NONFATAL_WEBHOOK_URL", srv.URL)
	webhookOnce = sync.Once{}

	// Must not panic or return an error of any kind.
	done := make(chan struct{})
	go func() {
		defer close(done)
		RecordNonFatal(context.Background(), errors.New("background error"), NonFatalAttributes{})
	}()

	select {
	case <-done:
		// correct: returned without blocking
	case <-time.After(3 * time.Second):
		t.Fatal("RecordNonFatal blocked longer than 3 s; expected fire-and-forget")
	}
}

// ── Test 5: warning events also post to webhook ────────────────────────────
//
// Bluff-Audit: TestWebhookBridge_WarningPostsEvent
//
//	Mutation: removed the `webhookForward(...)` call in RecordWarning.
//	Observed-Failure: test timed out at `case <-time.After(2s)` with
//	                  t.Fatal("no webhook POST received within 2 s").
//	Reverted: yes
func TestWebhookBridge_WarningPostsEvent(t *testing.T) {
	url, received := captureWebhook(t)

	t.Setenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED", "true")
	t.Setenv("LAVA_API_NONFATAL_WEBHOOK_URL", url)
	webhookOnce = sync.Once{}

	RecordWarning(context.Background(), "cache backend degraded", NonFatalAttributes{
		AttrFeature: "cache",
	})

	var body []byte
	select {
	case body = <-received:
	case <-time.After(2 * time.Second):
		t.Fatal("no webhook POST received within 2 s")
	}

	var parsed webhookBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("webhook body not valid JSON: %v — body: %s", err, body)
	}
	if parsed.Event != "warning" {
		t.Errorf("expected event='warning', got %q", parsed.Event)
	}
	if parsed.Message != "cache backend degraded" {
		t.Errorf("expected message='cache backend degraded', got %q", parsed.Message)
	}
}
