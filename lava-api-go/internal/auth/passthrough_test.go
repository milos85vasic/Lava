package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestRealmHashEmptyForMissingHeader(t *testing.T) {
	req := httptest.NewRequest("GET", "/", nil)
	if got := RealmHash(req); got != "" {
		t.Errorf("got=%q want empty", got)
	}
}

func TestRealmHashStable(t *testing.T) {
	mk := func() *http.Request {
		req := httptest.NewRequest("GET", "/", nil)
		req.Header.Set(HeaderName, "secret-token")
		return req
	}
	a := RealmHash(mk())
	b := RealmHash(mk())
	if a != b || a == "" || len(a) != 64 {
		t.Fatalf("hash unstable or wrong length: a=%q b=%q", a, b)
	}
}

func TestRealmHashDistinctTokensProduceDistinctHashes(t *testing.T) {
	r1 := httptest.NewRequest("GET", "/", nil)
	r1.Header.Set(HeaderName, "alpha")
	r2 := httptest.NewRequest("GET", "/", nil)
	r2.Header.Set(HeaderName, "beta")
	if RealmHash(r1) == RealmHash(r2) {
		t.Fatal("distinct tokens produced identical hashes")
	}
}

func TestUpstreamCookieFormatsCorrectly(t *testing.T) {
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set(HeaderName, "abcd")
	if c := UpstreamCookie(req); c != "bb_session=abcd" {
		t.Errorf("got=%q want bb_session=abcd", c)
	}
}

func TestUpstreamCookieEmptyForMissingHeader(t *testing.T) {
	req := httptest.NewRequest("GET", "/", nil)
	if c := UpstreamCookie(req); c != "" {
		t.Errorf("got=%q want empty", c)
	}
}

func TestGinMiddlewareSetsContextValue(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(GinMiddleware())
	var observed string
	r.GET("/", func(c *gin.Context) { observed = HashFromContext(c) })
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set(HeaderName, "x")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if observed == "" || len(observed) != 64 {
		t.Fatalf("middleware did not set realm hash: %q", observed)
	}
}

func TestGinMiddlewareEmptyTokenSetsEmptyHash(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(GinMiddleware())
	var observed string
	r.GET("/", func(c *gin.Context) { observed = HashFromContext(c) })
	req := httptest.NewRequest("GET", "/", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if observed != "" {
		t.Fatalf("expected empty hash for missing Auth-Token, got %q", observed)
	}
}

func TestHashFromContextDefaultsToEmpty(t *testing.T) {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	if got := HashFromContext(c); got != "" {
		t.Fatalf("got=%q want empty when middleware never ran", got)
	}
}

// TestUpstreamCookieForwardsCookieLineVerbatim — SP-3.5 Sixth-Law
// regression guard.
//
// Forensic anchor: the Android client (and the legacy Ktor proxy) store
// the raw upstream Set-Cookie line at login, e.g.
//
//	bb_session=0-47500467-…; expires=Sat, 26-Apr-2036 …; Max-Age=…; …
//
// and then send that whole string as the Auth-Token header on every
// subsequent request. The previous UpstreamCookie unconditionally
// prepended "bb_session=" to this token, producing a double-prefixed
// `bb_session=bb_session=0-47500467-…; …` Cookie header that rutracker
// parsed as an anonymous session — so /search?query=ps4 returned
// {"torrents":[]} on real device even with valid credentials, even
// though the same query in a browser returns thousands of hits.
//
// This test pins the verbatim-forward branch: any token that already
// looks like a Cookie line (contains '=') MUST be forwarded as-is.
//
// MUTATION: reverting to `return "bb_session=" + tok` makes this test
// fail with `got="bb_session=bb_session=…" want "bb_session=…"`.
func TestUpstreamCookieForwardsCookieLineVerbatim(t *testing.T) {
	const cookieLine = "bb_session=0-47500467-nycO0HaWwWr2QHYNcYlR; expires=Sat, 26-Apr-2036 19:25:18 GMT; Max-Age=315360000; path=/forum/; domain=.rutracker.org; secure; HttpOnly"
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set(HeaderName, cookieLine)
	if c := UpstreamCookie(req); c != cookieLine {
		t.Errorf("got=%q\nwant=%q (cookie line MUST be forwarded verbatim per SP-3.5; double-prefixing breaks search/favorites/etc.)", c, cookieLine)
	}
}

// TestUpstreamCookie_TokenWithEqualsForwardsVerbatim — defensive: any
// token that already contains an '=' is treated as a name=value line
// and forwarded verbatim, even if the name isn't bb_session.
func TestUpstreamCookie_TokenWithEqualsForwardsVerbatim(t *testing.T) {
	// A future schema might use a different cookie name; we still must
	// forward what the client gave us, not silently rewrap.
	const tok = "session_v2=opaque-token-value"
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set(HeaderName, tok)
	if c := UpstreamCookie(req); c != tok {
		t.Errorf("got=%q want %q (any name=value pair forwards verbatim)", c, tok)
	}
}
