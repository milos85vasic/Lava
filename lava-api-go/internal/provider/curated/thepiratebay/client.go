// Package thepiratebay is a curated, compiled-in provider for The Pirate Bay,
// sourced from its public apibay.org JSON API (anonymous, no Cloudflare). It is
// part of the embedded-curated-providers feature (Defect B, 2026-06-12 design):
// each popular public tracker is a Go provider.Provider compiled into
// lava-api-go so the on-device api-app's GET /providers catalogue exposes it
// with SEARCH + MAGNET_LINK and zero external Jackett dependency.
package thepiratebay

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

// DefaultBaseURL is the apibay JSON API base. apibay.org is The Pirate Bay's
// own JSON search API (protocol endpoint = the provider's identity, the same
// way the native adapters encode their canonical base, e.g.
// rutracker.NewClient("https://rutracker.org/forum")). §6.R: not a deployment
// address — it is this provider's fixed upstream.
const DefaultBaseURL = "https://apibay.org"

// searchPath is the apibay query endpoint. `cat=0` = all categories.
const searchPath = "/q.php"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// publicTrackers are well-known public BitTorrent tracker announce URLs added
// to every built magnet so the link is usable without apibay supplying a
// tracker list. These are protocol data (the public-tracker commons), not a
// deployment address — package constants, consistent with how the jackett
// package treats its Torznab path constants (§6.R).
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// noResultsHash is the all-zero info_hash apibay returns in its single
// synthetic "No results returned" row. We treat that row as an empty result
// set rather than a fake torrent (anti-bluff: never surface a non-downloadable
// placeholder as a real result).
const noResultsHash = "0000000000000000000000000000000000000000"

// Client talks to the apibay JSON API. The base URL + *http.Client are
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

// apiRow mirrors one element of the apibay /q.php JSON array. Every field
// arrives as a string (apibay's wire format), so numbers are parsed below.
type apiRow struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	InfoHash string `json:"info_hash"`
	Leechers string `json:"leechers"`
	Seeders  string `json:"seeders"`
	NumFiles string `json:"num_files"`
	Size     string `json:"size"`
	Username string `json:"username"`
	Added    string `json:"added"`
	Category string `json:"category"`
	IMDb     string `json:"imdb"`
}

// Search queries apibay and maps the rows to provider.SearchItem, building a
// magnet link from each info_hash. apibay returns the full result set in one
// response (no server-side paging), so page is accepted for interface
// compatibility but only page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		// apibay has no paging; only the first page carries results.
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	u := c.baseURL + searchPath + "?" + url.Values{
		"q":   {query},
		"cat": {"0"},
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

	var rows []apiRow
	if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
		return nil, fmt.Errorf("%s: decode: %w", providerID, provider.ErrUnknown)
	}

	items := make([]provider.SearchItem, 0, len(rows))
	for _, r := range rows {
		hash := strings.ToLower(strings.TrimSpace(r.InfoHash))
		// Skip apibay's synthetic empty-result placeholder + any row without a
		// usable 40-hex v1 info_hash (anti-bluff: only real, downloadable rows).
		if hash == "" || hash == noResultsHash || len(hash) != 40 {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      r.Name,
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, r.Name),
			SizeBytes:  atoiInt64(r.Size),
			Seeders:    atoiInt(r.Seeders),
			Leechers:   atoiInt(r.Leechers),
			Date:       unixToDate(r.Added),
			Category:   r.Category,
		})
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// Health performs a lightweight probe (a fixed query) to confirm apibay is
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

func atoiInt(s string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(s))
	return n
}

func atoiInt64(s string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	return n
}

// unixToDate converts an apibay unix-seconds "added" string to RFC3339; returns
// "" when unparseable (apibay occasionally sends 0).
func unixToDate(s string) string {
	sec, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil || sec <= 0 {
		return ""
	}
	return time.Unix(sec, 0).UTC().Format(time.RFC3339)
}
