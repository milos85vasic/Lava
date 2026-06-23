// Package bitsearch is a curated, compiled-in provider for BitSearch, sourced
// from its public bitsearch.eu JSON search API (anonymous, no Cloudflare
// challenge). It is part of the embedded-curated-providers feature (Defect B):
// each popular public tracker is a Go provider.Provider compiled into
// lava-api-go so the on-device api-app's GET /providers catalogue exposes it
// with SEARCH + MAGNET_LINK and zero external Jackett dependency.
//
// §6.E honesty: BitSearch genuinely filters on the free-text `q` term (verified
// live — q=ubuntu vs q=debian return disjoint, on-topic results), so CapSearch
// is honest.
package bitsearch

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the BitSearch JSON API base. bitsearch.to 301-redirects to
// bitsearch.eu, so the .eu host is pinned as the canonical upstream. §6.R: not a
// deployment address — it is this provider's fixed upstream.
const DefaultBaseURL = "https://bitsearch.eu"

// searchPath is the BitSearch free-text query endpoint.
const searchPath = "/api/v1/search"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (network/timeout, or a 5xx). BitSearch's live latency is variable and
// occasionally exceeds a single DefaultTimeout window → a user-facing "unknown
// error". A bounded retry converts that transient slowness into a successful
// search. Terminal errors (404 → ErrNotFound, 401/403 → ErrForbidden, decode
// failure) are NEVER retried — retrying them would only waste the budget.
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
)

// publicTrackers are well-known public BitTorrent tracker announce URLs added to
// every built magnet so the link is usable (BitSearch serves an info_hash only,
// no tracker list). Protocol data (the public-tracker commons), not a deployment
// address — package constants (§6.R), identical to the thepiratebay rationale.
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// Client talks to the BitSearch JSON API. The base URL + *http.Client are
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
		http:    &http.Client{Timeout: DefaultTimeout},
	}
}

// apiResponse mirrors the /api/v1/search JSON envelope. Fields arrive
// native-typed (BitSearch serves real JSON numbers + an RFC3339 updatedAt).
type apiResponse struct {
	Results []apiRow `json:"results"`
}

type apiRow struct {
	InfoHash  string `json:"infohash"`
	Title     string `json:"title"`
	Size      int64  `json:"size"`
	Category  int    `json:"category"`
	Seeders   int    `json:"seeders"`
	Leechers  int    `json:"leechers"`
	UpdatedAt string `json:"updatedAt"`
}

// Search queries BitSearch and maps the rows to provider.SearchItem, building a
// magnet link from each info_hash. BitSearch uses a 1-indexed `page`; a page of
// 0 is treated as the first page.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page < 1 {
		page = 1
	}

	u := c.baseURL + searchPath + "?" + url.Values{
		"q":    {query},
		"page": {strconv.Itoa(page)},
	}.Encode()

	body, err := c.fetchResults(ctx, u)
	if err != nil {
		return nil, err
	}

	items := make([]provider.SearchItem, 0, len(body.Results))
	for _, r := range body.Results {
		hash := strings.ToLower(strings.TrimSpace(r.InfoHash))
		// Skip any row without a usable 40-hex v1 info_hash (anti-bluff: only
		// real, downloadable rows reach the user's provider list).
		if len(hash) != 40 {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      r.Title,
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, r.Title),
			SizeBytes:  r.Size,
			Seeders:    r.Seeders,
			Leechers:   r.Leechers,
			Date:       normalizeDate(r.UpdatedAt),
			Category:   strconv.Itoa(r.Category),
		})
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       page,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// fetchResults fetches + decodes the search response at u, retrying up to
// maxAttempts on TRANSIENT failures (network/timeout from http.Do, or a 5xx).
// Terminal errors (404/403/401/decode) return immediately. The retry budget is
// bounded by the caller's ctx — each backoff aborts early if ctx is
// cancelled/expired.
func (c *Client) fetchResults(ctx context.Context, u string) (*apiResponse, error) {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		body, transient, err := c.fetchResultsOnce(ctx, u)
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

// fetchResultsOnce performs a single fetch+decode. The bool reports whether the
// error is transient (worth retrying): true for a network/timeout error or a
// 5xx status; false for terminal errors (404/403/401/decode) and on success.
func (c *Client) fetchResultsOnce(ctx context.Context, u string) (*apiResponse, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, false, fmt.Errorf("%s: build request: %w", providerID, err)
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient (the slow-upstream case).
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

// Health performs a lightweight probe (a fixed query) to confirm BitSearch is
// reachable + returns parseable JSON.
func (c *Client) Health(ctx context.Context) error {
	_, err := c.Search(ctx, "ubuntu", 1)
	return err
}

// buildMagnet constructs a magnet URI from a v1 info_hash + display name,
// appending the public-tracker commons.
func buildMagnet(infoHash, name string) string {
	v := url.Values{}
	v.Set("dn", name)
	for _, tr := range publicTrackers {
		v.Add("tr", tr)
	}
	return "magnet:?xt=urn:btih:" + infoHash + "&" + v.Encode()
}

// normalizeDate parses BitSearch's RFC3339(-nano) updatedAt and reformats it to
// plain RFC3339; returns "" when blank/unparseable (some rows carry "").
func normalizeDate(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	return ""
}
