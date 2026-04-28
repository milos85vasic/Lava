package rutracker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// successfulMainPage is a minimal main-page HTML payload that satisfies
// both VerifyAuthorisedUseCase (logged-in-username) and
// WithFormTokenUseCase (form_token regex). The Russian success sentence
// is intentionally NOT here — it belongs in the /posting.php response.
const successfulMainPage = `<html><body>
<div id='logged-in-username'>milos</div>
<script>
var profile = { form_token: 'tok-XYZ', other: 'noise' };
</script>
</body></html>`

// TestParseFormToken_Found verifies the happy path: a `form_token:
// '...'` line in the HTML body produces (capturedValue, true).
//
// Sixth Law clause 3 — primary assertion is on the returned token
// string, the user-visible (well, caller-visible) output.
func TestParseFormToken_Found(t *testing.T) {
	in := []byte("foo\nform_token: 'abc123',\nbar")
	tok, ok := ParseFormToken(in)
	if !ok {
		t.Fatalf("ParseFormToken: ok=false, want true (input had a marker)")
	}
	if tok != "abc123" {
		t.Errorf("ParseFormToken: token=%q want %q", tok, "abc123")
	}
}

// TestParseFormToken_Missing verifies the no-marker path: input without
// the regex marker produces ("", false).
func TestParseFormToken_Missing(t *testing.T) {
	in := []byte("hello world, no token here")
	tok, ok := ParseFormToken(in)
	if ok {
		t.Errorf("ParseFormToken: ok=true, want false (no marker present)")
	}
	if tok != "" {
		t.Errorf("ParseFormToken: token=%q want \"\"", tok)
	}
}

// TestParseFormToken_EmptyValue mirrors the Kotlin
// `if (formToken.isEmpty()) throw Unauthorized` branch — an empty
// captured group counts as "no match" for the boolean signal.
func TestParseFormToken_EmptyValue(t *testing.T) {
	in := []byte("form_token: '',")
	tok, ok := ParseFormToken(in)
	if ok {
		t.Errorf("ParseFormToken: ok=true, want false for empty captured value")
	}
	if tok != "" {
		t.Errorf("ParseFormToken: token=%q want \"\"", tok)
	}
}

// TestIsAuthorised_True — the marker substring is present.
func TestIsAuthorised_True(t *testing.T) {
	if !IsAuthorised([]byte("<div id='logged-in-username'>")) {
		t.Errorf("IsAuthorised: false, want true (marker present)")
	}
}

// TestIsAuthorised_False — empty HTML is the canonical "not authorised"
// case (the upstream serves a guest landing page when the cookie is
// missing or expired).
func TestIsAuthorised_False(t *testing.T) {
	if IsAuthorised([]byte("")) {
		t.Errorf("IsAuthorised: true, want false (empty input)")
	}
}

// TestAddComment_HappyPath wires AddComment to a real httptest server,
// verifies the upstream success sentence triggers a true return, and —
// per Sixth Law clause 1 — asserts on the actual wire bytes the user's
// action produces (the captured POST request shape).
func TestAddComment_HappyPath(t *testing.T) {
	const (
		topicID  = "topic-42"
		message  = "Hello, world! Привет."
		cookie   = "bb_session=abc; bb_data=opaque"
		formTok  = "tok-XYZ"
		mainBody = successfulMainPage
	)

	var (
		gotMethod      string
		gotPath        string
		gotContentType string
		gotCookie      string
		gotMode        string
		gotSubmitMode  string
		gotT           string
		gotFormToken   string
		gotMessage     string
		postCount      int32
	)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(mainBody))
		case r.Method == http.MethodPost && r.URL.Path == "/posting.php":
			atomic.AddInt32(&postCount, 1)
			gotMethod = r.Method
			gotPath = r.URL.Path
			gotContentType = r.Header.Get("Content-Type")
			gotCookie = r.Header.Get("Cookie")
			if err := r.ParseForm(); err != nil {
				t.Errorf("ParseForm: %v", err)
			}
			gotMode = r.PostForm.Get("mode")
			gotSubmitMode = r.PostForm.Get("submit_mode")
			gotT = r.PostForm.Get("t")
			gotFormToken = r.PostForm.Get("form_token")
			gotMessage = r.PostForm.Get("message")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>... Сообщение было успешно отправлено ...</html>"))
		default:
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddComment(context.Background(), topicID, message, cookie)
	if err != nil {
		t.Fatalf("AddComment: %v", err)
	}
	if !ok {
		t.Errorf("AddComment: ok=false, want true (success sentence present)")
	}

	if got := atomic.LoadInt32(&postCount); got != 1 {
		t.Errorf("postCount=%d want 1", got)
	}
	if gotMethod != http.MethodPost {
		t.Errorf("POST method=%q want %q", gotMethod, http.MethodPost)
	}
	if gotPath != "/posting.php" {
		t.Errorf("POST path=%q want /posting.php", gotPath)
	}
	// Go's http.Client/Server may emit the bare media-type or with a
	// charset suffix; accept both, as long as the form-urlencoded
	// media-type is the prefix.
	if !strings.HasPrefix(gotContentType, "application/x-www-form-urlencoded") {
		t.Errorf("Content-Type=%q want prefix application/x-www-form-urlencoded", gotContentType)
	}
	if gotCookie != cookie {
		t.Errorf("Cookie=%q want %q", gotCookie, cookie)
	}
	if gotMode != "reply" {
		t.Errorf("form mode=%q want reply", gotMode)
	}
	if gotSubmitMode != "submit" {
		t.Errorf("form submit_mode=%q want submit", gotSubmitMode)
	}
	if gotT != topicID {
		t.Errorf("form t=%q want %q", gotT, topicID)
	}
	if gotFormToken != formTok {
		t.Errorf("form form_token=%q want %q", gotFormToken, formTok)
	}
	if gotMessage != message {
		t.Errorf("form message=%q want %q", gotMessage, message)
	}
}

// TestAddComment_UpstreamRejects — the GET succeeds but the POST body
// does NOT contain the success sentence. AddComment must return
// (false, nil): the caller maps this to JSON `false` (silent rejection
// by rutracker, e.g. flood-control).
func TestAddComment_UpstreamRejects(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPage))
		case r.Method == http.MethodPost && r.URL.Path == "/posting.php":
			w.WriteHeader(http.StatusOK)
			// No success sentence.
			_, _ = w.Write([]byte("<html>flood control: please wait 30s</html>"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddComment(context.Background(), "topic-1", "msg", "cookie=v")
	if err != nil {
		t.Fatalf("AddComment: %v", err)
	}
	if ok {
		t.Errorf("AddComment: ok=true, want false (success sentence absent)")
	}
}

// TestAddComment_EmptyCookie_ErrUnauthorized — the empty-cookie
// short-circuit in step 1. No upstream request must be made.
func TestAddComment_EmptyCookie_ErrUnauthorized(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddComment(context.Background(), "topic-1", "msg", "")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("AddComment err=%v want ErrUnauthorized", err)
	}
	if ok {
		t.Errorf("AddComment: ok=true, want false on ErrUnauthorized")
	}
	if got := atomic.LoadInt32(&hits); got != 0 {
		t.Errorf("server hits=%d want 0 (empty cookie must short-circuit)", got)
	}
}

// TestAddComment_NotLoggedIn_ErrUnauthorized — the GET response is
// missing the "logged-in-username" marker. AddComment must abort before
// touching /posting.php.
func TestAddComment_NotLoggedIn_ErrUnauthorized(t *testing.T) {
	var postHits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			// No `logged-in-username` substring.
			_, _ = w.Write([]byte("<html><body>guest landing</body></html>"))
		case r.Method == http.MethodPost && r.URL.Path == "/posting.php":
			atomic.AddInt32(&postHits, 1)
			w.WriteHeader(http.StatusOK)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddComment(context.Background(), "topic-1", "msg", "cookie=v")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("AddComment err=%v want ErrUnauthorized", err)
	}
	if ok {
		t.Errorf("AddComment: ok=true, want false on ErrUnauthorized")
	}
	if got := atomic.LoadInt32(&postHits); got != 0 {
		t.Errorf("/posting.php hits=%d want 0 (must not reach POST step)", got)
	}
}

// TestAddComment_FormTokenMissing_ErrUnauthorized — the GET response
// has the marker but no `form_token: '...'` regex match. AddComment
// must abort (the rotating token is rutracker's CSRF defence; without
// it, no comment can be posted).
func TestAddComment_FormTokenMissing_ErrUnauthorized(t *testing.T) {
	var postHits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			// Has the marker but no form_token line.
			_, _ = w.Write([]byte("<div id='logged-in-username'>milos</div>"))
		case r.Method == http.MethodPost && r.URL.Path == "/posting.php":
			atomic.AddInt32(&postHits, 1)
			w.WriteHeader(http.StatusOK)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddComment(context.Background(), "topic-1", "msg", "cookie=v")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("AddComment err=%v want ErrUnauthorized", err)
	}
	if ok {
		t.Errorf("AddComment: ok=true, want false on ErrUnauthorized")
	}
	if got := atomic.LoadInt32(&postHits); got != 0 {
		t.Errorf("/posting.php hits=%d want 0 (must not reach POST step)", got)
	}
}

// TestPostForm_Forwards5xxAsError pins the breaker semantics for the
// new POST helper: a 500 response from the upstream surfaces as a
// non-nil error so the breaker can count it as a failure. Without
// this, the breaker would never trip on a flaky POST endpoint.
func TestPostForm_Forwards5xxAsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	form := map[string][]string{"k": {"v"}}
	_, _, err := c.PostForm(context.Background(), "/", form, "")
	if err == nil {
		t.Fatal("expected error for 500 response, got nil")
	}
}
