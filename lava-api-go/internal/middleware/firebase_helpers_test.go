package middleware

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// These tests cover the middleware helpers + branches the request-level
// telemetry tests leave uncovered: withTimeout (both the d<=0 pass-through and
// the real-deadline branch), IsClientCanceled (nil / context.Canceled /
// string-match / unrelated-error), the panic-recovery non-error branch, and the
// 5xx-without-handler-error synthetic-cause fallback. Each asserts on observable
// behavior — a real cancellation, a real deadline, a real recorded non-fatal.

// TestWithTimeout_NoDeadlinePassesThrough: d<=0 returns the parent context
// unchanged and a no-op cancel. The returned context MUST have no deadline.
//
// FALSIFIABILITY: if withTimeout always called context.WithTimeout, even d<=0
// would set a deadline and Deadline() would report ok=true, failing this assert.
func TestWithTimeout_NoDeadlinePassesThrough(t *testing.T) {
	type ctxKey string
	const k ctxKey = "marker"
	parent := context.WithValue(context.Background(), k, "v")

	ctx, cancel := withTimeout(parent, 0)
	defer cancel()

	if _, ok := ctx.Deadline(); ok {
		t.Errorf("withTimeout(parent, 0) set a deadline; want pass-through with none")
	}
	if ctx.Value(k) != "v" {
		t.Errorf("pass-through context lost the parent's value; got %v", ctx.Value(k))
	}
	// The negative-duration branch is the same pass-through path.
	ctx2, cancel2 := withTimeout(parent, -5*time.Second)
	defer cancel2()
	if _, ok := ctx2.Deadline(); ok {
		t.Errorf("withTimeout(parent, -5s) set a deadline; want pass-through")
	}
}

// TestWithTimeout_RealDeadlineCancels: a positive duration yields a context with
// a deadline that actually fires. We assert the user-visible effect: Done()
// closes and Err() reports DeadlineExceeded after the duration elapses.
//
// FALSIFIABILITY: returning the parent unchanged for d>0 would leave Done()
// never closing within the window, failing the select below.
func TestWithTimeout_RealDeadlineCancels(t *testing.T) {
	ctx, cancel := withTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	if _, ok := ctx.Deadline(); !ok {
		t.Fatalf("withTimeout(parent, 20ms) did not set a deadline")
	}
	select {
	case <-ctx.Done():
		if !errors.Is(ctx.Err(), context.DeadlineExceeded) {
			t.Errorf("ctx.Err() = %v, want DeadlineExceeded", ctx.Err())
		}
	case <-time.After(time.Second):
		t.Fatal("deadline context did not fire within 1s")
	}
}

// TestIsClientCanceled covers all four branches: nil, the wrapped
// context.Canceled sentinel, an error whose message merely contains the string
// (pgx/driver wraps sometimes lose the sentinel), and an unrelated error.
//
// FALSIFIABILITY: dropping the errors.Is(context.Canceled) branch makes the
// "wrapped sentinel" case fall through to the string check — still true here —
// so the discriminating case is the sentinel-WITHOUT-the-string, which Go's
// context.Canceled.Error() == "context canceled" makes coincide; the unrelated
// case below is the falsifying negative.
func TestIsClientCanceled(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"wrapped sentinel", fmt.Errorf("query failed: %w", context.Canceled), true},
		{"string-only match", errors.New("pq: context canceled by upstream"), true},
		{"unrelated error", errors.New("connection refused"), false},
		{"deadline is not cancel", context.DeadlineExceeded, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsClientCanceled(tc.err); got != tc.want {
				t.Errorf("IsClientCanceled(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

// TestFirebaseTelemetry_PanicNonError_RecordsAndRepanics: when a handler panics
// with a non-error value (e.g. panic("boom string") or panic(42)) and NO inner
// recovery middleware intercepts it, FirebaseTelemetry's own deferred recover
// MUST wrap the value into an error, record a non-fatal, and re-panic so an
// OUTER recovery layer can convert it to a 500. This covers the
// `err, ok := r.(error); if !ok { ... }` branch + the re-panic.
//
// The middleware re-panics, so the test recovers it itself (standing in for the
// outer recovery layer). The primary assertion is on the recorded non-fatal —
// it MUST carry the original non-error panic value.
//
// FALSIFIABILITY: if the recover handler only recorded `error`-typed panics, a
// string panic would skip RecordNonFatal and nonFatals would stay 0, failing the
// want=1 assertion. If it swallowed the panic instead of re-panicking, the
// recover() in this test would see nil, failing the re-panic assertion.
func TestFirebaseTelemetry_PanicNonError_RecordsAndRepanics(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	// NO recovery middleware between FirebaseTelemetry and the handler, so the
	// panic unwinds through FirebaseTelemetry's own deferred recover.
	r.Use(FirebaseTelemetry(client))
	r.GET("/panic", func(_ *gin.Context) {
		panic("a non-error panic value")
	})

	req := httptest.NewRequest(http.MethodGet, "/panic", nil)
	w := httptest.NewRecorder()

	repanicked := func() (got any) {
		defer func() { got = recover() }()
		r.ServeHTTP(w, req)
		return nil
	}()

	if repanicked == nil {
		t.Fatal("FirebaseTelemetry must re-panic after recording; outer layer saw no panic")
	}
	if got := client.nonFatals.Load(); got != 1 {
		t.Fatalf("non-error panic must record exactly 1 non-fatal; got %d", got)
	}
	if v := client.lastErrMsg.Load(); v == nil ||
		!strings.Contains(v.(string), "a non-error panic value") {
		t.Errorf("recorded non-fatal lost the panic value; got %v", v)
	}
}

// TestFirebaseTelemetry_PanicPath_HttpUrlAttrIsPathOnly: defense-in-depth §6.H
// hardening — the "http.url" telemetry attribute passed to RecordNonFatal on the
// panic path MUST contain only the URL path, never the query string.
//
// Architecture note: today auth credentials arrive via HTTP headers only, so no
// live credential can reach "http.url". This test locks down the attr value so
// that property can never silently regress if a future caller passes a credential
// in a query param.
//
// FALSIFIABILITY (Bluff-Audit):
//
//	Mutation:  changed `c.Request.URL.Path` back to `c.Request.URL.RequestURI()`
//	           in the panic-recovery block of firebase.go (line ~58).
//	Observed:  FAIL — `http.url` attr = "/secret?q=secret-looking-value"; want
//	           path only, got query string "q=secret-looking-value"
//	Reverted:  yes — production code restored to `c.Request.URL.Path` before commit
func TestFirebaseTelemetry_PanicPath_HttpUrlAttrIsPathOnly(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	// No inner recovery middleware so the panic unwinds into FirebaseTelemetry's
	// own deferred recover, which is exactly the path that records "http.url".
	r.Use(FirebaseTelemetry(client))
	r.GET("/secret", func(_ *gin.Context) {
		panic(errors.New("deliberate panic"))
	})

	// The request URL carries a query param that looks like a credential.
	req := httptest.NewRequest(http.MethodGet, "/secret?q=secret-looking-value", nil)
	w := httptest.NewRecorder()

	// Absorb the re-panic (standing in for the outer recovery layer).
	func() {
		defer func() { recover() }() //nolint:errcheck // intentional absorb
		r.ServeHTTP(w, req)
	}()

	// Primary assertion: exactly one non-fatal was recorded.
	if got := client.nonFatals.Load(); got != 1 {
		t.Fatalf("panic path must record 1 non-fatal; got %d", got)
	}

	// Primary assertion on user-visible state: the "http.url" attr MUST NOT
	// contain the query string.
	raw := client.lastAttrs.Load()
	if raw == nil {
		t.Fatal("RecordNonFatal was not called with an attrs map")
	}
	attrs := raw.(map[string]string)

	urlAttr, ok := attrs["http.url"]
	if !ok {
		t.Fatal(`telemetry attrs missing "http.url" key`)
	}
	if strings.Contains(urlAttr, "secret-looking-value") {
		t.Errorf(`"http.url" attr leaks query string: got %q; want path-only ("/secret")`, urlAttr)
	}
	if urlAttr != "/secret" {
		t.Errorf(`"http.url" attr = %q; want "/secret"`, urlAttr)
	}
}

// TestFirebaseTelemetry_5xxWithoutHandlerError_SynthesizesCause: a handler that
// returns 500 WITHOUT calling c.Error() leaves gin.Errors empty. The middleware
// MUST synthesize a cause ("http 500 on GET /path") so the recorded non-fatal is
// never a nil error. This covers the `cause == nil` fallback branch.
//
// FALSIFIABILITY: removing the synthetic-cause fallback would pass a nil error
// to RecordNonFatal; the recordingClient stores lastErrMsg only when err != nil,
// so lastErrMsg would stay nil, failing the assertion below.
func TestFirebaseTelemetry_5xxWithoutHandlerError_SynthesizesCause(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	r.Use(FirebaseTelemetry(client))
	r.GET("/silent500", func(c *gin.Context) {
		c.Status(http.StatusInternalServerError) // no c.Error()
	})

	req := httptest.NewRequest(http.MethodGet, "/silent500", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := client.nonFatals.Load(); got != 1 {
		t.Fatalf("silent 500 must record 1 non-fatal; got %d", got)
	}
	v := client.lastErrMsg.Load()
	if v == nil {
		t.Fatal("synthetic cause missing; RecordNonFatal got a nil error on a silent 500")
	}
	msg := v.(string)
	if !strings.Contains(msg, "http 500") || !strings.Contains(msg, "/silent500") {
		t.Errorf("synthetic cause %q does not name the status + path", msg)
	}
}
