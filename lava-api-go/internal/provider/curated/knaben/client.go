// Package knaben is a curated, compiled-in provider for the Knaben meta-search
// aggregator, sourced from its public api.knaben.org JSON API (anonymous, no
// Cloudflare challenge). It is part of the embedded-curated-providers feature
// (Defect B): each popular public tracker/aggregator is a Go provider.Provider
// compiled into lava-api-go so the on-device api-app's GET /providers catalogue
// exposes it with SEARCH + MAGNET_LINK and zero external Jackett dependency.
//
// §6.E + EZTV-LESSON honesty: Knaben genuinely filters ONLY when the request
// body is the MINIMAL {"query","size"} form. The documented `search_type:"score"`
// variant is a silent no-op (returns an unfiltered global list — the exact EZTV
// bug class). This client therefore sends ONLY query+size and a unit test guards
// that the request body never carries search_type. Knaben is a meta-aggregator,
// so it reaches many upstream trackers (including Cloudflare-gated ones we cannot
// hit directly) and returns a pre-built hash for each hit.
package knaben

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/httpx"
	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the Knaben API base. api.knaben.eu 301-redirects to
// api.knaben.org, so the .org host is pinned. §6.R: not a deployment address —
// it is this provider's fixed upstream.
const DefaultBaseURL = "https://api.knaben.org"

// searchPath is the Knaben v1 search endpoint (POST, JSON body).
const searchPath = "/v1"

// defaultSize is how many hits we request per query.
const defaultSize = 50

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (network/timeout, or a 5xx). Knaben's meta-aggregation fans out to many
// upstreams so its live latency is variable and occasionally exceeds a single
// DefaultTimeout window → a user-facing "unknown error". A bounded retry converts
// that transient slowness into a successful search. Terminal errors (404 →
// ErrNotFound, 401/403 → ErrForbidden, decode failure) are NEVER retried —
// retrying them would only waste the budget.
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
)

// publicTrackers are well-known public BitTorrent tracker announce URLs added to
// every built magnet. Protocol data (the public-tracker commons), not a
// deployment address — package constants (§6.R), identical to thepiratebay.
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// Client talks to the Knaben JSON API. The base URL + *http.Client are
// injectable so tests drive it against an httptest.Server (no live calls in the
// default `go test`).
type Client struct {
	baseURL string
	http    *http.Client
}

// NewClient returns a Client for the given base URL (DefaultBaseURL when empty)
// with a default-timeout HTTP client.
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: DefaultTimeout, Transport: httpx.NewTransport()},
	}
}

// searchRequest is the MINIMAL request body. It deliberately omits search_type:
// the `search_type:"score"` variant is a silent no-op filter (the EZTV bug
// class). Keeping this struct minimal makes reintroducing the bug a visible,
// test-guarded change.
type searchRequest struct {
	Query string `json:"query"`
	Size  int    `json:"size"`
}

// apiResponse mirrors the Knaben /v1 envelope.
type apiResponse struct {
	Hits []apiHit `json:"hits"`
}

type apiHit struct {
	Title   string `json:"title"`
	Hash    string `json:"hash"`
	Bytes   int64  `json:"bytes"`
	Seeders int    `json:"seeders"`
	Peers   int    `json:"peers"`
	Date    string `json:"date"`
	Cat     string `json:"category"`
}

// Search queries Knaben and maps the hits to provider.SearchItem, building a
// magnet link from each hit's hash. Knaben returns the result set in one
// response (no client-side paging here), so page is accepted for interface
// compatibility but only page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	payload, err := json.Marshal(searchRequest{Query: query, Size: defaultSize})
	if err != nil {
		return nil, fmt.Errorf("%s: marshal request: %w", providerID, err)
	}

	body, err := c.fetchHits(ctx, payload)
	if err != nil {
		return nil, err
	}

	items := make([]provider.SearchItem, 0, len(body.Hits))
	for _, h := range body.Hits {
		hash := strings.ToLower(strings.TrimSpace(h.Hash))
		// Skip any hit without a usable 40-hex v1 info_hash (anti-bluff: only
		// real, downloadable rows reach the user's provider list).
		if len(hash) != 40 {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      h.Title,
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, h.Title),
			SizeBytes:  h.Bytes,
			Seeders:    h.Seeders,
			Leechers:   h.Peers,
			Date:       normalizeDate(h.Date),
			Category:   h.Cat,
		})
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// fetchHits POSTs the payload + decodes the response, retrying up to maxAttempts
// on TRANSIENT failures (network/timeout from http.Do, or a 5xx). Terminal errors
// (404/403/401/decode) return immediately. The retry budget is bounded by the
// caller's ctx — each backoff aborts early if ctx is cancelled/expired.
func (c *Client) fetchHits(ctx context.Context, payload []byte) (*apiResponse, error) {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		body, transient, err := c.fetchHitsOnce(ctx, payload)
		if err == nil {
			return body, nil
		}
		lastErr = err
		if !transient || attempt == maxAttempts {
			return nil, err
		}
		select {
		case <-time.After(retryBackoff):
			// next attempt
		case <-ctx.Done():
			return nil, lastErr
		}
	}
	return nil, lastErr
}

// fetchHitsOnce performs a single POST+decode. The bool reports whether the error
// is transient (worth retrying): true for a network/timeout error or a 5xx
// status; false for terminal errors (404/403/401/decode) and on success.
func (c *Client) fetchHitsOnce(ctx context.Context, payload []byte) (*apiResponse, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+searchPath, bytes.NewReader(payload))
	if err != nil {
		return nil, false, fmt.Errorf("%s: build request: %w", providerID, err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient (the slow-aggregation case).
		// no-telemetry: error is propagated to the caller via the returned error value;
		// the search handler's RecordNonFatal covers the provider-level failure.
		return nil, true, fmt.Errorf("%s: %w", providerID, provider.ErrUnknown)
	}
	defer func() { _ = resp.Body.Close() }()

	switch resp.StatusCode {
	case http.StatusOK:
		// proceed
	case http.StatusNotFound:
		return nil, false, provider.ErrNotFound
	case http.StatusForbidden, http.StatusUnauthorized:
		return nil, false, provider.ErrForbidden
	default:
		// 5xx is transient; other unexpected codes are terminal.
		transient := resp.StatusCode >= 500
		return nil, transient, fmt.Errorf("%s: HTTP %d: %w", providerID, resp.StatusCode, provider.ErrUnknown)
	}

	var body apiResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, false, fmt.Errorf("%s: decode: %w", providerID, provider.ErrUnknown)
	}
	return &body, false, nil
}

// Health performs a lightweight probe (a fixed query) to confirm Knaben is
// reachable + returns parseable JSON.
func (c *Client) Health(ctx context.Context) error {
	_, err := c.Search(ctx, "ubuntu", 0)
	return err
}

// buildMagnet constructs a magnet URI from a v1 info_hash + display name,
// appending the public-tracker commons (Knaben's own magnetUrl carries stale
// trackers, so we rebuild from the clean hash for consistency + reliability).
func buildMagnet(infoHash, name string) string {
	v := url.Values{}
	v.Set("dn", name)
	for _, tr := range publicTrackers {
		v.Add("tr", tr)
	}
	return "magnet:?xt=urn:btih:" + infoHash + "&" + v.Encode()
}

// normalizeDate parses Knaben's RFC3339 date and reformats to plain RFC3339;
// returns "" when blank/unparseable.
func normalizeDate(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	return ""
}
