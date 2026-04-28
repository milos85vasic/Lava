// Package handlers — login.go implements POST /login, the rutracker
// login endpoint exposed as a JSON-returning façade over the scraper's
// three-state Auth resolution tree (Success / WrongCredits /
// CaptchaRequired).
//
// Wire shape (per api/openapi.yaml /paths/~1login):
//
//   - Body: application/x-www-form-urlencoded with required `username`
//     and `password` fields, plus optional captcha-set `cap_sid`,
//     `cap_code`, `cap_val`. The form-decoding mirrors Ktor's
//     `call.receiveParameters()` shape used by the legacy proxy.
//   - 200 → discriminated `AuthResponseDto` (Success | WrongCredits |
//     CaptchaRequired). The `type` discriminator field carries the
//     variant name (`Success` / `WrongCredits` / `CaptchaRequired`),
//     mirroring the Kotlin sealed class @SerialName.
//   - 400 → missing required form parameter. Returned WITHOUT any
//     upstream traffic — a malformed request is not the upstream's
//     fault, so the breaker MUST NOT see it.
//
// The all-three-or-none captcha guard is enforced INSIDE the scraper's
// rutracker.Login (LoginParams pointers): if any of the three captcha
// fields is nil, ALL three are dropped from the form body. This
// handler converts empty form values to nil so the guard fires
// correctly when the client sends `cap_sid=&cap_code=&cap_val=` (Ktor
// would treat the same input as "captcha not provided"; we must not
// silently submit empty captcha strings to rutracker).
//
// Cache policy: NO cache. Login is a state-mutating operation against
// the upstream's session store — the response includes a freshly-
// minted auth cookie on success, and replaying a cached "Success"
// would be a security incident. Identical reasoning applies at the
// Ktor proxy.
package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/rutracker"
)

// loginRouteTemplate is documentation-grade — POST /login does not
// read or write the cache, so this constant does not feed cache.Key.
const loginRouteTemplate = "/login"

// AuthHandler owns POST /login. The name (`AuthHandler`, not
// `LoginHandler`) keeps room for additional auth-related routes if a
// future spec revision adds them; today it has exactly one method.
type AuthHandler struct {
	scraper ScraperClient
}

// NewAuthHandler is the constructor injected with the shared Deps.
// Cache is intentionally NOT held — see the file-level comment.
func NewAuthHandler(deps *Deps) *AuthHandler {
	return &AuthHandler{scraper: deps.Scraper}
}

// PostLogin implements POST /login. Parses an
// application/x-www-form-urlencoded body, builds a rutracker.LoginParams
// (with the all-three-or-none captcha-field guard converted to *string
// nilability), invokes the scraper's Login method, and returns the
// JSON-marshalled AuthResponseDto verbatim.
//
//	Missing username or password → 400 Bad Request, no upstream traffic.
//	Scraper error → writeUpstreamError (502 by default; ErrCircuitOpen
//	                → 503; the 4xx sentinels are not produced by Login
//	                in practice but the mapping handles them for free).
//	Scraper success → 200 OK + JSON of *gen.AuthResponseDto.
func (h *AuthHandler) PostLogin(c *gin.Context) {
	if err := c.Request.ParseForm(); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "parse form: " + err.Error()})
		return
	}

	username := c.PostForm("username")
	password := c.PostForm("password")
	if username == "" || password == "" {
		// Reject early — a malformed client request must NOT reach
		// the upstream. Mirrors Sixth Law clause 1: zero upstream
		// traffic on bad input is itself a user-visible behaviour
		// (the breaker can never trip on client-induced badness).
		c.JSON(http.StatusBadRequest, gin.H{"error": "username and password required"})
		return
	}

	// Optional captcha fields. Empty string means "absent" per the
	// rutracker scraper's all-three-or-none guard (rutracker rejects
	// partial captcha submissions, so we MUST drop the whole set when
	// any of the three is missing — the guard fires on nil pointers,
	// hence the empty-→-nil conversion here).
	var capSid, capCode, capVal *string
	if v := c.PostForm("cap_sid"); v != "" {
		capSid = &v
	}
	if v := c.PostForm("cap_code"); v != "" {
		capCode = &v
	}
	if v := c.PostForm("cap_val"); v != "" {
		capVal = &v
	}

	params := rutracker.LoginParams{
		Username:     username,
		Password:     password,
		CaptchaSid:   capSid,
		CaptchaCode:  capCode,
		CaptchaValue: capVal,
	}

	resp, err := h.scraper.Login(c.Request.Context(), params)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}

	c.JSON(http.StatusOK, resp)
}
