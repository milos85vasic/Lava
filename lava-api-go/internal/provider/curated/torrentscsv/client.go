// Package torrentscsv is a curated, compiled-in provider for Torrents-CSV,
// sourced from its public torrents-csv.com JSON search API (anonymous, no
// Cloudflare). It is part of the embedded-curated-providers feature (Defect B,
// 2026-06-12 design): each popular public tracker is a Go provider.Provider
// compiled into lava-api-go so the on-device api-app's GET /providers catalogue
// exposes it with SEARCH + MAGNET_LINK and zero external Jackett dependency.
//
// Unlike apibay (The Pirate Bay), Torrents-CSV genuinely filters by the free-text
// `q` term and serves native-typed JSON fields, so this provider honestly
// declares CapSearch (§6.E).
package torrentscsv

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

// DefaultBaseURL is the Torrents-CSV API base. torrents-csv.com is the tracker's
// own free-text JSON search service (protocol endpoint = the provider's
// identity, the same way the native adapters encode their canonical base).
// §6.R: not a deployment address — it is this provider's fixed upstream.
const DefaultBaseURL = "https://torrents-csv.com"

// searchPath is the Torrents-CSV free-text query endpoint.
const searchPath = "/service/search"

// pageSize is the number of rows requested per page (Torrents-CSV `size` param).
const pageSize = 50

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// publicTrackers are well-known public BitTorrent tracker announce URLs added
// to every built magnet so the link is usable (Torrents-CSV serves an info_hash
// only, no tracker list). These are protocol data (the public-tracker commons),
// not a deployment address — package constants (§6.R), identical to the
// thepiratebay package's rationale.
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// Client talks to the Torrents-CSV JSON API. The base URL + *http.Client are
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

// apiResponse mirrors the /service/search JSON envelope. Fields arrive
// native-typed (Torrents-CSV serves real JSON numbers, unlike apibay's strings).
type apiResponse struct {
	Torrents []apiRow `json:"torrents"`
	Next     int64    `json:"next"`
}

type apiRow struct {
	InfoHash    string `json:"infohash"`
	Name        string `json:"name"`
	SizeBytes   int64  `json:"size_bytes"`
	CreatedUnix int64  `json:"created_unix"`
	Seeders     int    `json:"seeders"`
	Leechers    int    `json:"leechers"`
}

// Search queries Torrents-CSV and maps the rows to provider.SearchItem, building
// a magnet link from each info_hash. Torrents-CSV uses a 1-indexed `page`; a
// page of 0 is treated as the first page.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page < 1 {
		page = 1
	}

	u := c.baseURL + searchPath + "?" + url.Values{
		"q":    {query},
		"size": {strconv.Itoa(pageSize)},
		"page": {strconv.Itoa(page)},
	}.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("%s: build request: %w", providerID, err)
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", providerID, provider.ErrUnknown)
	}
	defer func() { _ = resp.Body.Close() }()

	switch resp.StatusCode {
	case http.StatusOK:
		// proceed
	case http.StatusNotFound:
		return nil, provider.ErrNotFound
	case http.StatusForbidden, http.StatusUnauthorized:
		return nil, provider.ErrForbidden
	default:
		return nil, fmt.Errorf("%s: HTTP %d: %w", providerID, resp.StatusCode, provider.ErrUnknown)
	}

	var body apiResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("%s: decode: %w", providerID, provider.ErrUnknown)
	}

	items := make([]provider.SearchItem, 0, len(body.Torrents))
	for _, r := range body.Torrents {
		hash := strings.ToLower(strings.TrimSpace(r.InfoHash))
		// Skip any row without a usable 40-hex v1 info_hash (anti-bluff: only
		// real, downloadable rows reach the user's provider list).
		if len(hash) != 40 {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      r.Name,
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, r.Name),
			SizeBytes:  r.SizeBytes,
			Seeders:    r.Seeders,
			Leechers:   r.Leechers,
			Date:       unixToDate(r.CreatedUnix),
		})
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       page,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// Health performs a lightweight probe (a fixed query) to confirm Torrents-CSV is
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

// unixToDate converts a Torrents-CSV unix-seconds "created_unix" to RFC3339;
// returns "" when <= 0 (some rows carry 0).
func unixToDate(sec int64) string {
	if sec <= 0 {
		return ""
	}
	return time.Unix(sec, 0).UTC().Format(time.RFC3339)
}
