// Package nnmclub is the Lava-domain NNM-Club (nnmclub.to) scraper.
// It wraps an HTTP client with a circuit breaker and exposes typed helpers
// for each provider capability.
package nnmclub

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"digital.vasic.recovery/pkg/breaker"
	"golang.org/x/net/html/charset"
)

// readBodyDecoded reads resp.Body and transcodes it from the upstream's
// declared charset to UTF-8. nnmclub.to serves text/html in windows-1251.
func readBodyDecoded(resp *http.Response) ([]byte, error) {
	ct := resp.Header.Get("Content-Type")
	mt := strings.ToLower(strings.TrimSpace(strings.SplitN(ct, ";", 2)[0]))
	textish := strings.HasPrefix(mt, "text/") || mt == "application/xhtml+xml" || mt == "application/xml" || mt == "application/json"
	if !textish {
		return io.ReadAll(resp.Body)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		return raw, nil
	}
	r, err := charset.NewReader(bytes.NewReader(raw), ct)
	if err != nil {
		return nil, err
	}
	return io.ReadAll(r)
}

// ErrCircuitOpen is returned when the breaker is in OPEN state.
var ErrCircuitOpen = fmt.Errorf("nnmclub: circuit breaker OPEN")

// ErrUnauthorized is returned when the auth cookie is missing or invalid.
var ErrUnauthorized = fmt.Errorf("nnmclub: unauthorized")

// ErrNotFound is returned when the upstream returns a 404 or equivalent.
var ErrNotFound = fmt.Errorf("nnmclub: not found")

// Client is the nnmclub.to HTTP client wrapped with a circuit breaker.
type Client struct {
	base    string
	http    *http.Client
	breaker *breaker.CircuitBreaker
}

// NewClient returns a Client whose breaker trips after 5 consecutive
// failures and stays open for 10s before probing half-open.
func NewClient(base string) *Client {
	return &Client{
		base: base,
		http: &http.Client{
			Timeout: 30 * time.Second,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		breaker: breaker.NewCircuitBreaker(breaker.CircuitBreakerConfig{
			Name:         "nnmclub",
			MaxFailures:  5,
			ResetTimeout: 10 * time.Second,
		}),
	}
}

// Fetch performs a GET against base+path with the given cookie value.
func (c *Client) Fetch(ctx context.Context, path, cookie string) ([]byte, int, error) {
	var body []byte
	var status int
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
		if err != nil {
			return err
		}
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := readBodyDecoded(resp)
		if err != nil {
			return err
		}
		body = b
		status = resp.StatusCode
		if resp.StatusCode >= 500 {
			return fmt.Errorf("nnmclub upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, err
	}
	return body, status, nil
}

// FetchWithHeaders behaves like Fetch but additionally returns response headers.
func (c *Client) FetchWithHeaders(ctx context.Context, path, cookie string) ([]byte, int, http.Header, error) {
	var body []byte
	var status int
	var headers http.Header
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
		if err != nil {
			return err
		}
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := readBodyDecoded(resp)
		if err != nil {
			return err
		}
		body = b
		status = resp.StatusCode
		headers = resp.Header.Clone()
		if resp.StatusCode >= 500 {
			return fmt.Errorf("nnmclub upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, nil, err
	}
	return body, status, headers, nil
}

// PostForm performs a POST against base+path with the given form values.
func (c *Client) PostForm(ctx context.Context, path string, form url.Values, cookie string) ([]byte, int, error) {
	var body []byte
	var status int
	encoded := form.Encode()
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := readBodyDecoded(resp)
		if err != nil {
			return err
		}
		body = b
		status = resp.StatusCode
		if resp.StatusCode >= 500 {
			return fmt.Errorf("nnmclub upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, err
	}
	return body, status, nil
}

// PostFormWithHeaders behaves like PostForm but additionally returns response headers.
func (c *Client) PostFormWithHeaders(ctx context.Context, path string, form url.Values, cookie string) ([]byte, int, http.Header, error) {
	var body []byte
	var status int
	var headers http.Header
	encoded := form.Encode()
	err := c.breaker.Execute(func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path, strings.NewReader(encoded))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		defer func() { _ = resp.Body.Close() }()
		b, err := readBodyDecoded(resp)
		if err != nil {
			return err
		}
		body = b
		status = resp.StatusCode
		headers = resp.Header.Clone()
		if resp.StatusCode >= 500 {
			return fmt.Errorf("nnmclub upstream %d", resp.StatusCode)
		}
		return nil
	})
	if err != nil {
		return nil, 0, nil, err
	}
	return body, status, headers, nil
}
