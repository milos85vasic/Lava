package nnmclub

import (
	"bytes"
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// loginSubmitValue is the Russian "Вход" literal used by the NNM-Club login form.
const loginSubmitValue = "Вход"

// IsAuthorised returns true if the html contains a logged-in marker.
// It looks for a "logout" link or a user-profile link.
func IsAuthorised(html []byte) bool {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
		return false
	}
	// Presence of a logout link is the primary marker.
	logout := doc.Find("a[href*=logout]").First()
	if logout.Length() > 0 {
		return true
	}
	// Also accept a user-profile link that isn't the login page.
	if doc.Find("a[href*=login.php]").Length() == 0 && doc.Find("a[href*=profile.php]").Length() > 0 {
		return true
	}
	return false
}

// Login posts /forum/login.php with username and password.
// It returns a LoginResult when the response headers contain a session cookie.
func (c *Client) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	form := url.Values{}
	form.Set("username", opts.Username)
	form.Set("password", opts.Password)
	form.Set("login", loginSubmitValue)

	body, status, headers, err := c.PostFormWithHeaders(ctx, "/forum/login.php", form, "")
	if err != nil {
		return nil, err
	}
	if status >= 500 {
		return nil, fmt.Errorf("nnmclub: POST /forum/login.php → %d", status)
	}

	// Check for session cookie in Set-Cookie headers.
	var sessionToken string
	for _, c := range headers.Values("Set-Cookie") {
		if strings.Contains(c, "phpbb2mysql_4") || strings.Contains(c, "phpbb2mysql_4_sid") || strings.Contains(c, "phpbb2mysql_4_data") {
			parts := strings.SplitN(c, ";", 2)
			if len(parts) > 0 {
				sessionToken = strings.TrimSpace(parts[0])
				break
			}
		}
	}

	// If no cookie but body shows we're logged in, still consider success.
	if sessionToken == "" && IsAuthorised(body) {
		return &provider.LoginResult{Success: true}, nil
	}

	if sessionToken == "" {
		return nil, provider.ErrUnauthorized
	}

	return &provider.LoginResult{
		Success:   true,
		AuthToken: sessionToken,
	}, nil
}

// CheckAuth performs a lightweight authorisation probe.
func (c *Client) CheckAuth(ctx context.Context, cookie string) (bool, error) {
	body, status, err := c.Fetch(ctx, "/forum/index.php", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		return false, fmt.Errorf("nnmclub: GET /forum/index.php → %d", status)
	}
	return IsAuthorised(body), nil
}
