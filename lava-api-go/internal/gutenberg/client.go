// Package gutenberg implements the Project Gutenberg provider for the Lava
// multi-provider architecture. It consumes the Gutendex JSON API
// (https://gutendex.com) and adapts it to the provider.Provider interface.
package gutenberg

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client is a thin HTTP client for the Gutendex JSON API.
type Client struct {
	base string
	http *http.Client
}

// NewClient returns a Client configured with the given base URL.
// If base is empty it defaults to the public Gutendex endpoint.
func NewClient(base string) *Client {
	if base == "" {
		base = "https://gutendex.com"
	}
	return &Client{
		base: base,
		http: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

func (c *Client) getJSON(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Lava/1.0.0")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode >= 500 {
		return fmt.Errorf("gutenberg upstream %d", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("gutenberg GET %s → %d: %s", path, resp.StatusCode, string(body))
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (c *Client) downloadBytes(ctx context.Context, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Lava/1.0.0")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode >= 500 {
		return nil, fmt.Errorf("gutenberg upstream %d", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("gutenberg download → %d: %s", resp.StatusCode, string(body))
	}
	return io.ReadAll(resp.Body)
}
