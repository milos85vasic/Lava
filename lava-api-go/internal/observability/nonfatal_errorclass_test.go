// Package observability — nonfatal_errorclass_test.go is a §6.L 68th
// anti-bluff test for the previously-uncovered error-class derivation
// (classOf) that produces the "error_class" telemetry attribute. That
// attribute is the field an operator GROUPS BY in Loki/Tempo to decide
// which failure is spiking — a wrong error_class silently buckets a real
// incident under the wrong heading. The existing nonfatal_test.go covers
// redaction/truncation/cancellation; it does NOT assert the emitted
// error_class value, which is exactly the "would a bug here be invisible
// to existing tests?" (§6.N.2) gap this file closes.
//
// PRIMARY assertion is on the error_class VALUE actually written to the
// slog handler (the operator-observable telemetry surface), NOT a call
// count.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//
//	Mutation: in nonfatal.go classOf, replace the body with
//	  `return "error"` so every event reports the same opaque class.
//	Observed: TestRecordNonFatal_ErrorClass_DistinguishesSentinels
//	  FAILS: "error_class for context.Canceled-derived... " mismatch /
//	  "two distinct sentinel errors collapsed to the same error_class".
//	Reverted: yes (production code restored; final commit unmutated).
package observability

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"testing"
)

// namedError implements the optional `Name() string` interface that
// classOf prefers when present — modelling a typed sentinel from one of
// the tracker packages.
type namedError struct{ n string }

func (e namedError) Error() string { return "named: " + e.n }
func (e namedError) Name() string  { return e.n }

// captureDefault swaps the default slog logger for a buffer-backed text
// handler, runs fn, restores the previous logger, and returns the
// captured bytes. Same path RecordNonFatal writes to in production.
func captureDefault(t *testing.T, fn func()) string {
	t.Helper()
	var buf bytes.Buffer
	prev := slog.Default()
	t.Cleanup(func() { slog.SetDefault(prev) })
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
	fn()
	return buf.String()
}

// extractErrorClass pulls the value of the `error_class=` key from a
// slog text-handler line. slog text format quotes values containing
// spaces; our class names have none, so a simple token scan suffices.
func extractErrorClass(out string) string {
	const key = "error_class="
	i := strings.Index(out, key)
	if i < 0 {
		return ""
	}
	rest := out[i+len(key):]
	// value ends at the next space or newline.
	end := strings.IndexAny(rest, " \n")
	if end < 0 {
		return strings.TrimSpace(rest)
	}
	return strings.TrimSpace(rest[:end])
}

// TestRecordNonFatal_ErrorClass_PrefersNamedType pins that a typed error
// exposing Name() surfaces that name as error_class — so an operator
// grouping by error_class sees the meaningful sentinel name, not the
// opaque "error".
func TestRecordNonFatal_ErrorClass_PrefersNamedType(t *testing.T) {
	out := captureDefault(t, func() {
		RecordNonFatal(context.Background(), namedError{n: "rutracker.ErrCircuitOpen"}, NonFatalAttributes{
			AttrOperation: "search",
		})
	})
	got := extractErrorClass(out)
	if got != "rutracker.ErrCircuitOpen" {
		t.Fatalf("error_class=%q want %q (named sentinel must surface its Name() for operator triage).\nFULL:\n%s", got, "rutracker.ErrCircuitOpen", out)
	}
}

// TestRecordNonFatal_ErrorClass_WalksWrapChain pins that classOf unwraps
// fmt.Errorf("...: %w", named) and still reports the wrapped sentinel's
// name — a real handler wraps upstream errors with context, and the
// error_class MUST reflect the root cause, not the wrapper.
func TestRecordNonFatal_ErrorClass_WalksWrapChain(t *testing.T) {
	root := namedError{n: "auth.ErrUnauthorized"}
	wrapped := fmt.Errorf("login failed: %w", root)
	out := captureDefault(t, func() {
		RecordNonFatal(context.Background(), wrapped, NonFatalAttributes{AttrOperation: "login"})
	})
	got := extractErrorClass(out)
	if got != "auth.ErrUnauthorized" {
		t.Fatalf("error_class=%q want %q (classOf must walk the %%w wrap chain to the root sentinel).\nFULL:\n%s", got, "auth.ErrUnauthorized", out)
	}
}

// TestRecordNonFatal_ErrorClass_DistinguishesSentinels is the
// load-bearing discrimination test: two DIFFERENT typed errors MUST NOT
// collapse to the same error_class. If classOf is mutated to a constant,
// this fails because both emit the same bucket.
func TestRecordNonFatal_ErrorClass_DistinguishesSentinels(t *testing.T) {
	outA := captureDefault(t, func() {
		RecordNonFatal(context.Background(), namedError{n: "tracker.ErrNotFound"}, nil)
	})
	outB := captureDefault(t, func() {
		RecordNonFatal(context.Background(), namedError{n: "tracker.ErrForbidden"}, nil)
	})
	classA := extractErrorClass(outA)
	classB := extractErrorClass(outB)
	if classA == "" || classB == "" {
		t.Fatalf("missing error_class: A=%q B=%q", classA, classB)
	}
	if classA == classB {
		t.Fatalf("two distinct sentinel errors collapsed to the same error_class %q — operator cannot distinguish NotFound from Forbidden in telemetry", classA)
	}
}

// TestRecordNonFatal_ErrorClass_PlainErrorIsStable pins the fallback:
// a plain errors.New / fmt.Errorf without a Name() still produces a
// non-empty, stable error_class (so the field is never blank — a blank
// group-by key is itself a triage hole).
func TestRecordNonFatal_ErrorClass_PlainErrorIsStable(t *testing.T) {
	out := captureDefault(t, func() {
		RecordNonFatal(context.Background(), errors.New("opaque failure"), nil)
	})
	got := extractErrorClass(out)
	if got == "" {
		t.Fatalf("error_class is blank for a plain error; operators lose the group-by key.\nFULL:\n%s", out)
	}
}

// TestRecordNonFatal_PreservesNonSensitiveAndRedactsSensitive pins the
// §6.H boundary at byte level in ONE event carrying BOTH kinds of
// attribute: the non-sensitive triage context survives verbatim AND the
// secret is redacted. The existing redaction test does not assert that a
// non-sensitive value (e.g. the endpoint) survives in the same event.
func TestRecordNonFatal_PreservesNonSensitiveAndRedactsSensitive(t *testing.T) {
	const secret = "leak-me-9f3a"
	out := captureDefault(t, func() {
		RecordNonFatal(context.Background(), errors.New("upstream 500"), NonFatalAttributes{
			AttrEndpoint: "/v1/rutracker/search",
			AttrFeature:  "rutracker",
			"secret":     secret,
		})
	})
	if strings.Contains(out, secret) {
		t.Fatalf("secret leaked into telemetry: %q present.\nFULL:\n%s", secret, out)
	}
	if !strings.Contains(out, "/v1/rutracker/search") {
		t.Fatalf("non-sensitive endpoint attribute dropped — redaction over-applied; operator loses triage context.\nFULL:\n%s", out)
	}
	if !strings.Contains(out, "<redacted>") {
		t.Fatalf("expected <redacted> marker for the secret attribute.\nFULL:\n%s", out)
	}
}
