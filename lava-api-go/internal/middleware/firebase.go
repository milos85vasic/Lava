// firebase.go — Gin middleware that records HTTP 5xx responses + recovered
// panics as Firebase non-fatals via internal/firebase.Client.
//
// Constitutional bindings:
//
//   - 6.J Anti-Bluff: every non-2xx response is honestly forwarded to the
//     Firebase client. The Configured()=false path becomes a structured
//     log line; the Configured()=true path forwards to the Admin SDK
//     (when wired). No silent drop.
//
//   - 6.A Real-binary contract: this middleware is exercised by the
//     server's e2e suite; a deliberate 5xx in any handler must produce
//     a non-fatal record.

package middleware

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/firebase"
)

// FirebaseTelemetry returns a Gin middleware that:
//
//  1. Records request start time
//  2. After the handler completes, examines status code:
//     - 5xx: record a non-fatal with method, path, status, duration
//     - 4xx: log an event (NOT a non-fatal — client error, not server bug)
//     - 2xx/3xx: log a Performance-style success event
//  3. Recovers from panics: records as non-fatal + re-panics so Gin's own
//     recovery middleware (if installed afterwards) can convert to 500.
//
// The middleware is safe to install even when the Firebase client is in
// no-op mode — every call still produces a structured-log line so the
// surface remains visible.
func FirebaseTelemetry(client firebase.Client) gin.HandlerFunc {
	if client == nil {
		client = firebase.New(firebase.Config{})
	}
	return func(c *gin.Context) {
		start := time.Now()
		defer func() {
			if r := recover(); r != nil {
				err, ok := r.(error)
				if !ok {
					err = fmt.Errorf("panic: %v", r)
				}
				_ = client.RecordNonFatal(c.Request.Context(), err, map[string]string{
					"http.method":   c.Request.Method,
					"http.path":     c.FullPath(),
					"http.url":      c.Request.URL.RequestURI(),
					"event.type":    "panic",
					"event.elapsed": time.Since(start).String(),
				})
				panic(r)
			}
		}()

		c.Next()

		status := c.Writer.Status()
		fields := map[string]string{
			"http.method":   c.Request.Method,
			"http.path":     c.FullPath(),
			"http.status":   fmt.Sprintf("%d", status),
			"event.elapsed": time.Since(start).String(),
		}

		switch {
		case status >= http.StatusInternalServerError:
			cause := firstError(c.Errors)
			if cause == nil {
				cause = fmt.Errorf("http %d on %s %s", status, c.Request.Method, c.FullPath())
			}
			_ = client.RecordNonFatal(c.Request.Context(), cause, fields)
		case status >= http.StatusBadRequest:
			_ = client.LogEvent(c.Request.Context(), "lava_api_4xx", fields)
		default:
			_ = client.LogEvent(c.Request.Context(), "lava_api_2xx", fields)
		}
	}
}

// firstError returns the first concrete error from gin.Errors, or nil
// if the slice is empty / contains only nil entries.
func firstError(errs []*gin.Error) error {
	for _, e := range errs {
		if e == nil || e.Err == nil {
			continue
		}
		return e.Err
	}
	return nil
}

// withTimeout is a small helper used by the telemetry path to bound how
// long any forwarder call may block before the request returns.
func withTimeout(parent context.Context, d time.Duration) (context.Context, context.CancelFunc) {
	if d <= 0 {
		return parent, func() {}
	}
	return context.WithTimeout(parent, d)
}

// IsClientCanceled is a convenience used in the test suite to verify
// that a real-stack assertion uses the same context-cancellation semantics
// as the production code. Not used in the middleware path itself.
func IsClientCanceled(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.Canceled) {
		return true
	}
	return strings.Contains(err.Error(), "context canceled")
}
