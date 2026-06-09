package observability

import (
	"context"
	"errors"
	"io/fs"
	"testing"
)

// unnamedTypedError is a typed error WITHOUT a Name() method — the shape of
// virtually every real lava-api-go error (stdlib typed errors, fmt.Errorf
// chains). The existing nonfatal_errorclass_test.go only exercised a
// Name()-implementing custom error + errors.New, so it never caught the
// collapse-to-"error" bug for this dominant case (§6.N "would a bug here be
// invisible to existing tests?").
type unnamedTypedError struct{}

func (unnamedTypedError) Error() string { return "boom" }

// LVA-021: a typed error WITHOUT Name() MUST report its actual %T type as
// error_class, NOT the literal "error" (which collapsed every real error into
// one un-triageable Loki/Tempo bucket, defeating §6.AC).
//
// Falsifiability: revert underlyingTypeName's final return to `"error"` and
// this fails with: classOf = "error", want observability.unnamedTypedError.
func TestClassOf_UnnamedTypedError_UsesActualType(t *testing.T) {
	got := classOf(unnamedTypedError{})
	if got == "error" {
		t.Fatalf("error_class collapsed to \"error\" for a real typed error; want its %%T type name")
	}
	if got != "observability.unnamedTypedError" {
		t.Fatalf("classOf = %q, want observability.unnamedTypedError", got)
	}
}

// A real stdlib typed-error chain must also not collapse to "error".
func TestClassOf_StdlibTypedError_NotCollapsed(t *testing.T) {
	err := &fs.PathError{Op: "open", Path: "/nope", Err: errors.New("nope")}
	if got := classOf(err); got == "error" {
		t.Fatalf("error_class collapsed to \"error\" for *fs.PathError chain; want a distinguishing type")
	}
}

// The preserved context sentinels keep their friendly names (regression guard).
func TestClassOf_ContextSentinels_KeepFriendlyNames(t *testing.T) {
	if got := classOf(context.Canceled); got != "context.Canceled" {
		t.Fatalf("classOf(context.Canceled) = %q, want context.Canceled", got)
	}
	if got := classOf(context.DeadlineExceeded); got != "context.DeadlineExceeded" {
		t.Fatalf("classOf(context.DeadlineExceeded) = %q, want context.DeadlineExceeded", got)
	}
}
