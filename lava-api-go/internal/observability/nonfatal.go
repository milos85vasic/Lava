// Package observability — non-fatal telemetry helper.
//
// §6.AC Comprehensive Non-Fatal Telemetry Mandate (added 2026-05-14):
// every error path / fallback / unexpected-state branch in production
// code MUST surface to telemetry so the operator can triage real-user
// failures remotely. The Android side has `analytics.recordNonFatal()` /
// `recordWarning()`; this is the Go-side equivalent.
//
// RecordNonFatal does two things:
//
//  1. Always: emits a structured WARNING/ERROR-level log via the existing
//     OTLP pipeline (via the package-level slog logger). The structured
//     attributes are queryable in Loki + Tempo.
//
//  2. Optionally (§6.AC-debt closed): posts a JSON event to a configurable
//     webhook URL when LAVA_API_FIREBASE_CRASHLYTICS_ENABLED=true AND
//     LAVA_API_NONFATAL_WEBHOOK_URL is set. This is best-effort (a 2 s
//     timeout, errors swallowed, runs in its own goroutine) so telemetry
//     NEVER cascades into the user request path.
//
// ── HONEST NOTE ON "FIREBASE CRASHLYTICS" ────────────────────────────────
// Firebase Crashlytics has NO public server-side REST ingest API — it is a
// mobile-SDK-only product (as of 2026). There is no POST endpoint that
// accepts non-fatal events from a Go server. The original TODO(§6.AC-debt)
// described a "REST endpoint" that does not exist; fabricating such a call
// would be a §6.J bluff. Instead the bridge posts to an operator-supplied
// webhook (LAVA_API_NONFATAL_WEBHOOK_URL) — a Grafana Loki push endpoint,
// a custom Cloud Function, a Slack webhook, or any HTTP collector the
// operator configures. The server-side telemetry dashboard is Loki/Grafana
// (via the OTLP pipeline). The Crashlytics mobile dashboard shows what the
// Android client reports; server-side non-fatals reach it only when the
// operator wires a Cloud Function from the webhook into Crashlytics.
// ─────────────────────────────────────────────────────────────────────────
//
// Mandatory attributes per §6.AC.3 — feature, operation, error_class,
// error_message (truncated 1024 bytes, NEVER credentials per §6.H),
// endpoint, request_id, tracker_id where applicable. Caller passes
// these via the `attrs` map; the helper truncates message-class
// attributes to 1024 bytes each AND applies automatic redaction to
// known sensitive attribute names (password / token / secret / api_key
// / cookie / authorization).
//
// Cancellation contexts (context.Canceled, context.DeadlineExceeded)
// are filtered as benign teardown — the Go-side equivalent of the
// Android CancellationException filter in FirebaseAnalyticsTracker.
package observability

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// NonFatalAttributes is the structured context for a non-fatal event.
// Keys SHOULD use the canonical names from §6.AC.3 (feature, operation,
// error_class, error_message, endpoint, request_id, tracker_id) when
// applicable; additional domain-specific attributes are permitted.
type NonFatalAttributes map[string]string

const (
	// AttrFeature names the high-level subsystem the error came from
	// (e.g. "rutracker", "auth", "cache").
	AttrFeature = "feature"
	// AttrOperation names the user-visible action that triggered the
	// error (e.g. "login", "search", "topic_view").
	AttrOperation = "operation"
	// AttrErrorClass names the error category (a stable code OR the
	// Go error type's package-qualified name).
	AttrErrorClass = "error_class"
	// AttrErrorMessage carries the truncated error message. NEVER
	// include credentials here per §6.H.
	AttrErrorMessage = "error_message"
	// AttrEndpoint names the HTTP route that surfaced the error.
	AttrEndpoint = "endpoint"
	// AttrRequestID carries the per-request correlation ID for
	// cross-referencing with traces in Tempo.
	AttrRequestID = "request_id"
	// AttrTrackerID names the tracker subsystem (rutracker, rutor, etc).
	AttrTrackerID = "tracker_id"

	// maxValueChars matches the Android tracker's MAX_VALUE_CHARS so
	// truncation behavior is symmetric across platforms.
	maxValueChars = 1024
)

// sensitiveAttrPatterns is the case-insensitive substring set that the
// helper redacts automatically. Per §6.H credential-handling mandate,
// these MUST never reach telemetry as plaintext. Callers who pass these
// attribute names get `<redacted>` as the value regardless of input.
var sensitiveAttrPatterns = []string{
	"password",
	"token",
	"secret",
	"api_key",
	"apikey",
	"cookie",
	"authorization",
	"hmac",
	"pepper",
}

// RecordNonFatal records a non-fatal error event. Always emits a
// structured log; optionally bridges to Firebase Crashlytics via REST
// when enabled. Cancellation errors (context.Canceled,
// context.DeadlineExceeded) are filtered as benign teardown noise.
//
// Caller MUST NOT include credentials in attrs; the helper auto-redacts
// known sensitive attribute names but the call site is responsible for
// not leaking secrets in the first place per §6.H.
func RecordNonFatal(ctx context.Context, err error, attrs NonFatalAttributes) {
	if err == nil {
		return
	}
	// Filter cancellation noise — symmetric with Android's
	// FirebaseAnalyticsTracker.recordNonFatal CancellationException
	// filter (Crashlytics issue 7df61fdba64f9928b067624d6db395ca).
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		slog.DebugContext(ctx, "RecordNonFatal skipped: cancellation",
			slog.String("error_class", classOf(err)),
			slog.Any("attrs", attrs),
		)
		return
	}
	logAttrs := make([]any, 0, len(attrs)*2+4)
	logAttrs = append(logAttrs, slog.String("error", truncate(err.Error())))
	logAttrs = append(logAttrs, slog.String("error_class", classOf(err)))
	for k, v := range attrs {
		logAttrs = append(logAttrs, slog.String(k, redactIfSensitive(k, v)))
	}
	slog.WarnContext(ctx, "non-fatal event", logAttrs...)
	webhookForward(ctx, "non_fatal", classOf(err), truncate(err.Error()), attrs)
}

// RecordWarning records a non-throwable warning. Same treatment as
// RecordNonFatal (structured log + optional webhook forward), but for
// non-error situations: degraded paths, fallback hits, missing
// resources, capability mismatches, etc.
func RecordWarning(ctx context.Context, message string, attrs NonFatalAttributes) {
	logAttrs := make([]any, 0, len(attrs)*2+2)
	logAttrs = append(logAttrs, slog.String(AttrErrorMessage, truncate(message)))
	for k, v := range attrs {
		logAttrs = append(logAttrs, slog.String(k, redactIfSensitive(k, v)))
	}
	slog.WarnContext(ctx, "warning event", logAttrs...)
	webhookForward(ctx, "warning", "warning", truncate(message), attrs)
}

// ── Webhook bridge (§6.AC-debt closed) ────────────────────────────────────

// webhookOnce guards the one-time "enabled but no URL" warning so it
// appears only once per process rather than on every event.
var webhookOnce sync.Once

// webhookBody is the JSON shape posted to LAVA_API_NONFATAL_WEBHOOK_URL.
// All string values are already redacted + truncated before reaching here.
type webhookBody struct {
	Event      string            `json:"event"`       // "non_fatal" | "warning"
	ErrorClass string            `json:"error_class"` // stable type name
	Message    string            `json:"error_message"`
	Attrs      map[string]string `json:"attrs"`
	Timestamp  string            `json:"ts"` // RFC 3339
}

// webhookForward posts a best-effort JSON event to the operator-configured
// webhook URL. It:
//   - no-ops when LAVA_API_FIREBASE_CRASHLYTICS_ENABLED != "true"
//   - emits a one-time WARN and no-ops when the flag is set but
//     LAVA_API_NONFATAL_WEBHOOK_URL is empty (§6.R: URL from env, never
//     hardcoded)
//   - runs in its own goroutine (fire-and-forget)
//   - applies a 2 s timeout independent of the caller's context
//   - swallows all errors + recovers from panics so telemetry NEVER
//     cascades into the user-facing code path
//   - applies the same §6.H credential redaction to the attrs before
//     encoding the body
func webhookForward(ctx context.Context, event, errorClass, message string, attrs NonFatalAttributes) {
	if os.Getenv("LAVA_API_FIREBASE_CRASHLYTICS_ENABLED") != "true" {
		return
	}
	url := os.Getenv("LAVA_API_NONFATAL_WEBHOOK_URL")
	if url == "" {
		webhookOnce.Do(func() {
			slog.WarnContext(ctx, "observability: LAVA_API_FIREBASE_CRASHLYTICS_ENABLED=true but LAVA_API_NONFATAL_WEBHOOK_URL is not set; non-fatal webhook forwarding disabled")
		})
		return
	}

	// Build redacted attrs copy — never forward plaintext credentials.
	safe := make(map[string]string, len(attrs))
	for k, v := range attrs {
		safe[k] = redactIfSensitive(k, v)
	}

	body := webhookBody{
		Event:      event,
		ErrorClass: errorClass,
		Message:    message,
		Attrs:      safe,
		Timestamp:  time.Now().UTC().Format(time.RFC3339),
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		// Marshal of plain strings should never fail; log and abandon.
		slog.WarnContext(ctx, "observability: webhookForward marshal failed", slog.String("err", err.Error()))
		return
	}

	targetURL := url // capture for goroutine
	payload := encoded
	go func() {
		defer func() { recover() }() //nolint:errcheck // swallow panics per §6.AC.2
		reqCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, targetURL, bytes.NewReader(payload))
		if err != nil {
			// no-telemetry: best-effort webhook forward MUST NOT cascade or recurse into RecordNonFatal.
			return
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			// no-telemetry: best-effort webhook forward MUST NOT cascade or recurse into RecordNonFatal.
			return
		}
		_ = resp.Body.Close()
	}()
}

// classOf returns a stable name for the error's type. For errors that
// implement an Unwrap() chain, walks the chain looking for a sentinel
// or named type.
func classOf(err error) string {
	if err == nil {
		return ""
	}
	t := err
	depth := 0
	for t != nil && depth < 32 {
		// Skip wrapper types whose name is just "*errors.errorString" —
		// fmt.Errorf produces this, which is opaque. Walk one more.
		next := errors.Unwrap(t)
		if next == nil {
			break
		}
		t = next
		depth++
	}
	return underlyingTypeName(t)
}

// underlyingTypeName uses %T-style formatting via reflection to extract
// a stable type name. For Go's errors.errorString this returns
// "*errors.errorString" — useful for distinguishing fmt.Errorf-wrapped
// errors from typed errors.
func underlyingTypeName(err error) string {
	if err == nil {
		return ""
	}
	type namer interface{ Name() string }
	if n, ok := err.(namer); ok {
		return n.Name()
	}
	// LVA-021: real lava-api-go errors are fmt.Errorf-wrapped stdlib typed
	// errors (*fs.PathError, *net.OpError, *url.Error, *strconv.NumError, pgx
	// errors) that do NOT implement Name(). The prior fallback returned the
	// literal "error" for ALL of them, collapsing error_class into one bucket
	// and defeating §6.AC per-type triage in Loki/Tempo. %T yields the actual
	// distinguishing type name. The context sentinels keep their friendly names.
	switch err {
	case context.Canceled:
		return "context.Canceled"
	case context.DeadlineExceeded:
		return "context.DeadlineExceeded"
	}
	return fmt.Sprintf("%T", err)
}

// truncate enforces §6.AC.3's 1024-char message-attribute cap.
func truncate(s string) string {
	if len(s) <= maxValueChars {
		return s
	}
	return s[:maxValueChars-3] + "..."
}

// redactIfSensitive replaces values whose key matches a known
// sensitive substring with "<redacted>".
func redactIfSensitive(key, value string) string {
	lower := strings.ToLower(key)
	for _, pat := range sensitiveAttrPatterns {
		if strings.Contains(lower, pat) {
			return "<redacted>"
		}
	}
	return truncate(value)
}
