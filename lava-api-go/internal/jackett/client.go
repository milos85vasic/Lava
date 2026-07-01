package jackett

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/httpx"
)

// Config carries the Jackett connection parameters. Both fields are injected
// at runtime (§6.R): BaseURL from the Containers glue / env, APIKey read from
// the gitignored /config/ServerConfig.json host volume. Neither is a literal
// anywhere in tracked source.
type Config struct {
	// BaseURL is the Jackett base, e.g. "http://jackett:9117" (no trailing
	// slash required; the client trims one if present).
	BaseURL string
	// APIKey is the Jackett api_key (§6.H secret — server-side only).
	APIKey string
	// AdminPassword is the Jackett dashboard admin password (§6.H secret —
	// server-side only). Empty by default. Used ONLY for the cookie-session
	// login that the MANAGEMENT API requires when the dashboard is password
	// protected (see login). Torznab feeds never use it.
	AdminPassword string
	// Timeout bounds each request; zero selects DefaultTimeout.
	Timeout time.Duration
}

// DefaultTimeout is used when Config.Timeout is zero.
const DefaultTimeout = 30 * time.Second

// Torznab path components. These are protocol path segments defined by the
// Torznab/Jackett API surface (not deployment-specific addresses), so they
// are package constants rather than config — the §6.R no-hardcoding rule
// targets connection addresses/ports/secrets, which live in Config.
const (
	torznabResultsPath = "/api/v2.0/indexers/%s/results/torznab/api"

	// dashboardLoginPath is the Jackett dashboard login endpoint. A POST with
	// the `password` form field establishes the session cookie the MANAGEMENT
	// API requires when the dashboard is password protected. This is a protocol
	// path segment (not a deployment address), so it is a package constant; the
	// §6.R no-hardcoding rule targets addresses/ports/secrets, which live in
	// Config.
	dashboardLoginPath = "/UI/Dashboard"

	// loginPasswordField is the form field Jackett's dashboard login reads.
	loginPasswordField = "password"

	// IndexerAll queries every configured indexer at once.
	IndexerAll = "all"

	// Torznab "t" function values.
	funcSearch = "search"
	funcCaps   = "caps"
)

// ErrMissingConfig is returned when BaseURL or APIKey is empty.
var ErrMissingConfig = errors.New("jackett: BaseURL and APIKey are required")

// Client talks to a Jackett sidecar over Torznab. lava-api-go holds it
// server-side; the Android app never sees Jackett directly.
type Client struct {
	cfg  Config
	http *http.Client
}

// NewClient constructs a Client. The HTTP client deliberately does NOT follow
// redirects: Jackett answers download links with HTTP 302 whose Location is a
// magnet: URI, and an auto-following client would try to "GET magnet:" and
// fail. We capture the Location header instead (see Download). The 302→login
// edge case on the MANAGEMENT API (see getManagement) is also handled by
// inspecting the status rather than following.
//
// The client carries its OWN cookie jar (local to this Jackett client — it is
// NOT the global/shared HTTP transport): the dashboard session cookie acquired
// by login is stored here and replayed automatically on subsequent management
// calls.
func NewClient(cfg Config) (*Client, error) {
	if strings.TrimSpace(cfg.BaseURL) == "" || strings.TrimSpace(cfg.APIKey) == "" {
		return nil, ErrMissingConfig
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = DefaultTimeout
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, fmt.Errorf("jackett: cookie jar: %w", err)
	}
	return &Client{
		cfg: cfg,
		http: &http.Client{
			Timeout: cfg.Timeout,
			Jar:     jar,
			// Route egress through the configurable outbound proxy
			// (LAVA_API_UPSTREAM_PROXY / *_PROXY env) — see internal/httpx.
			Transport: httpx.NewTransport(),
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

// BuildSearchURL constructs the Torznab search request URL for an indexer:
//
//	<base>/api/v2.0/indexers/<indexerID>/results/torznab/api?apikey=&t=search&q=
//
// The apikey and base come from Config; nothing is hardcoded.
func (c *Client) BuildSearchURL(indexerID, query string) string {
	return c.buildURL(indexerID, funcSearch, url.Values{"q": {query}})
}

// BuildCapsURL constructs the Torznab caps (capabilities) request URL, used as
// the §6.B readiness probe for an indexer.
func (c *Client) BuildCapsURL(indexerID string) string {
	return c.buildURL(indexerID, funcCaps, nil)
}

func (c *Client) buildURL(indexerID, fn string, extra url.Values) string {
	path := fmt.Sprintf(torznabResultsPath, url.PathEscape(indexerID))
	v := url.Values{}
	v.Set("apikey", c.cfg.APIKey)
	v.Set("t", fn)
	for key, vals := range extra {
		for _, val := range vals {
			v.Add(key, val)
		}
	}
	return c.cfg.BaseURL + path + "?" + v.Encode()
}

// Search performs a Torznab t=search query against the given indexer and
// returns the parsed results.
func (c *Client) Search(ctx context.Context, indexerID, query string) ([]Result, error) {
	reqURL := c.BuildSearchURL(indexerID, query)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("jackett: search request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("jackett: search status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	return ParseResults(body)
}

// DownloadResult holds the outcome of resolving a Torznab download link.
// Exactly one of Magnet / TorrentBytes is populated (Magnet for the 302→magnet
// edge case, TorrentBytes when the link served a real .torrent file).
type DownloadResult struct {
	// Magnet is set when the download link resolved to a magnet URI — either
	// the enclosure was already a magnet, or following the /dl/ link produced
	// an HTTP 302 whose Location is a magnet: URI.
	Magnet string
	// TorrentBytes is the raw .torrent payload when the link served a file.
	TorrentBytes []byte
	// ContentType echoes the response Content-Type for a served .torrent.
	ContentType string
}

// IsMagnet reports whether the download resolved to a magnet link.
func (d DownloadResult) IsMagnet() bool { return d.Magnet != "" }

// Download resolves a Torznab item's download link WITHOUT auto-following
// redirects. The documented Jackett edge case is HTTP 302 with a
// "Location: magnet:..." header; this method captures that Location instead of
// trying to GET the magnet scheme. If the enclosure URL is already a magnet,
// it short-circuits. Otherwise a 2xx response body is returned as the .torrent
// bytes.
func (c *Client) Download(ctx context.Context, downloadURL string) (*DownloadResult, error) {
	if strings.TrimSpace(downloadURL) == "" {
		return nil, errors.New("jackett: empty download url")
	}
	// Enclosure already a magnet — no HTTP needed.
	if strings.HasPrefix(downloadURL, "magnet:") {
		return &DownloadResult{Magnet: downloadURL}, nil
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("jackett: download request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	// 302 → magnet edge case: capture the Location header verbatim instead of
	// following it. CheckRedirect (ErrUseLastResponse) guarantees we land here
	// rather than auto-following into a "GET magnet:" failure.
	if isRedirect(resp.StatusCode) {
		loc := resp.Header.Get("Location")
		if strings.HasPrefix(loc, "magnet:") {
			return &DownloadResult{Magnet: loc}, nil
		}
		return nil, fmt.Errorf("jackett: download redirected to non-magnet location %q", loc)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("jackett: download status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	return &DownloadResult{
		TorrentBytes: body,
		ContentType:  resp.Header.Get("Content-Type"),
	}, nil
}

// getManagement performs a GET against a Jackett MANAGEMENT endpoint (the
// configured-indexers enumeration, an indexer's /config). Unlike the Torznab
// feeds — which authenticate by apikey alone — the management API requires the
// dashboard SESSION COOKIE whenever the dashboard is password protected:
// apikey alone is answered with an HTTP 302 redirect to /UI/Login.
//
// Strategy (covers BOTH real-Jackett deployments):
//   - Unprotected dashboard → the first request returns 200 directly; no login.
//   - Protected dashboard → the first request returns 302 → we run the dashboard
//     login to acquire the session cookie (stored in the client jar) and retry
//     once. The cookie is reused automatically by the jar for later calls.
func (c *Client) getManagement(ctx context.Context, reqURL string) ([]byte, error) {
	body, status, err := c.doManagementGet(ctx, reqURL)
	if err != nil {
		return nil, err
	}
	if isRedirect(status) {
		// Session cookie missing/expired → authenticate and retry once.
		if lerr := c.login(ctx); lerr != nil {
			return nil, lerr
		}
		body, status, err = c.doManagementGet(ctx, reqURL)
		if err != nil {
			return nil, err
		}
	}
	if status != http.StatusOK {
		return nil, fmt.Errorf("jackett: management request status %d", status)
	}
	return body, nil
}

// doManagementGet issues a single GET and returns the body + status. Redirects
// are NOT followed (the client's CheckRedirect returns ErrUseLastResponse), so a
// 302→/UI/Login surfaces here as status 302 for getManagement to act on.
func (c *Client) doManagementGet(ctx context.Context, reqURL string) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, 0, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("jackett: management request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	return body, resp.StatusCode, nil
}

// login establishes the Jackett dashboard session cookie by POSTing the admin
// password to /UI/Dashboard. The acquired Set-Cookie is captured by the client's
// cookie jar (net/http stores response cookies before applying CheckRedirect, so
// this works whether Jackett answers the login with 200 or a 302 back to the
// dashboard). An unprotected Jackett (empty admin password) still issues a
// session cookie for the empty password.
//
// login is used ONLY for the management API; the Torznab results/caps/download
// paths authenticate by apikey and never call this.
func (c *Client) login(ctx context.Context) error {
	form := url.Values{}
	form.Set(loginPasswordField, c.cfg.AdminPassword)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.cfg.BaseURL+dashboardLoginPath, strings.NewReader(form.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("jackett: dashboard login: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, resp.Body)

	// A 401/403 is an explicit rejection (wrong admin password).
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("jackett: dashboard login rejected (status %d) — check %s", resp.StatusCode, "LAVA_API_JACKETT_ADMIN_PASSWORD")
	}

	// Behavioral safety net: real Jackett re-renders the login page with HTTP
	// 200 and NO Set-Cookie when the password is wrong. Treat "no session cookie
	// established" as a login failure so a later management call does not loop on
	// 302 with a confusing status error.
	if base, perr := url.Parse(c.cfg.BaseURL); perr == nil && c.http.Jar != nil {
		if len(c.http.Jar.Cookies(base)) == 0 {
			return errors.New("jackett: dashboard login did not establish a session cookie — check LAVA_API_JACKETT_ADMIN_PASSWORD")
		}
	}
	return nil
}

func isRedirect(status int) bool {
	switch status {
	case http.StatusMovedPermanently, http.StatusFound,
		http.StatusSeeOther, http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		return true
	default:
		return false
	}
}
