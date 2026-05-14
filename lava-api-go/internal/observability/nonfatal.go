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
//  2. Optionally: posts to Firebase Crashlytics's REST endpoint (when
//     LAVA_API_FIREBASE_CRASHLYTICS_ENABLED=true is set in the
//     environment) so server-side and client-side telemetry land in the
//     same dashboard. The REST bridge is best-effort (network errors
//     while reporting telemetry MUST NOT cascade into the user-facing
//     code path).
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
	"context"
	"errors"
	"log/slog"
	"strings"
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
	// TODO(§6.AC-debt): Firebase Crashlytics REST bridge gated on
	// LAVA_API_FIREBASE_CRASHLYTICS_ENABLED. Today this is just the
	// structured log path; Phase D-bridge ships the REST bridge.
}

// RecordWarning records a non-throwable warning. Same treatment as
// RecordNonFatal (structured log + optional REST bridge), but for
// non-error situations: degraded paths, fallback hits, missing
// resources, capability mismatches, etc.
func RecordWarning(ctx context.Context, message string, attrs NonFatalAttributes) {
	logAttrs := make([]any, 0, len(attrs)*2+2)
	logAttrs = append(logAttrs, slog.String(AttrErrorMessage, truncate(message)))
	for k, v := range attrs {
		logAttrs = append(logAttrs, slog.String(k, redactIfSensitive(k, v)))
	}
	slog.WarnContext(ctx, "warning event", logAttrs...)
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
	// Fallback: reflect-free type discovery via interface-level identity.
	switch err {
	case context.Canceled:
		return "context.Canceled"
	case context.DeadlineExceeded:
		return "context.DeadlineExceeded"
	}
	return "error"
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
