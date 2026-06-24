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

// DefaultBaseURL is a stable YTS JSON API mirror, kept for the single-URL
// test seam. §6.R: not a deployment address — it is this provider's fixed
// upstream protocol mirror (same §6.R rationale as DefaultBaseURLs below).
// Updated 2026-06-24: yts.mx is NXDOMAIN (dead); yts.bz is the fastest
// responding live mirror measured at ~0.24–0.8s.
const DefaultBaseURL = "https://yts.bz"

// DefaultBaseURLs is the FAILOVER list of YTS API mirrors, tried in order.
// YTS rotates its domain frequently under takedown pressure. These are
// protocol mirrors of the SAME public API (the provider's identity), not
// deployment addresses — package constants (§6.R).
//
// Mirror status as of 2026-06-24 (real-network verified):
//
//	yts.bz           — 200 OK, ~0.24–0.8s  ← FASTEST, listed first
//	yts.lt           — 200 OK, ~0.8s
//	yts.am           — 200 OK, ~0.7–5.8s   ← occasionally slow
//	yts.gg           — DNS resolves, TLS connects, HTTP stalls from some
//	                   networks; sub-second from phones/other regions
//	movies-api.accel.li — same stall pattern from this host; the API's own
//	                   NOTICE says "Base URL moving to https://movies-api.accel.li/api/v2/"
//	                   but it stalls from some egress IPs; listed last as
//	                   future-proofing when connectivity improves
//	yts.mx           — NXDOMAIN (dead 2026-06-24); REMOVED from list
//
// The perAttemptTimeout (8s) protects against the stall-on-connect class
// (yts.gg / movies-api.accel.li) so they cannot consume the full budget.
var DefaultBaseURLs = []string{
	"https://yts.bz",
	"https://yts.lt",
	"https://yts.am",
	"https://yts.gg",
	"https://movies-api.accel.li",
}

// searchPath is the YTS list_movies query endpoint.
const searchPath = "/api/v2/list_movies.json"

// DefaultTimeout bounds the whole HTTP client (TLS + headers + body).
const DefaultTimeout = 20 * time.Second

// perAttemptTimeout bounds EACH mirror attempt during failover so one
// dead/hanging mirror (e.g. a stale yts.mx DNS entry that connects but stalls)
// cannot consume the entire search budget. Only applied when >1 mirror is set.
const perAttemptTimeout = 8 * time.Second

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

// Client talks to the YTS JSON API across a failover list of mirror base URLs.
// The base URLs + *http.Client are injectable so tests drive it against
// httptest.Servers (no live calls in the default `go test`).
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
// one answers successfully — the resilience against YTS domain rotation.
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

	// Failover: try each mirror in order, returning the first that answers
	// successfully. A mirror that is DNS-dead (yts.mx as of 2026-06-13), 5xx,
	// or otherwise errors is skipped; only an unreachable ENTIRE list surfaces
	// the last error (so the historical single-host tests still see their 5xx).
	// When more than one mirror is configured, each attempt is bounded by
	// perAttemptTimeout so a single dead/hanging mirror cannot stall the whole
	// search — failover stays snappy.
	var lastErr error
	for _, base := range c.baseURLs {
		attemptCtx := ctx
		var cancel context.CancelFunc
		if len(c.baseURLs) > 1 {
			attemptCtx, cancel = context.WithTimeout(ctx, perAttemptTimeout)
		}
		res, err := c.searchOne(attemptCtx, base, query)
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
func (c *Client) searchOne(ctx context.Context, base, query string) (*provider.SearchResult, error) {
	u := base + searchPath + "?" + url.Values{
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
		// no-telemetry: error is propagated to the caller via the returned error value;
		// the search handler's RecordNonFatal covers the provider-level failure.
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
