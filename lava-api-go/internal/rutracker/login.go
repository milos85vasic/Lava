// Package rutracker — login.go ports the rutracker.org login flow from
// Kotlin:
//
//   - LoginUseCase.kt              → Login orchestrator (token / form /
//                                    captcha resolution tree).
//   - LoginUseCase.ParseCaptchaUseCase → ParseCaptcha (three-regex
//                                    extraction of CaptchaDto from the
//                                    login-form HTML).
//   - GetCurrentProfileUseCase.kt  → fetchUserProfile step 1: extract
//                                    userId from /index.php's
//                                    `#logged-in-username[?u=]` href.
//   - GetProfileUseCase.kt         → fetchUserProfile step 2: parse
//                                    /profile.php?mode=viewprofile&u=<id>
//                                    into UserDto fields.
//   - RuTrackerInnerApiImpl.kt:35-56 → POST /login.php with the form
//                                    shape; the conditional "all three
//                                    captcha fields or none" guard;
//                                    the bb_ssl-cookie filter that
//                                    extracts the auth token.
//
// The byte-equal Russian literals — "Вход" (the login submit value) and
// "неверный пароль" (the wrong-credits sentence) — come straight from
// the Kotlin sources and MUST NOT be transliterated; the upstream
// matches on the exact UTF-8 bytes.
package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// LoginParams mirrors the rutracker form-data shape consumed by
// RuTrackerInnerApiImpl.login. Username and Password are required; the
// three captcha fields are nilable and are forwarded only when ALL THREE
// are non-nil — this is the exact `if (a != null && b != null && c != null)`
// guard from the Kotlin source. A partial set is silently dropped, NEVER
// half-submitted (rutracker rejects partial captcha submissions).
//
// CaptchaCode is special: its STRING VALUE is the form-field NAME for
// the captcha answer (rutracker rotates this on each captcha render).
// CaptchaValue is the user-entered captcha solution. So the form ends
// up with two extra fields: `cap_sid=<CaptchaSid>` and
// `<CaptchaCode>=<CaptchaValue>`.
type LoginParams struct {
	Username     string
	Password     string
	CaptchaSid   *string // form key: cap_sid
	CaptchaCode  *string // dynamic: this string IS the form-field name
	CaptchaValue *string // form value for the CaptchaCode-named field
}

// captchaCodeRe / captchaSidRe / captchaURLRe are byte-equal to Kotlin's
// LoginUseCase.ParseCaptchaUseCase regexes. Do NOT collapse whitespace,
// rewrite the character classes, or relax the URL "/captcha/" segment —
// the legacy proxy depends on the same regex shape and the upstream HTML
// is the contract we're matching.
var (
	captchaCodeRe = regexp.MustCompile(`<input[^>]*name="(cap_code_[^"]+)"[^>]*>`)
	captchaSidRe  = regexp.MustCompile(`<input[^>]*name="cap_sid"[^>]*value="([^"]+)"[^>]*>`)
	captchaURLRe  = regexp.MustCompile(`<img[^>]*src="([^"]+/captcha/[^"]+)"[^>]*>`)
)

// loginFormMarker / wrongCreditsMarker are the byte-equal substrings
// LoginUseCase searches for in the response body. The Russian wrong-
// credits sentence MUST stay byte-equal to the Kotlin literal — the
// upstream emits it verbatim.
var (
	loginFormMarker     = []byte("login-form")
	wrongCreditsMarker  = []byte("неверный пароль")
	bbSslCookieFragment = "bb_ssl"
)

// loginSubmitValue is the byte-equal Russian "Вход" literal from
// RuTrackerInnerApiImpl.login: the upstream login form's submit-button
// value. Stored as a constant so a transliteration regression is a
// compile-visible diff.
const loginSubmitValue = "Вход"

// ParseCaptcha extracts the rutracker login-form embedded captcha into a
// CaptchaDto, or returns nil when at least one of the three regex
// matches fails (code-name input, sid-value input, captcha-image src).
// All three MUST match — partial matches return nil so the caller can
// fall through to the "wrong credits without captcha" or ErrUnknown
// branches in LoginUseCase.
//
// URL normalisation: if the matched src contains "http" anywhere in it,
// it is used verbatim; otherwise "https://" is prepended after stripping
// leading and trailing slashes. This mirrors Kotlin's
// `url.takeIf { it.contains("http") } ?: "https://${url.trim('/')}"`.
// Note this is a `contains` check, NOT a `HasPrefix` check — a relative
// URL whose substring happens to contain "http" stays unprefixed (the
// legacy proxy behaviour).
func ParseCaptcha(html []byte) *gen.CaptchaDto {
	code := captchaCodeRe.FindSubmatch(html)
	sid := captchaSidRe.FindSubmatch(html)
	surl := captchaURLRe.FindSubmatch(html)
	if code == nil || sid == nil || surl == nil {
		return nil
	}
	raw := string(surl[1])
	var u string
	if strings.Contains(raw, "http") {
		u = raw
	} else {
		u = "https://" + strings.Trim(raw, "/")
	}
	return &gen.CaptchaDto{
		Id:   string(sid[1]),
		Code: string(code[1]),
		Url:  u,
	}
}

// ExtractLoginToken returns the FIRST Set-Cookie value that does NOT
// contain "bb_ssl". Mirrors Kotlin's
// `cookie.firstOrNull { !it.contains("bb_ssl") }` — bb_ssl is the
// transport-layer flag rutracker sets on every response; the auth token
// is on a different cookie (typically bb_data / bb_t). When there is no
// non-bb_ssl cookie, returns the empty string.
func ExtractLoginToken(headers http.Header) string {
	for _, c := range headers.Values("Set-Cookie") {
		if !strings.Contains(c, bbSslCookieFragment) {
			return c
		}
	}
	return ""
}

// Login posts /login.php with the given credentials and returns the
// discriminated AuthResponseDto union. Resolution tree:
//
//  1. Set-Cookie carries a non-bb_ssl token → fetch /index.php with that
//     cookie, extract userId from `#logged-in-username[?u=]`, fetch
//     /profile.php?mode=viewprofile&u=<id>, parse the profile, return
//     AuthResponseDtoSuccess{user}.
//  2. Response body contains "login-form" → parse the embedded captcha
//     via ParseCaptcha. If body also contains "неверный пароль", emit
//     AuthResponseDtoWrongCredits{captcha: parsed (may be nil)}. Else
//     if captcha was parsed, emit AuthResponseDtoCaptchaRequired{captcha}.
//     Else return ErrUnknown.
//  3. Otherwise → ErrNoData.
//
// The captcha-form fields are forwarded only when all three of CaptchaSid,
// CaptchaCode, CaptchaValue are non-nil (matches Kotlin's null-AND-null-
// AND-null guard). Partial sets are silently dropped — NOT half-submitted.
func (c *Client) Login(ctx context.Context, p LoginParams) (*gen.AuthResponseDto, error) {
	form := url.Values{}
	form.Set("login_username", p.Username)
	form.Set("login_password", p.Password)
	form.Set("login", loginSubmitValue)
	if p.CaptchaSid != nil && p.CaptchaCode != nil && p.CaptchaValue != nil {
		form.Set("cap_sid", *p.CaptchaSid)
		// The captcha-code-name string is itself the form-field name —
		// rutracker rotates this on each captcha render so the field
		// name is per-session. Do NOT collapse to a fixed key.
		form.Set(*p.CaptchaCode, *p.CaptchaValue)
	}

	body, status, headers, err := c.PostFormWithHeaders(ctx, "/login.php", form, "")
	if err != nil {
		return nil, err
	}
	if status >= 500 {
		// Defensive: PostFormWithHeaders already maps 5xx to a breaker
		// error, so this branch is unreachable in practice. Kept so a
		// future relaxation of the breaker-side policy doesn't silently
		// pretend a 502 was a successful login.
		return nil, fmt.Errorf("rutracker: POST /login.php → %d", status)
	}

	if token := ExtractLoginToken(headers); token != "" {
		// Branch 1 — Success. The presence of a non-bb_ssl Set-Cookie is
		// the upstream's only signal that credentials were accepted.
		user, err := c.fetchUserProfile(ctx, token)
		if err != nil {
			return nil, err
		}
		var out gen.AuthResponseDto
		if err := out.FromAuthResponseDtoSuccess(gen.AuthResponseDtoSuccess{
			Type: gen.Success,
			User: *user,
		}); err != nil {
			return nil, err
		}
		return &out, nil
	}

	if !bytes.Contains(body, loginFormMarker) {
		// Branch 3 — neither token nor login-form: the upstream said
		// something LoginUseCase doesn't know how to interpret. Maps to
		// the Kotlin `throw NoData` branch.
		return nil, ErrNoData
	}

	captcha := ParseCaptcha(body)
	if bytes.Contains(body, wrongCreditsMarker) {
		// Branch 2a — WrongCredits. The captcha may be present (rutracker
		// re-renders one alongside the wrong-credits sentence after a
		// few failed attempts) or nil (early failures don't include one).
		var out gen.AuthResponseDto
		if err := out.FromAuthResponseDtoWrongCredits(gen.AuthResponseDtoWrongCredits{
			Type:    gen.WrongCredits,
			Captcha: captcha,
		}); err != nil {
			return nil, err
		}
		return &out, nil
	}
	if captcha == nil {
		// Branch 2c — login-form present, no captcha, no wrong-credits.
		// Maps to the Kotlin `throw Unknown` branch.
		return nil, ErrUnknown
	}
	// Branch 2b — CaptchaRequired. Login form + parseable captcha + no
	// wrong-credits sentence ⇒ the user must solve the captcha and
	// resubmit.
	var out gen.AuthResponseDto
	if err := out.FromAuthResponseDtoCaptchaRequired(gen.AuthResponseDtoCaptchaRequired{
		Type:    gen.CaptchaRequired,
		Captcha: captcha,
	}); err != nil {
		return nil, err
	}
	return &out, nil
}

// CheckAuthorised performs the lightweight authorisation probe used by
// the GET / and GET /index health endpoints. It does a GET /index.php
// with the supplied cookie (which may be empty) and returns IsAuthorised
// over the body bytes.
//
// Divergence from AddComment / AddFavorite: those flows short-circuit
// on cookie == "" with ErrUnauthorized BEFORE issuing any upstream
// request, because they are state-mutating operations that cannot
// proceed anonymously. CheckAuthorised does NOT short-circuit — the
// OpenAPI explicitly defines / and /index as health probes that work
// anonymously: "If absent or empty, upstream rutracker is queried with
// no cookie and the response will typically be `false`." A regression
// that copy-pasted the empty-cookie short-circuit here would change
// observable wire behaviour from 200 + JSON `false` to 401, silently
// breaking the Android client's liveness check.
//
// Upstream 4xx/5xx surface as a generic error: 5xx already trips the
// breaker via Fetch, and 4xx (rare on /index.php — it usually 200s
// regardless of auth state) is mapped to the default writeUpstreamError
// arm by the handler (502).
func (c *Client) CheckAuthorised(ctx context.Context, cookie string) (bool, error) {
	body, status, err := c.Fetch(ctx, "/index.php", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		return false, fmt.Errorf("rutracker: GET /index.php → %d", status)
	}
	return IsAuthorised(body), nil
}

// fetchUserProfile is the two-step port of
// GetCurrentProfileUseCase + GetProfileUseCase. It runs after a
// successful login — the bb_data-style cookie returned by /login.php is
// passed in as `token` AND becomes the `token` field of the resulting
// UserDto (the Android client stores it as the auth token for subsequent
// requests).
//
//  1. GET /index.php with the cookie, parse `#logged-in-username` and
//     extract the `u` query parameter from its href — that's the userId.
//  2. GET /profile.php?mode=viewprofile&u=<userId>, parse:
//       #profile-uname[data-uid]   → UserDto.Id (parity with Kotlin —
//                                    even though we already have userId
//                                    from step 1, the upstream's
//                                    canonical id is data-uid)
//       #avatar-img > img[src]     → UserDto.AvatarUrl
func (c *Client) fetchUserProfile(ctx context.Context, token string) (*gen.UserDto, error) {
	indexBody, status, err := c.Fetch(ctx, "/index.php", token)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET /index.php → %d", status)
	}
	idoc, err := goquery.NewDocumentFromReader(bytes.NewReader(indexBody))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse index.php for user-id: %w", err)
	}
	userID := queryParam(idoc.Find("#logged-in-username"), "u")
	if userID == "" {
		return nil, fmt.Errorf("rutracker: index.php missing #logged-in-username[?u=]")
	}

	pq := url.Values{}
	pq.Set("mode", "viewprofile")
	pq.Set("u", userID)
	profilePath := "/profile.php?" + pq.Encode()
	profileBody, status2, err := c.Fetch(ctx, profilePath, token)
	if err != nil {
		return nil, err
	}
	if status2 >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s → %d", profilePath, status2)
	}
	pdoc, err := goquery.NewDocumentFromReader(bytes.NewReader(profileBody))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse profile.php: %w", err)
	}
	return &gen.UserDto{
		Id:        nodeAttr(pdoc.Find("#profile-uname"), "data-uid"),
		Token:     token,
		AvatarUrl: nodeAttr(pdoc.Find("#avatar-img > img"), "src"),
	}, nil
}
