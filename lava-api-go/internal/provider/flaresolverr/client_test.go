package flaresolverr

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Anti-Bluff (§6.J): the SUT is the REAL FlareSolverr Client POSTing to a REAL
// httptest.Server that emulates a FlareSolverr sidecar. Primary assertions are
// on the user-visible Solution (the solved HTML the caller parses) + that a
// still-challenged / non-ok reply is surfaced as an error, never as a fake
// success.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in Get, return out.Solution.URL as HTML instead of out.Solution.Response.
//   Observed: TestGet_ReturnsSolvedHTML → solved HTML mismatch (got the URL, not the body).
//   Mutation: drop the `out.Solution.Status < 200 || >= 300` guard.
//   Observed: TestGet_UpstreamNon200IsNotSolved → a 403 challenge page leaks as success.
//   Reverted: yes.

func solveServer(t *testing.T, handler func(req cmdRequest) cmdResponse) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != solvePath || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		var req cmdRequest
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &req)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(handler(req))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func okResp(html string) cmdResponse {
	var rsp cmdResponse
	rsp.Status = "ok"
	rsp.Solution.Status = 200
	rsp.Solution.URL = "https://1337x.to/search/ubuntu/1/"
	rsp.Solution.Response = html
	rsp.Solution.UserAgent = "Mozilla/5.0"
	return rsp
}

func TestGet_ReturnsSolvedHTML(t *testing.T) {
	const wantHTML = "<html><body><table class='table-list'>RESULTS</table></body></html>"
	var gotReq cmdRequest
	srv := solveServer(t, func(req cmdRequest) cmdResponse {
		gotReq = req
		return okResp(wantHTML)
	})
	c := NewClient(srv.URL)

	sol, err := c.Get(context.Background(), "https://1337x.to/search/ubuntu/1/")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if sol.HTML != wantHTML {
		t.Errorf("solved HTML = %q, want %q", sol.HTML, wantHTML)
	}
	if sol.Status != 200 {
		t.Errorf("status = %d, want 200", sol.Status)
	}
	// The request must be a real request.get for the target URL.
	if gotReq.Cmd != "request.get" {
		t.Errorf("cmd = %q, want request.get", gotReq.Cmd)
	}
	if gotReq.URL != "https://1337x.to/search/ubuntu/1/" {
		t.Errorf("url = %q", gotReq.URL)
	}
	if gotReq.MaxTimeout <= 0 {
		t.Errorf("maxTimeout = %d, want > 0", gotReq.MaxTimeout)
	}
}

func TestGet_StatusNotOkIsError(t *testing.T) {
	srv := solveServer(t, func(cmdRequest) cmdResponse {
		var rsp cmdResponse
		rsp.Status = "error"
		rsp.Message = "Challenge not detected!"
		return rsp
	})
	c := NewClient(srv.URL)

	if _, err := c.Get(context.Background(), "https://1337x.to/"); !errors.Is(err, ErrNotSolved) {
		t.Errorf("status!=ok err = %v, want ErrNotSolved", err)
	}
}

func TestGet_UpstreamNon200IsNotSolved(t *testing.T) {
	// FlareSolverr replied ok, but the upstream page is still a 403 challenge —
	// it MUST NOT be surfaced as a usable solved page.
	srv := solveServer(t, func(cmdRequest) cmdResponse {
		var rsp cmdResponse
		rsp.Status = "ok"
		rsp.Solution.Status = 403
		rsp.Solution.Response = "<title>Just a moment...</title>"
		return rsp
	})
	c := NewClient(srv.URL)

	if _, err := c.Get(context.Background(), "https://1337x.to/"); !errors.Is(err, ErrNotSolved) {
		t.Errorf("upstream 403 err = %v, want ErrNotSolved", err)
	}
}

func TestGet_EmptyBodyIsNotSolved(t *testing.T) {
	srv := solveServer(t, func(cmdRequest) cmdResponse { return okResp("   ") })
	c := NewClient(srv.URL)

	if _, err := c.Get(context.Background(), "https://1337x.to/"); !errors.Is(err, ErrNotSolved) {
		t.Errorf("empty body err = %v, want ErrNotSolved", err)
	}
}

func TestGet_NoSidecarConfiguredIsError(t *testing.T) {
	c := NewClient("")
	if _, err := c.Get(context.Background(), "https://1337x.to/"); !errors.Is(err, ErrNotSolved) {
		t.Errorf("empty baseURL err = %v, want ErrNotSolved", err)
	}
}

func TestGet_SidecarHTTPErrorSurfaces(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	t.Cleanup(srv.Close)
	c := NewClient(srv.URL)

	if _, err := c.Get(context.Background(), "https://1337x.to/"); err == nil || !strings.Contains(err.Error(), "502") {
		t.Errorf("sidecar 502 err = %v, want a 502 error", err)
	}
}
