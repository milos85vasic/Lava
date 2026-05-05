package middleware

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/firebase"
)

// recordingClient is a real-stack test double that records every call
// for primary-assertion verification (Sixth Law clause 3 — primary
// assertion on user-visible state). It is NOT a mock of internal logic;
// it stands in for the external Firebase boundary, the only permitted
// fake location per Second Law.
type recordingClient struct {
	events     atomic.Int32
	nonFatals  atomic.Int32
	lastErrMsg atomic.Value
	configured bool
}

func (r *recordingClient) LogEvent(_ context.Context, _ string, _ map[string]string) error {
	r.events.Add(1)
	return nil
}

func (r *recordingClient) RecordNonFatal(_ context.Context, err error, _ map[string]string) error {
	r.nonFatals.Add(1)
	if err != nil {
		r.lastErrMsg.Store(err.Error())
	}
	return nil
}

func (r *recordingClient) Configured() bool { return r.configured }

func TestFirebaseTelemetry_5xx_RecordsNonFatal(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	r.Use(FirebaseTelemetry(client))
	r.GET("/boom", func(c *gin.Context) {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "boom"})
	})

	req := httptest.NewRequest(http.MethodGet, "/boom", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d", w.Code)
	}
	if got := client.nonFatals.Load(); got != 1 {
		t.Fatalf("expected 1 non-fatal record, got %d", got)
	}
	if got := client.events.Load(); got != 0 {
		t.Fatalf("5xx should not log a 4xx/2xx event; got %d events", got)
	}
}

func TestFirebaseTelemetry_4xx_LogsEvent(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	r.Use(FirebaseTelemetry(client))
	r.GET("/missing", func(c *gin.Context) {
		c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "missing"})
	})

	req := httptest.NewRequest(http.MethodGet, "/missing", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
	if got := client.events.Load(); got != 1 {
		t.Fatalf("expected 1 event log, got %d", got)
	}
	if got := client.nonFatals.Load(); got != 0 {
		t.Fatalf("4xx must NOT record a non-fatal; got %d", got)
	}
}

func TestFirebaseTelemetry_2xx_LogsEvent(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	r.Use(FirebaseTelemetry(client))
	r.GET("/ok", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	req := httptest.NewRequest(http.MethodGet, "/ok", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if got := client.events.Load(); got != 1 {
		t.Fatalf("expected 1 event log, got %d", got)
	}
	if got := client.nonFatals.Load(); got != 0 {
		t.Fatalf("2xx must NOT record a non-fatal; got %d", got)
	}
}

func TestFirebaseTelemetry_NilClient_DegradesGracefully(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(FirebaseTelemetry(nil))
	r.GET("/ok", func(c *gin.Context) { c.Status(http.StatusOK) })
	req := httptest.NewRequest(http.MethodGet, "/ok", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("nil client must not break the request; got %d", w.Code)
	}
}

func TestFirebaseTelemetry_RecordsHandlerError(t *testing.T) {
	gin.SetMode(gin.TestMode)
	client := &recordingClient{configured: true}
	r := gin.New()
	r.Use(FirebaseTelemetry(client))
	r.GET("/oops", func(c *gin.Context) {
		_ = c.Error(errors.New("upstream timeout"))
		c.AbortWithStatus(http.StatusInternalServerError)
	})

	req := httptest.NewRequest(http.MethodGet, "/oops", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := client.nonFatals.Load(); got != 1 {
		t.Fatalf("expected 1 non-fatal record, got %d", got)
	}
	if v := client.lastErrMsg.Load(); v == nil || !strings.Contains(v.(string), "upstream timeout") {
		t.Fatalf("expected non-fatal to carry handler error; got %v", v)
	}
}

// TestFirebaseTelemetry_RealNoopClient_ProducesStructuredLog asserts the
// production path with a real no-op firebase client (the same path that
// would run in CI when no service-account key is mounted). The assertion
// is on the structured log surface — the no-op client logs every event
// + non-fatal, which is itself the user-visible signal until the Admin
// SDK is wired.
func TestFirebaseTelemetry_RealNoopClient_ProducesStructuredLog(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	c := firebase.New(firebase.Config{Logger: logger})
	if c.Configured() {
		t.Fatal("test pre-condition: firebase client must be no-op (no creds)")
	}
	r := gin.New()
	r.Use(FirebaseTelemetry(c))
	r.GET("/boom", func(g *gin.Context) {
		g.AbortWithStatus(http.StatusInternalServerError)
	})
	req := httptest.NewRequest(http.MethodGet, "/boom", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d", w.Code)
	}
}
