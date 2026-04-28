package rutracker

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// loginFormHTML is a minimal but byte-faithful login-form fragment that
// satisfies all three captcha regexes. Used by tests that need a
// parseable captcha embedded in the response body.
const loginFormHTML = `<html><body class='login-form'>
<form>
  <input type="hidden" name="cap_sid" value="sid-XYZ-123" />
  <input type="text" name="cap_code_abc123" value="" />
  <img src="https://static.t-ru.org/captcha/captcha-12345.png" alt="captcha" />
</form>
</body></html>`

// loginFormHTMLNoCaptcha is the login-form fragment WITHOUT a captcha —
// the upstream renders this on the very first failed login attempt.
const loginFormHTMLNoCaptcha = `<html><body class='login-form'>
<form>
  <input type="text" name="login_username" />
  <input type="password" name="login_password" />
</form>
</body></html>`

// TestParseCaptcha_HappyPath_AbsoluteURL — input HTML carries an absolute
// captcha-image URL ("contains http"). ParseCaptcha returns a CaptchaDto
// with Id/Code/Url verbatim from the HTML.
//
// Sixth Law clause 3 — primary assertion is on the user-visible struct
// field values (the bytes the API client will see in JSON).
func TestParseCaptcha_HappyPath_AbsoluteURL(t *testing.T) {
	got := ParseCaptcha([]byte(loginFormHTML))
	if got == nil {
		t.Fatal("ParseCaptcha: nil, want non-nil for fully-matched input")
	}
	if got.Id != "sid-XYZ-123" {
		t.Errorf("Captcha.Id=%q want %q", got.Id, "sid-XYZ-123")
	}
	if got.Code != "cap_code_abc123" {
		t.Errorf("Captcha.Code=%q want %q", got.Code, "cap_code_abc123")
	}
	if got.Url != "https://static.t-ru.org/captcha/captcha-12345.png" {
		t.Errorf("Captcha.Url=%q want absolute URL verbatim", got.Url)
	}
}

// TestParseCaptcha_HappyPath_RelativeURL — when the captcha src does NOT
// contain "http" anywhere in it, the parser MUST prepend "https://" and
// strip leading/trailing slashes.
func TestParseCaptcha_HappyPath_RelativeURL(t *testing.T) {
	in := []byte(`
<input name="cap_sid" value="sid-1" />
<input name="cap_code_xy" value="" />
<img src="//static.example.com/captcha/abc.png" />
`)
	got := ParseCaptcha(in)
	if got == nil {
		t.Fatal("ParseCaptcha: nil, want non-nil")
	}
	want := "https://static.example.com/captcha/abc.png"
	if got.Url != want {
		t.Errorf("Captcha.Url=%q want %q", got.Url, want)
	}
}

// TestParseCaptcha_MissingFields — table-driven coverage of the three
// "one regex didn't match" branches. Each input drops exactly one of
// (sid input, code input, captcha image src) and expects nil.
func TestParseCaptcha_MissingFields(t *testing.T) {
	cases := []struct {
		name string
		html string
	}{
		{
			name: "missing-cap-code",
			html: `
<input name="cap_sid" value="sid-1" />
<img src="https://x/captcha/y.png" />`,
		},
		{
			name: "missing-cap-sid",
			html: `
<input name="cap_code_xy" value="" />
<img src="https://x/captcha/y.png" />`,
		},
		{
			name: "missing-captcha-img",
			html: `
<input name="cap_sid" value="sid-1" />
<input name="cap_code_xy" value="" />
<img src="https://x/avatar/y.png" />`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := ParseCaptcha([]byte(tc.html)); got != nil {
				t.Errorf("ParseCaptcha=%+v want nil for input missing one field", got)
			}
		})
	}
}

// TestExtractLoginToken_FiltersBbSsl — pass two Set-Cookie values, one
// containing "bb_ssl" and one not. The non-bb_ssl one MUST win.
//
// Sixth Law clause 3 — primary assertion is on the returned token
// string, the literal cookie line that becomes UserDto.Token in JSON.
func TestExtractLoginToken_FiltersBbSsl(t *testing.T) {
	h := http.Header{}
	h.Add("Set-Cookie", "bb_ssl=1; Path=/")
	h.Add("Set-Cookie", "bb_data=opaqueAuthToken; Path=/")
	got := ExtractLoginToken(h)
	if got != "bb_data=opaqueAuthToken; Path=/" {
		t.Errorf("ExtractLoginToken=%q want bb_data line", got)
	}
}

// TestExtractLoginToken_OnlyBbSsl — only one cookie present and it's
// bb_ssl: ExtractLoginToken must return "" so Login falls through to the
// captcha/wrong-credits/no-data branches.
func TestExtractLoginToken_OnlyBbSsl(t *testing.T) {
	h := http.Header{}
	h.Add("Set-Cookie", "bb_ssl=1; Secure")
	if got := ExtractLoginToken(h); got != "" {
		t.Errorf("ExtractLoginToken=%q want \"\" (only bb_ssl present)", got)
	}
}

// TestExtractLoginToken_Empty — no Set-Cookie at all: returns "".
func TestExtractLoginToken_Empty(t *testing.T) {
	if got := ExtractLoginToken(http.Header{}); got != "" {
		t.Errorf("ExtractLoginToken=%q want \"\" for empty headers", got)
	}
}

// indexBodyWithUserID returns a minimal /index.php body whose
// `#logged-in-username` href carries the given user id in `?u=`.
func indexBodyWithUserID(uid string) string {
	return fmt.Sprintf(`<html><body>
<a id="logged-in-username" href="profile.php?u=%s">alice</a>
</body></html>`, uid)
}

// profileBody returns a minimal /profile.php?mode=viewprofile&u=<id>
// body whose `#profile-uname[data-uid]` and `#avatar-img > img[src]` are
// the two fields UserDto needs.
func profileBody(uid, avatar string) string {
	return fmt.Sprintf(`<html><body>
<a id="profile-uname" data-uid="%s">alice</a>
<div id="avatar-img"><img src="%s" /></div>
</body></html>`, uid, avatar)
}

// TestLogin_Success_FetchesProfile — the full happy path:
//
//   1. POST /login.php returns two Set-Cookie headers; only the
//      non-bb_ssl one becomes the token.
//   2. The token is forwarded as a Cookie to GET /index.php; the
//      response carries `#logged-in-username[?u=42]`, so userID=42.
//   3. GET /profile.php?mode=viewprofile&u=42 returns the avatar URL
//      and data-uid; UserDto is populated and wrapped in a Success
//      AuthResponseDto.
//
// Sixth Law clause 3 — primary assertions are on the user-visible
// UserDto fields (Id, Token, AvatarUrl) AND on the form bytes that
// went over the wire to /login.php.
func TestLogin_Success_FetchesProfile(t *testing.T) {
	const (
		username    = "alice"
		password    = "secret"
		bbSslCookie = "bb_ssl=1; Path=/; Secure"
		bbDataLine  = "bb_data=opaqueAuthToken; Path=/"
		userID      = "42"
		avatarURL   = "https://avatar.example/picture.png"
	)

	var (
		gotLoginUsername string
		gotLoginPassword string
		gotLoginSubmit   string
		gotCapSid        string
		gotCapCodeAbc    string
		loginCount       int32
	)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/login.php":
			atomic.AddInt32(&loginCount, 1)
			if err := r.ParseForm(); err != nil {
				t.Errorf("ParseForm: %v", err)
			}
			gotLoginUsername = r.PostForm.Get("login_username")
			gotLoginPassword = r.PostForm.Get("login_password")
			gotLoginSubmit = r.PostForm.Get("login")
			gotCapSid = r.PostForm.Get("cap_sid")
			gotCapCodeAbc = r.PostForm.Get("cap_code_abc123")
			w.Header().Add("Set-Cookie", bbSslCookie)
			w.Header().Add("Set-Cookie", bbDataLine)
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>any-body</html>"))
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			// Verify the token-as-cookie was forwarded.
			if got := r.Header.Get("Cookie"); got != bbDataLine {
				t.Errorf("/index.php Cookie=%q want %q", got, bbDataLine)
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(indexBodyWithUserID(userID)))
		case r.Method == http.MethodGet && r.URL.Path == "/profile.php":
			if r.URL.Query().Get("mode") != "viewprofile" {
				t.Errorf("profile.php mode=%q want viewprofile", r.URL.Query().Get("mode"))
			}
			if r.URL.Query().Get("u") != userID {
				t.Errorf("profile.php u=%q want %q", r.URL.Query().Get("u"), userID)
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(profileBody(userID, avatarURL)))
		default:
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{
		Username: username,
		Password: password,
	})
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	if out == nil {
		t.Fatal("Login: nil out, want Success")
	}
	success, err := out.AsAuthResponseDtoSuccess()
	if err != nil {
		t.Fatalf("AsAuthResponseDtoSuccess: %v (out=%+v)", err, out)
	}
	if success.User.Id != userID {
		t.Errorf("User.Id=%q want %q", success.User.Id, userID)
	}
	if success.User.Token != bbDataLine {
		t.Errorf("User.Token=%q want bb_data line %q", success.User.Token, bbDataLine)
	}
	if success.User.AvatarUrl != avatarURL {
		t.Errorf("User.AvatarUrl=%q want %q", success.User.AvatarUrl, avatarURL)
	}

	// Captured form-data assertions.
	if got := atomic.LoadInt32(&loginCount); got != 1 {
		t.Errorf("/login.php hit count=%d want 1", got)
	}
	if gotLoginUsername != username {
		t.Errorf("login_username=%q want %q", gotLoginUsername, username)
	}
	if gotLoginPassword != password {
		t.Errorf("login_password=%q want %q", gotLoginPassword, password)
	}
	if gotLoginSubmit != "Вход" {
		t.Errorf("login=%q want Russian \"Вход\" literal", gotLoginSubmit)
	}
	// Captcha fields MUST NOT be present when all three are nil.
	if gotCapSid != "" {
		t.Errorf("cap_sid=%q want empty (no captcha provided)", gotCapSid)
	}
	if gotCapCodeAbc != "" {
		t.Errorf("cap_code_abc123=%q want empty (no captcha provided)", gotCapCodeAbc)
	}
}

// TestLogin_WrongCredits — the upstream returns no non-bb_ssl Set-Cookie,
// the body contains the login-form, the wrong-credits sentence, AND a
// parseable captcha. Login MUST emit AuthResponseDtoWrongCredits with a
// non-nil Captcha.
func TestLogin_WrongCredits(t *testing.T) {
	body := loginFormHTML + "\n<p>неверный пароль</p>"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Only bb_ssl, no auth cookie.
		w.Header().Add("Set-Cookie", "bb_ssl=1; Path=/")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{Username: "x", Password: "y"})
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	wc, err := out.AsAuthResponseDtoWrongCredits()
	if err != nil {
		t.Fatalf("AsAuthResponseDtoWrongCredits: %v", err)
	}
	if wc.Captcha == nil {
		t.Fatal("WrongCredits.Captcha=nil want parsed CaptchaDto")
	}
	if wc.Captcha.Id != "sid-XYZ-123" {
		t.Errorf("Captcha.Id=%q want sid-XYZ-123", wc.Captcha.Id)
	}
}

// TestLogin_WrongCreditsNoCaptcha — same as above but body is missing
// the captcha (one of the three regex matches fails). Login MUST still
// emit WrongCredits, with Captcha == nil.
func TestLogin_WrongCreditsNoCaptcha(t *testing.T) {
	body := loginFormHTMLNoCaptcha + "\n<p>неверный пароль</p>"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Set-Cookie", "bb_ssl=1")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{Username: "x", Password: "y"})
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	wc, err := out.AsAuthResponseDtoWrongCredits()
	if err != nil {
		t.Fatalf("AsAuthResponseDtoWrongCredits: %v", err)
	}
	if wc.Captcha != nil {
		t.Errorf("WrongCredits.Captcha=%+v want nil (body had no captcha)", wc.Captcha)
	}
}

// TestLogin_CaptchaRequired — login-form + parseable captcha but NO
// "неверный пароль" sentence ⇒ CaptchaRequired with the parsed captcha.
func TestLogin_CaptchaRequired(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Set-Cookie", "bb_ssl=1")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(loginFormHTML))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{Username: "x", Password: "y"})
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	cr, err := out.AsAuthResponseDtoCaptchaRequired()
	if err != nil {
		t.Fatalf("AsAuthResponseDtoCaptchaRequired: %v", err)
	}
	if cr.Captcha == nil {
		t.Fatal("CaptchaRequired.Captcha=nil want parsed CaptchaDto")
	}
	if cr.Captcha.Code != "cap_code_abc123" {
		t.Errorf("Captcha.Code=%q want cap_code_abc123", cr.Captcha.Code)
	}
}

// TestLogin_NoData_ErrNoData — body has neither token nor login-form
// substring ⇒ ErrNoData (the Kotlin `throw NoData` branch).
func TestLogin_NoData_ErrNoData(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("<html>just some unrelated page</html>"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{Username: "x", Password: "y"})
	if !errors.Is(err, ErrNoData) {
		t.Fatalf("Login err=%v want ErrNoData", err)
	}
	if out != nil {
		t.Errorf("Login out=%+v want nil on ErrNoData", out)
	}
}

// TestLogin_FormPresentNoCaptchaNoWrong_ErrUnknown — the body has the
// login-form marker but no captcha and no "неверный пароль" sentence ⇒
// ErrUnknown (the Kotlin `throw Unknown` branch).
func TestLogin_FormPresentNoCaptchaNoWrong_ErrUnknown(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(loginFormHTMLNoCaptcha))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.Login(context.Background(), LoginParams{Username: "x", Password: "y"})
	if !errors.Is(err, ErrUnknown) {
		t.Fatalf("Login err=%v want ErrUnknown", err)
	}
	if out != nil {
		t.Errorf("Login out=%+v want nil on ErrUnknown", out)
	}
}

// TestLogin_ForwardsCaptchaFields — when all three captcha fields are
// non-nil, Login MUST submit BOTH `cap_sid=<sid>` AND the dynamic
// `<code-key>=<value>` field. Asserted on the wire bytes the upstream
// would receive (Sixth Law clause 3).
func TestLogin_ForwardsCaptchaFields(t *testing.T) {
	const (
		sid     = "captcha-sid-9"
		codeKey = "cap_code_dynamic_x"
		val     = "user-typed-answer"
	)
	var (
		gotCapSid    string
		gotCapAnswer string
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Errorf("ParseForm: %v", err)
		}
		gotCapSid = r.PostForm.Get("cap_sid")
		gotCapAnswer = r.PostForm.Get(codeKey)
		// Force the no-data branch — we only care about the captured
		// form data, not the response shape.
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("nothing here"))
	}))
	defer srv.Close()

	sidp := sid
	codep := codeKey
	valp := val
	c := NewClient(srv.URL)
	_, _ = c.Login(context.Background(), LoginParams{
		Username:     "u",
		Password:     "p",
		CaptchaSid:   &sidp,
		CaptchaCode:  &codep,
		CaptchaValue: &valp,
	})

	if gotCapSid != sid {
		t.Errorf("cap_sid=%q want %q", gotCapSid, sid)
	}
	if gotCapAnswer != val {
		t.Errorf("dynamic field %q=%q want %q", codeKey, gotCapAnswer, val)
	}
}

// TestLogin_PartialCaptchaOmitted — only CaptchaSid is non-nil; the
// other two fields are nil. The all-three-or-none guard MUST drop ALL
// three from the form (rutracker rejects partial captcha submissions,
// so half-submitting them is a bug we MUST NOT introduce).
func TestLogin_PartialCaptchaOmitted(t *testing.T) {
	var capSidPresent, capSidValue string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Errorf("ParseForm: %v", err)
		}
		// Capture both presence ("does the key appear at all") and
		// value (sanity).
		if _, ok := r.PostForm["cap_sid"]; ok {
			capSidPresent = "yes"
		}
		capSidValue = r.PostForm.Get("cap_sid")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("nothing here"))
	}))
	defer srv.Close()

	sid := "sid-only"
	c := NewClient(srv.URL)
	_, _ = c.Login(context.Background(), LoginParams{
		Username:   "u",
		Password:   "p",
		CaptchaSid: &sid,
		// CaptchaCode and CaptchaValue are nil — partial set.
	})

	if capSidPresent != "" {
		t.Errorf("cap_sid present in form, want absent (partial captcha must be dropped)")
	}
	if capSidValue != "" {
		t.Errorf("cap_sid=%q want empty", capSidValue)
	}
}

// TestLogin_RusEvenInQuery — defensive: the wire-form-encoded "Вход"
// value MUST decode back to the same bytes server-side. Caught a
// would-be regression where strings.NewReader plus url.Values.Encode
// could mis-handle multi-byte literals if the encoder were swapped.
func TestLogin_RusEvenInQuery(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Errorf("ParseForm: %v", err)
		}
		got = r.PostForm.Get("login")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("nothing"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, _ = c.Login(context.Background(), LoginParams{Username: "u", Password: "p"})
	if got != "Вход" {
		t.Errorf("login wire value=%q (bytes=%v) want Russian Вход (bytes=%v)",
			got, []byte(got), []byte("Вход"))
	}
	// Sanity — the "Вход" literal is exactly 8 bytes (4 cyrillic chars
	// × 2 bytes each in UTF-8). Double-protection against an editor
	// silently transliterating to ASCII.
	if !strings.HasPrefix(got, "В") {
		t.Errorf("login does not start with cyrillic В")
	}
}
