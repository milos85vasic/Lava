package middleware

import (
	"errors"
	"testing"

	"github.com/gin-gonic/gin"
)

// firstError is the telemetry path's selector for which error to attach to
// a 5xx non-fatal record: it returns the first concrete error in
// gin.Errors, skipping nil entries, or nil when none exist. A regression
// that returned the LAST error, or that surfaced a nil-wrapper as a
// non-nil error, would mis-label the Firebase non-fatal — invisible
// without a test asserting on the returned error identity.

func TestFirstError_ReturnsFirstConcrete(t *testing.T) {
	first := errors.New("boom-1")
	second := errors.New("boom-2")
	errs := []*gin.Error{
		{Err: first, Type: gin.ErrorTypePrivate},
		{Err: second, Type: gin.ErrorTypePrivate},
	}
	if got := firstError(errs); got != first {
		t.Errorf("firstError: got %v, want first %v", got, first)
	}
}

func TestFirstError_SkipsNilEntriesAndNilErr(t *testing.T) {
	real := errors.New("real")
	// A nil *gin.Error and a *gin.Error whose .Err is nil must both be
	// skipped; the first concrete error after them is the result.
	errs := []*gin.Error{
		nil,
		{Err: nil, Type: gin.ErrorTypePrivate},
		{Err: real, Type: gin.ErrorTypePrivate},
	}
	if got := firstError(errs); got != real {
		t.Errorf("firstError: got %v, want %v after skipping nils", got, real)
	}
}

func TestFirstError_EmptyReturnsNil(t *testing.T) {
	if got := firstError(nil); got != nil {
		t.Errorf("firstError(nil) = %v, want nil", got)
	}
	if got := firstError([]*gin.Error{}); got != nil {
		t.Errorf("firstError(empty) = %v, want nil", got)
	}
}
