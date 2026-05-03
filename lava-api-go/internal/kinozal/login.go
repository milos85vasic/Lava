package kinozal

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"digital.vasic.lava.apigo/internal/provider"
)

// extractAuthToken returns the concatenated uid + pass cookies from the
// Set-Cookie headers, or the empty string if neither is present.
func extractAuthToken(headers http.Header) string {
	var parts []string
	for _, c := range headers.Values("Set-Cookie") {
		if strings.Contains(c, "uid=") || strings.Contains(c, "pass=") {
			nameVal := strings.TrimSpace(strings.SplitN(c, ";", 2)[0])
			parts = append(parts, nameVal)
		}
	}
	if len(parts) == 0 {
		return ""
	}
	return strings.Join(parts, "; ")
}

// Login posts /takelogin.php and returns a LoginResult when the upstream
// responds with uid/pass cookies.
func (c *Client) Login(ctx context.Context, username, password string) (*provider.LoginResult, error) {
	form := url.Values{}
	form.Set("username", username)
	form.Set("password", password)
	form.Set("returnto", "%2F")

	body, status, headers, err := c.PostForm(ctx, "/takelogin.php", form, "")
	if err != nil {
		return nil, err
	}
	if status >= 500 {
		return nil, fmt.Errorf("kinozal: POST /takelogin.php → %d", status)
	}

	token := extractAuthToken(headers)
	if token != "" {
		return &provider.LoginResult{
			Success:   true,
			AuthToken: token,
		}, nil
	}

	if strings.Contains(string(body), "Неверный логин") ||
		strings.Contains(string(body), "неверный пароль") ||
		strings.Contains(string(body), "error") {
		return nil, provider.ErrUnauthorized
	}

	return nil, ErrNoData
}

// CheckAuth performs a lightweight probe by checking whether a uid cookie
// is present. A more robust check would inspect the home page for a logout
// link, but the cookie presence is sufficient for the provider contract.
func (c *Client) CheckAuth(ctx context.Context, cookie string) (bool, error) {
	if cookie == "" {
		return false, nil
	}
	_, status, err := c.Fetch(ctx, "/", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		return false, fmt.Errorf("kinozal: GET / → %d", status)
	}
	return true, nil
}
