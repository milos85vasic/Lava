package observability

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"
)

// withTestLogger swaps the package-level slog logger for a test buffer
// and returns a function to restore the original. Captured output is
// returned via the buffer.
func withTestLogger(t *testing.T) (*bytes.Buffer, func()) {
	t.Helper()
	buf := &bytes.Buffer{}
	original := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
	return buf, func() { slog.SetDefault(original) }
}

func TestRecordNonFatal_NilError_NoOp(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordNonFatal(context.Background(), nil, NonFatalAttributes{"feature": "test"})
	if buf.Len() != 0 {
		t.Errorf("expected no log output for nil error, got: %s", buf.String())
	}
}

// §6.AC closure: cancellation contexts are filtered as benign teardown,
// symmetric with Android's FirebaseAnalyticsTracker.recordNonFatal
// CancellationException filter (Crashlytics 7df61fdba64f9928b067624d6db395ca).
func TestRecordNonFatal_FiltersContextCanceled(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordNonFatal(context.Background(), context.Canceled, NonFatalAttributes{
		AttrFeature:   "test",
		AttrOperation: "noop",
	})
	if strings.Contains(buf.String(), "WARN") {
		t.Errorf("expected DEBUG-level skip log (no WARN), got: %s", buf.String())
	}
	if !strings.Contains(buf.String(), "RecordNonFatal skipped: cancellation") {
		t.Errorf("expected skip-cancellation log entry, got: %s", buf.String())
	}
}

func TestRecordNonFatal_FiltersDeadlineExceeded(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordNonFatal(context.Background(), context.DeadlineExceeded, NonFatalAttributes{})
	if strings.Contains(buf.String(), "non-fatal event") {
		t.Errorf("expected cancellation filter, got non-fatal log: %s", buf.String())
	}
}

func TestRecordNonFatal_RealError_LogsWarn(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordNonFatal(context.Background(), errors.New("real failure"), NonFatalAttributes{
		AttrFeature:   "rutracker",
		AttrOperation: "search",
	})
	out := buf.String()
	if !strings.Contains(out, "non-fatal event") {
		t.Errorf("expected 'non-fatal event' log, got: %s", out)
	}
	if !strings.Contains(out, "real failure") {
		t.Errorf("expected error message in log, got: %s", out)
	}
	if !strings.Contains(out, "feature=rutracker") {
		t.Errorf("expected feature attr, got: %s", out)
	}
}

// §6.H + §6.AC.4: attribute names matching credential-class patterns
// MUST be redacted in the output.
func TestRecordNonFatal_RedactsSensitiveAttributes(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordNonFatal(context.Background(), errors.New("auth flow failed"), NonFatalAttributes{
		AttrFeature:    "auth",
		"password":     "supersecret",
		"api_key":      "abc123",
		"X-Auth-Token": "Bearer ey...",
	})
	out := buf.String()
	if strings.Contains(out, "supersecret") {
		t.Errorf("password value LEAKED in output: %s", out)
	}
	if strings.Contains(out, "abc123") {
		t.Errorf("api_key value LEAKED in output: %s", out)
	}
	if strings.Contains(out, "Bearer ey") {
		t.Errorf("auth token LEAKED in output: %s", out)
	}
	if !strings.Contains(out, "<redacted>") {
		t.Errorf("expected <redacted> placeholder for sensitive attrs, got: %s", out)
	}
}

func TestRecordNonFatal_TruncatesLongMessages(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	long := strings.Repeat("x", 2000)
	RecordNonFatal(context.Background(), errors.New(long), NonFatalAttributes{})
	out := buf.String()
	// truncate produces "...x...(maxValueChars-3 chars)..." so total length
	// after the error= attribute should not exceed ~1100 chars including framing.
	// Look for the trailing "..." marker and that no full 2000-char run survived.
	if strings.Contains(out, strings.Repeat("x", 1100)) {
		t.Errorf("expected truncation to <=1024 chars; output contains 1100 consecutive 'x's: len=%d", len(out))
	}
	if !strings.Contains(out, "...") {
		t.Errorf("expected truncation marker '...', got: %s", out[:200])
	}
}

func TestRecordWarning_LogsWithMessage(t *testing.T) {
	buf, restore := withTestLogger(t)
	defer restore()
	RecordWarning(context.Background(), "cache miss fallback", NonFatalAttributes{
		AttrFeature:   "cache",
		AttrOperation: "get_topic",
	})
	out := buf.String()
	if !strings.Contains(out, "warning event") {
		t.Errorf("expected 'warning event' log, got: %s", out)
	}
	if !strings.Contains(out, "cache miss fallback") {
		t.Errorf("expected message in log, got: %s", out)
	}
}
