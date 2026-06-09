package server

import (
	"bytes"
	"io"
	"net/http"
	"testing"

	"github.com/andybalholm/brotli"
)

// brotli_writestring_test.go covers two production paths brotli_test.go leaves
// at 0%:
//
//   - brotliWriter.WriteString (brotli.go:132): the io.StringWriter fast-path.
//     gin's c.String / c.HTML route string writes through io.WriteString, which
//     dispatches to the WriteString method when the writer implements
//     io.StringWriter. The existing tests only trigger Write([]byte), so a
//     regression here (e.g. WriteString bypassing the brotli encoder and
//     writing plaintext) would corrupt compressed string responses invisibly.
//   - statusAllowsBody (brotli.go:143): the RFC 9110 §15 no-body-status gate.
//     Real production logic with a defined contract and zero current coverage.
//
// White-box (package server) because both symbols are package-private. The SUT
// is a REAL brotliWriter wrapping a REAL brotli.Writer over a real buffer — no
// mocking; the primary assertion is on the actual bytes / boolean produced.

// TestBrotliWriter_WriteString_RoutesThroughEncoder asserts that bytes written
// via WriteString land in the brotli stream and decode back to the exact input.
// We construct the writer EXACTLY as NewBrotliMiddleware does
// (brotli.NewWriterLevel(dst, quality)) and drive WriteString directly.
//
// Primary assertion: the decoded bytes equal the input string AND the wire
// bytes differ from plaintext (proving real compression, not a pass-through).
//
// Falsifiability: change brotli.go:133 from `w.bw.Write([]byte(s))` to
// `w.ResponseWriter.Write([]byte(s))` (bypass the encoder) and this test fails
// at the brotli-decode step / the "wire == plaintext" guard.
func TestBrotliWriter_WriteString_RoutesThroughEncoder(t *testing.T) {
	const payload = "Lava WriteString path. repeat repeat repeat repeat repeat repeat repeat."

	var dst bytes.Buffer
	bw := brotli.NewWriterLevel(&dst, 4)

	// Embedded gin.ResponseWriter is irrelevant to WriteString (it only touches
	// w.bw); leave it nil — WriteString must never reach into the embedded field.
	w := &brotliWriter{bw: bw}

	n, err := w.WriteString(payload)
	if err != nil {
		t.Fatalf("WriteString error: %v", err)
	}
	// brotli buffers; the returned count is plaintext bytes consumed.
	if n != len(payload) {
		t.Fatalf("WriteString returned n=%d, want %d (plaintext bytes consumed)", n, len(payload))
	}
	if err := bw.Close(); err != nil {
		t.Fatalf("brotli Close (flush): %v", err)
	}

	wire := dst.Bytes()
	if bytes.Equal(wire, []byte(payload)) {
		t.Fatalf("wire bytes equal plaintext — WriteString did not compress (encoder bypassed)")
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

// TestBrotliWriter_IoWriteString_UsesStringWriterFastPath proves *brotliWriter
// satisfies io.StringWriter and that io.WriteString dispatches to its
// WriteString method (the exact mechanism gin's c.String uses), not a generic
// []byte fallback. This is the production trigger for brotli.go:132.
//
// Falsifiability: rename the WriteString method (so *brotliWriter no longer
// implements io.StringWriter) OR make it emit plaintext — the decode below
// then fails.
func TestBrotliWriter_IoWriteString_UsesStringWriterFastPath(t *testing.T) {
	const payload = "fast-path fast-path fast-path fast-path fast-path fast-path"

	// Compile-time proof the SUT is an io.StringWriter (gin's precondition).
	var _ io.StringWriter = (*brotliWriter)(nil)

	var dst bytes.Buffer
	bw := brotli.NewWriterLevel(&dst, 4)
	w := &brotliWriter{bw: bw}

	if _, err := io.WriteString(w, payload); err != nil {
		t.Fatalf("io.WriteString: %v", err)
	}
	if err := bw.Close(); err != nil {
		t.Fatalf("brotli Close: %v", err)
	}

	dec := brotli.NewReader(bytes.NewReader(dst.Bytes()))
	got, err := io.ReadAll(dec)
	if err != nil {
		t.Fatalf("brotli decode: %v", err)
	}
	if string(got) != payload {
		t.Fatalf("decoded via io.WriteString = %q, want %q", got, payload)
	}
}

// TestStatusAllowsBody_Contract is the RFC 9110 §15 truth table for
// statusAllowsBody (brotli.go:143). 1xx, 204, 304 MUST NOT carry a body;
// everything else may. The primary assertion is the boolean the production
// function returns for each status.
//
// Falsifiability: flip any case in statusAllowsBody (e.g. make the 204 branch
// `return true`, or change the 1xx range) and the matching row fails.
func TestStatusAllowsBody_Contract(t *testing.T) {
	cases := []struct {
		code int
		want bool
	}{
		{http.StatusContinue, false},           // 100
		{http.StatusSwitchingProtocols, false}, // 101
		{http.StatusProcessing, false},         // 102
		{http.StatusOK, true},                  // 200
		{http.StatusCreated, true},             // 201
		{http.StatusNoContent, false},          // 204 — no body
		{http.StatusPartialContent, true},      // 206
		{http.StatusMovedPermanently, true},    // 301
		{http.StatusNotModified, false},        // 304 — no body
		{http.StatusBadRequest, true},          // 400
		{http.StatusInternalServerError, true}, // 500
	}
	for _, tc := range cases {
		if got := statusAllowsBody(tc.code); got != tc.want {
			t.Errorf("statusAllowsBody(%d) = %v, want %v", tc.code, got, tc.want)
		}
	}
}
