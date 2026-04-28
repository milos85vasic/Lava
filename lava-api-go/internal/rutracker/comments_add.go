// Package rutracker — comments_add.go ports the three-step
// "post a comment" flow from Kotlin:
//
//   - VerifyTokenUseCase.kt        → empty-cookie short-circuit.
//   - VerifyAuthorisedUseCase.kt   → IsAuthorised (logged-in-username).
//   - WithFormTokenUseCase.kt      → ParseFormToken (regex).
//   - AddCommentUseCase.kt         → AddComment orchestrator.
//   - RuTrackerInnerApiImpl.kt     → mainPage (GET /index.php with
//                                    cookie) + postMessage (POST
//                                    /posting.php form data).
//
// The Russian success sentence "Сообщение было успешно отправлено" is
// the byte-equal literal from AddCommentUseCase.kt:18 — it is the only
// way the upstream signals acceptance; absence ⇒ silent rejection
// (flood-control, slow-mode, etc.).
package rutracker

import (
	"bytes"
	"context"
	"net/url"
	"regexp"
)

// formTokenRegex mirrors Kotlin's `Pattern.compile("form_token: '(.*)',")`.
// The `.*` is greedy by design (parity with Java/Kotlin) — if multiple
// `form_token: '...',` lines ever appeared on the same page, this would
// span across them. In practice rutracker emits exactly one. Do not
// "fix" the greediness: the legacy proxy depends on the same behaviour.
var formTokenRegex = regexp.MustCompile(`form_token: '(.*)',`)

// loggedInMarker is the substring VerifyAuthorisedUseCase.kt looks for
// in the rutracker main-page HTML to confirm the cookie is still valid.
var loggedInMarker = []byte("logged-in-username")

// commentAcceptedSentence is the byte-equal Russian success sentence
// from AddCommentUseCase.kt:18. The presence of this sentence in the
// /posting.php response body is the ONLY signal that rutracker accepted
// the comment; absence means the comment was silently rejected
// (flood-control, slow-mode, low-rep, etc.) and the caller maps it to
// JSON `false`.
var commentAcceptedSentence = []byte("Сообщение было успешно отправлено")

// ParseFormToken extracts the rutracker.org `form_token: '...'` value
// from the main-page HTML. Returns ("", false) when the regex does not
// match or the captured group is empty.
//
// Ports WithFormTokenUseCase.parseFormToken from
// core/network/rutracker/.../domain/WithFormTokenUseCase.kt.
func ParseFormToken(html []byte) (string, bool) {
	m := formTokenRegex.FindSubmatch(html)
	if m == nil {
		return "", false
	}
	if len(m[1]) == 0 {
		return "", false
	}
	return string(m[1]), true
}

// IsAuthorised returns true iff the rutracker main-page HTML contains
// the "logged-in-username" marker (the only signal the legacy proxy
// uses).
//
// Ports VerifyAuthorisedUseCase.invoke from
// core/network/rutracker/.../domain/VerifyAuthorisedUseCase.kt.
func IsAuthorised(html []byte) bool {
	return bytes.Contains(html, loggedInMarker)
}

// AddComment performs the three-step flow that posts a comment to
// rutracker.org topic `topicID`:
//
//  1. fetch /index.php with the supplied cookie and verify the response
//     contains "logged-in-username" (else ErrUnauthorized).
//  2. extract `form_token: '...'` from the index page (else
//     ErrUnauthorized — rutracker rotates this on every page load).
//  3. POST /posting.php with form data
//     {mode=reply, submit_mode=submit, t=<topicID>, form_token=<token>,
//     message=<message>}.
//
// Returns true when the upstream response contains the Russian success
// sentence ("Сообщение было успешно отправлено"). False indicates the
// upstream silently rejected the post (flood-control, slow-mode, etc.).
//
// `cookie` MUST be non-empty — an empty cookie short-circuits to
// ErrUnauthorized without any upstream traffic, matching the legacy
// VerifyTokenUseCase pre-check.
func (c *Client) AddComment(ctx context.Context, topicID, message, cookie string) (bool, error) {
	// Step 1 — VerifyTokenUseCase: empty cookie ⇒ Unauthorized, no
	// upstream traffic.
	if cookie == "" {
		return false, ErrUnauthorized
	}

	// Step 2 — fetch /index.php with the cookie, then check for the
	// "logged-in-username" marker AND extract the rotating form_token.
	body, status, err := c.Fetch(ctx, "/index.php", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		// 4xx on the main page is the upstream telling us the cookie is
		// no longer accepted. Map to ErrUnauthorized so the handler can
		// return 401, matching the Kotlin Unauthorized exception path.
		return false, ErrUnauthorized
	}
	if !IsAuthorised(body) {
		return false, ErrUnauthorized
	}
	formToken, ok := ParseFormToken(body)
	if !ok {
		return false, ErrUnauthorized
	}

	// Step 3 — POST /posting.php with the form data shape from
	// RuTrackerInnerApiImpl.postMessage.
	form := url.Values{}
	form.Set("mode", "reply")
	form.Set("submit_mode", "submit")
	form.Set("t", topicID)
	form.Set("form_token", formToken)
	form.Set("message", message)

	respBody, postStatus, err := c.PostForm(ctx, "/posting.php", form, cookie)
	if err != nil {
		return false, err
	}
	if postStatus >= 400 {
		return false, ErrUnauthorized
	}
	return bytes.Contains(respBody, commentAcceptedSentence), nil
}
