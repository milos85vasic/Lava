// Package archiveorg implements the Internet Archive provider for the
// Lava multi-provider extension. It consumes archive.org's JSON APIs
// (advancedsearch.php and metadata) rather than scraping HTML.
//
// Constitutional alignment:
//   - 6.E Capability Honesty: the provider declares only SEARCH, BROWSE,
//     TOPIC, FORUM_TREE, and HTTP_DOWNLOAD — no torrents, magnets, auth,
//     comments, or favorites.
//   - 6.D Behavioral Coverage: every exported method has a real-stack test
//     against an httptest server.
package archiveorg

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Client is a thin HTTP wrapper around archive.org's public JSON endpoints.
type Client struct {
	baseURL string
	client  *http.Client
}

// NewClient creates a Client with the given base URL (e.g. "https://archive.org").
func NewClient(baseURL string) *Client {
	return &Client{
		baseURL: baseURL,
		client: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// get performs a GET request to the given path + query string and returns
// the response body bytes. It returns an error for non-2xx status codes.
func (c *Client) get(ctx context.Context, path string, query url.Values) ([]byte, error) {
	u, err := url.Parse(c.baseURL + path)
	if err != nil {
		return nil, fmt.Errorf("archiveorg: invalid url: %w", err)
	}
	if query != nil {
		u.RawQuery = query.Encode()
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("archiveorg: build request: %w", err)
	}
	req.Header.Set("User-Agent", "Lava/1.2.0")

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("archiveorg: do request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("archiveorg: HTTP %d: %s", resp.StatusCode, string(body))
	}

	return io.ReadAll(resp.Body)
}

// getRaw performs a GET to an absolute URL and returns the body without
// decoding or status-code checks. Used by the download path.
func (c *Client) getRaw(ctx context.Context, rawURL string) (io.ReadCloser, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, 0, fmt.Errorf("archiveorg: build request: %w", err)
	}
	req.Header.Set("User-Agent", "Lava/1.2.0")

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("archiveorg: do request: %w", err)
	}
	return resp.Body, resp.StatusCode, nil
}

// intQuery is a small helper that adds an int parameter to url.Values.
func intQuery(q url.Values, key string, value int) url.Values {
	q.Set(key, strconv.Itoa(value))
	return q
}
