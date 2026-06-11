// Package yts is a curated, compiled-in provider for YTS, sourced from its
// public list_movies JSON API (anonymous, no Cloudflare). It is part of the
// embedded-curated-providers feature: each popular public tracker is a Go
// provider.Provider compiled into lava-api-go so the on-device api-app's
// GET /providers catalogue exposes it with SEARCH + MAGNET_LINK and zero
// external Jackett dependency.
package yts

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the YTS JSON API base. yts.mx is YTS's own movie-search
// API (protocol endpoint = the provider's identity, the same way the native
// adapters encode their canonical base). §6.R: not a deployment address — it is
// this provider's fixed upstream.
const DefaultBaseURL = "https://yts.mx"

// searchPath is the YTS list_movies query endpoint.
const searchPath = "/api/v2/list_movies.json"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// publicTrackers are well-known public BitTorrent tracker announce URLs added
// to every built magnet so the link is usable without YTS supplying a tracker
// list. These are protocol data (the public-tracker commons), not a deployment
// address — package constants (§6.R).
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// Client talks to the YTS JSON API. The base URL + *http.Client are injectable
// so tests drive it against an httptest.Server (no live calls in the default
// `go test`).
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

// apiResponse mirrors the YTS list_movies.json envelope. When no results match,
// MovieCount is 0 and Movies is absent/empty.
type apiResponse struct {
	Status string  `json:"status"`
	Data   apiData `json:"data"`
}

type apiData struct {
	MovieCount int        `json:"movie_count"`
	Movies     []apiMovie `json:"movies"`
}

type apiMovie struct {
	ID        int          `json:"id"`
	TitleLong string       `json:"title_long"`
	Year      int          `json:"year"`
	Torrents  []apiTorrent `json:"torrents"`
}

type apiTorrent struct {
	Hash             string `json:"hash"`
	Quality          string `json:"quality"`
	Type             string `json:"type"`
	SizeBytes        int64  `json:"size_bytes"`
	Seeds            int    `json:"seeds"`
	Peers            int    `json:"peers"`
	DateUploadedUnix int64  `json:"date_uploaded_unix"`
}

// Search queries YTS and FLATTENS each movie's torrents into one
// provider.SearchItem per torrent (a movie carries multiple quality torrents),
// building a magnet link from each hash. YTS returns the full result set in one
// response (server-side paging via page= is available but we request limit=50
// and treat that as the single page), so page is accepted for interface
// compatibility but only page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	u := c.baseURL + searchPath + "?" + url.Values{
		"query_term": {query},
		"limit":      {"50"},
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

	items := make([]provider.SearchItem, 0)
	for _, m := range body.Data.Movies {
		for _, tor := range m.Torrents {
			hash := strings.ToLower(strings.TrimSpace(tor.Hash))
			// Skip any torrent without a usable 40-hex v1 info_hash (anti-bluff:
			// only real, downloadable rows; never surface a placeholder).
			if len(hash) != 40 || !isHex(hash) {
				continue
			}
			title := m.TitleLong + " [" + tor.Quality + "]"
			items = append(items, provider.SearchItem{
				ID:         hash,
				Title:      title,
				InfoHash:   hash,
				MagnetLink: buildMagnet(hash, title),
				SizeBytes:  tor.SizeBytes,
				Seeders:    tor.Seeds,
				Leechers:   tor.Peers,
				Date:       unixToDate(tor.DateUploadedUnix),
			})
		}
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// Health performs a lightweight probe (a fixed query) to confirm YTS is
// reachable + returns parseable JSON.
func (c *Client) Health(ctx context.Context) error {
	_, err := c.Search(ctx, "ubuntu", 0)
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

// isHex reports whether s is composed entirely of lowercase hex digits.
func isHex(s string) bool {
	for _, ch := range s {
		switch {
		case ch >= '0' && ch <= '9':
		case ch >= 'a' && ch <= 'f':
		default:
			return false
		}
	}
	return true
}

// unixToDate converts a YTS unix-seconds upload timestamp to RFC3339; returns
// "" when zero/unparseable (YTS occasionally sends 0).
func unixToDate(sec int64) string {
	if sec <= 0 {
		return ""
	}
	return time.Unix(sec, 0).UTC().Format(time.RFC3339)
}
