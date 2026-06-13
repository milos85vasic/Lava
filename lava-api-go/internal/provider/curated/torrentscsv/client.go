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
// §6.R: not a deployment address — it is this provider's fixed upstream. Kept
// for the single-URL test seam and as the sole member of DefaultBaseURLs.
const DefaultBaseURL = "https://torrents-csv.com"

// DefaultBaseURLs is the FAILOVER list of Torrents-CSV API mirrors, tried in
// order — the same domain-rotation resilience the YTS provider carries (see
// internal/provider/curated/yts/client.go). The structure exists so that if
// torrents-csv.com rotates out of DNS (the YTS-class failure: yts.mx was
// observed NXDOMAIN on 2026-06-13 while siblings served HTTP 200) a
// verified-live replacement host is a ONE-LINE addition here, not a client
// rewrite.
//
// HONEST MIRROR INVENTORY (§6.L — a fabricated mirror is a bluff): as of
// 2026-06-13, live HTTP probing found exactly ONE real Torrents-CSV
// /service/search endpoint. torrents-csv.com served HTTP 200 application/json;
// candidate hosts torrents-csv.ml (HTTP 404) and torrents-csv.org (NXDOMAIN)
// and api.torrents-csv.com (NXDOMAIN) do NOT serve the search contract.
// Torrents-CSV is a self-hostable project (git.torrents-csv.com is its Gitea
// code repo, NOT a search API), and torrents-csv.com is the canonical public
// instance. So this list contains the ONE verified-live host; we deliberately
// do NOT pad it with dead .ml/.org domains that would only add latency and
// failover noise. The failover code path is real and unit-tested (httptest
// servers) so adding a second real host later requires no further change.
// These are protocol mirrors of the SAME public API (the provider's identity),
// not deployment addresses — package constants, same §6.R rationale as
// publicTrackers below.
var DefaultBaseURLs = []string{
	"https://torrents-csv.com",
}

// searchPath is the Torrents-CSV free-text query endpoint.
const searchPath = "/service/search"

// pageSize is the number of rows requested per page (Torrents-CSV `size` param).
const pageSize = 50

// DefaultTimeout bounds the whole HTTP client (TLS + headers + body).
const DefaultTimeout = 20 * time.Second

// perAttemptTimeout bounds EACH mirror attempt during failover so one
// dead/hanging mirror cannot consume the entire search budget. Only applied
// when more than one mirror is configured.
const perAttemptTimeout = 8 * time.Second

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

// Client talks to the Torrents-CSV JSON API across a failover list of mirror
// base URLs. The base URLs + *http.Client are injectable so tests drive it
// against httptest.Servers (no live calls in the default `go test`).
type Client struct {
	baseURLs []string
	http     *http.Client
}

// NewClient returns a single-base-URL Client (DefaultBaseURLs failover list when
// empty). The single-URL form preserves the httptest seam used by the parser
// tests; production registration uses NewClientWithMirrors(DefaultBaseURLs).
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		return NewClientWithMirrors(DefaultBaseURLs)
	}
	return NewClientWithMirrors([]string{baseURL})
}

// NewClientWithMirrors returns a Client that tries each base URL in order until
// one answers successfully — the resilience against Torrents-CSV domain rotation.
func NewClientWithMirrors(urls []string) *Client {
	bases := make([]string, 0, len(urls))
	for _, u := range urls {
		bases = append(bases, strings.TrimRight(u, "/"))
	}
	return &Client{
		baseURLs: bases,
		http:     &http.Client{Timeout: DefaultTimeout},
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

	// Failover: try each mirror in order, returning the first that answers
	// successfully. A mirror that is DNS-dead, 5xx, or otherwise errors is
	// skipped; only an unreachable ENTIRE list surfaces the last error (so the
	// historical single-host tests still see their 5xx). When more than one
	// mirror is configured, each attempt is bounded by perAttemptTimeout so a
	// single dead/hanging mirror cannot stall the whole search.
	var lastErr error
	for _, base := range c.baseURLs {
		attemptCtx := ctx
		var cancel context.CancelFunc
		if len(c.baseURLs) > 1 {
			attemptCtx, cancel = context.WithTimeout(ctx, perAttemptTimeout)
		}
		res, err := c.searchOne(attemptCtx, base, query, page)
		if cancel != nil {
			cancel()
		}
		if err == nil {
			return res, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		// No base URLs configured at all.
		lastErr = fmt.Errorf("%s: no mirrors configured: %w", providerID, provider.ErrUnknown)
	}
	return nil, lastErr
}

// searchOne performs a single-mirror query + parse. Search wraps it with
// across-mirror failover.
func (c *Client) searchOne(ctx context.Context, base, query string, page int) (*provider.SearchResult, error) {
	u := base + searchPath + "?" + url.Values{
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
