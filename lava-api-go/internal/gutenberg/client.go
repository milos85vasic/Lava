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

	"digital.vasic.lava.apigo/internal/httpx"
)

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (a network/timeout error from http.Do, or a 5xx status). Gutendex live latency
// is occasionally variable and a single transient blip otherwise surfaces to the
// user as a failed search/download. A bounded retry converts that transient
// failure into a successful request. Terminal errors (4xx, decode) are NEVER
// retried — retrying them would only waste the budget. The retry budget is
// bounded by the caller's ctx (each backoff aborts early if ctx is done).
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
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
			// Route egress through the configurable outbound proxy
			// (LAVA_API_UPSTREAM_PROXY / *_PROXY env) — see internal/httpx.
			Transport: httpx.NewTransport(),
		},
	}
}

// doWithRetry runs once per attempt, retrying up to maxAttempts on TRANSIENT
// failures. once returns (transient, err): transient=true means the failure is
// worth retrying (network/timeout from http.Do, or a 5xx). On success once
// returns (false, nil). Terminal errors (transient=false) return immediately.
// Each backoff is bounded by ctx.
func doWithRetry(ctx context.Context, once func() (transient bool, err error)) error {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		transient, err := once()
		if err == nil {
			return nil
		}
		lastErr = err
		if !transient || attempt == maxAttempts {
			return err
		}
		select {
		case <-time.After(retryBackoff):
			// next attempt
		case <-ctx.Done():
			return lastErr
		}
	}
	return lastErr
}

func (c *Client) getJSON(ctx context.Context, path string, out any) error {
	return doWithRetry(ctx, func() (bool, error) {
		return c.getJSONOnce(ctx, path, out)
	})
}

// getJSONOnce performs a single fetch+decode. The bool reports whether the error
// is transient (worth retrying): true for a network/timeout error from http.Do
// or a 5xx status; false for terminal errors (other non-200, decode) and success.
func (c *Client) getJSONOnce(ctx context.Context, path string, out any) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Lava/1.0.0")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient.
		return true, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode >= 500 {
		// 5xx is transient.
		return true, fmt.Errorf("gutenberg upstream %d", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return false, fmt.Errorf("gutenberg GET %s → %d: %s", path, resp.StatusCode, string(body))
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return false, err
	}
	return false, nil
}

func (c *Client) downloadBytes(ctx context.Context, url string) ([]byte, error) {
	var data []byte
	err := doWithRetry(ctx, func() (bool, error) {
		transient, b, err := c.downloadBytesOnce(ctx, url)
		if err != nil {
			return transient, err
		}
		data = b
		return false, nil
	})
	if err != nil {
		return nil, err
	}
	return data, nil
}

// downloadBytesOnce performs a single download. The bool reports whether the
// error is transient (network/timeout from http.Do, or a 5xx); false for
// terminal errors (other non-200) and success.
func (c *Client) downloadBytesOnce(ctx context.Context, url string) (bool, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false, nil, err
	}
	req.Header.Set("User-Agent", "Lava/1.0.0")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient.
		return true, nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode >= 500 {
		// 5xx is transient.
		return true, nil, fmt.Errorf("gutenberg upstream %d", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return false, nil, fmt.Errorf("gutenberg download → %d: %s", resp.StatusCode, string(body))
	}
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		// A read error mid-body is transient (network).
		return true, nil, err
	}
	return false, b, nil
}
