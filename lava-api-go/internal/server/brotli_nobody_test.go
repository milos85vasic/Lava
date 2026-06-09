package server

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/andybalholm/brotli"
	"github.com/gin-gonic/gin"
)

// newBrotliNoBodyRouter installs the real brotli middleware and registers
// handlers that return RFC 9110 §15 no-body statuses. The middleware is the
// production NewBrotliMiddleware — no shortcut, no mock — so a green test here
// means a real client over a real socket sees the correct wire framing.
func newBrotliNoBodyRouter(t *testing.T, status int) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(NewBrotliMiddleware(true, 4))
	r.GET("/nobody", func(c *gin.Context) {
		// A bodyless status: per RFC 9110 §15 this MUST NOT carry a payload.
		c.Status(status)
	})
	return r
}

// TestBrotliMiddleware_NoBodyStatus_NoContentEncoding pins the contract the
// brotli.go docstring (lines 9-12) and statusAllowsBody (line 143) promise:
// responses with a no-body status (204, 304) MUST NOT advertise
// Content-Encoding: br. Emitting it on a bodyless response misleads
// intermediaries and breaks clients that try to brotli-decode an empty stream.
//
// This is the §6.J / Seventh-Law-clause-4 anti-bluff gate that statusAllowsBody
// claims to provide but the middleware never wired up: statusAllowsBody(204)
// tests green in isolation while the middleware emits Content-Encoding: br on a
// 204 anyway. The PRIMARY assertion is on the user-visible response header.
//
// Falsifiability rehearsal: revert the statusAllowsBody gate in
// NewBrotliMiddleware (always set Content-Encoding: br) and this test fails with
// `204: Content-Encoding = "br", want empty`.
func TestBrotliMiddleware_NoBodyStatus_NoContentEncoding(t *testing.T) {
	cases := []struct {
		name   string
		status int
	}{
		{"204 No Content", http.StatusNoContent},
		{"304 Not Modified", http.StatusNotModified},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := newBrotliNoBodyRouter(t, tc.status)
			srv := httptest.NewServer(r)
			defer srv.Close()

			req, _ := http.NewRequest("GET", srv.URL+"/nobody", nil)
			req.Header.Set("Accept-Encoding", "br")
			resp, err := http.DefaultTransport.RoundTrip(req)
			if err != nil {
				t.Fatalf("request error: %v", err)
			}
			defer resp.Body.Close()

			if resp.StatusCode != tc.status {
				t.Fatalf("status = %d, want %d", resp.StatusCode, tc.status)
			}
			if got := resp.Header.Get("Content-Encoding"); got != "" {
				t.Fatalf("%d: Content-Encoding = %q, want empty (no body MUST NOT be compressed)", tc.status, got)
			}
		})
	}
}

// TestBrotliWriter_BodyStatus_WriteEngagesEncoder drives brotliWriter directly:
// WriteHeader(200) MUST engage compression (compress=true, Content-Encoding: br,
// Content-Length stripped) and a subsequent Write MUST route through the brotli
// encoder, producing a frame that decodes back to the input. This is the
// positive companion to the no-body header test: it proves WriteHeader's
// statusAllowsBody gate engages the encoder for a body-bearing status, exercising
// the brotliWriter.Write compress branch at the writer layer.
//
// Falsifiability: invert the statusAllowsBody gate in WriteHeader (treat 200 as
// no-body) — compress stays false, the recorder holds plaintext, and the
// brotli-decode / Content-Encoding assertions fail. Verified via Bluff-Audit.
func TestBrotliWriter_BodyStatus_WriteEngagesEncoder(t *testing.T) {
	gin.SetMode(gin.TestMode)
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request, _ = http.NewRequest("GET", "/", nil)

	bw := brotli.NewWriterLevel(c.Writer, 4)
	w := &brotliWriter{ResponseWriter: c.Writer, bw: bw}

	w.WriteHeader(http.StatusOK)
	if !w.compress {
		t.Fatal("compress NOT engaged for 200 — statusAllowsBody gate wrong")
	}
	if got := rec.Header().Get("Content-Encoding"); got != "br" {
		t.Fatalf("Content-Encoding = %q, want \"br\" for 200", got)
	}

	const payload = "encode-me encode-me encode-me encode-me encode-me encode-me"
	if _, err := w.Write([]byte(payload)); err != nil {
		t.Fatalf("Write: %v", err)
	}
	if err := bw.Close(); err != nil { // flush the encoder into the recorder
		t.Fatalf("brotli Close: %v", err)
	}

	wire := rec.Body.Bytes()
	if string(wire) == payload {
		t.Fatal("recorder body equals plaintext — Write did not route through encoder")
	}
	dec := brotli.NewReader(bytes.NewReader(wire))
	got, err := io.ReadAll(dec)
	if err != nil {
		t.Fatalf("brotli decode: %v\nwire=%x", err, wire)
	}
	if string(got) != payload {
		t.Fatalf("decoded = %q, want %q", got, payload)
	}
}
