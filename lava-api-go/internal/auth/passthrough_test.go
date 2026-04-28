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
