package jackett

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
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
// fail. We capture the Location header instead (see Download).
func NewClient(cfg Config) (*Client, error) {
	if strings.TrimSpace(cfg.BaseURL) == "" || strings.TrimSpace(cfg.APIKey) == "" {
		return nil, ErrMissingConfig
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = DefaultTimeout
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	return &Client{
		cfg: cfg,
		http: &http.Client{
			Timeout: cfg.Timeout,
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

func isRedirect(status int) bool {
	switch status {
	case http.StatusMovedPermanently, http.StatusFound,
		http.StatusSeeOther, http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		return true
	default:
		return false
	}
}
