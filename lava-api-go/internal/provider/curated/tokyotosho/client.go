// Package tokyotosho is a curated, compiled-in provider for Tokyo Toshokan, the
// long-running anime / Asian-media torrent index, sourced from its public RSS
// search feed (anonymous, no Cloudflare, no login). It is part of the
// embedded-curated-providers feature (Defect B): each popular public tracker is
// a Go provider.Provider compiled into lava-api-go so the on-device api-app's
// GET /providers catalogue exposes it with SEARCH + MAGNET_LINK and zero
// external Jackett dependency. Tokyo Toshokan is the second anime-niche provider
// (alongside nyaa), broadening Asian-media coverage.
//
// §6.E honesty: Tokyo Toshokan's RSS `terms` parameter genuinely filters
// (verified live 2026-06-13 — terms=naruto → 93% of titles contain "naruto";
// terms=bleach → 98% contain "bleach"; the empty feed contains neither; naruto
// vs bleach result sets are near-disjoint, 2 of ~140 overlap). So CapSearch is
// honest — NOT the EZTV/Knaben-search_type no-op-query bluff.
//
// Wire-shape specifics that differ from nyaa:
//   - The magnet is embedded inside each item's <description> CDATA HTML
//     (`<a href="magnet:?xt=urn:btih:<HASH>...">Magnet Link</a>`), NOT in a
//     dedicated namespaced element.
//   - The info_hash is BASE32 (32 chars), not 40-hex.
//   - There is NO seeders/leechers field — Seeders defaults to 0 (documented).
//   - Size is decimal ("113.89MB", "5.93MB"), not IEC ("400 MiB").
package tokyotosho

import (
	"context"
	"encoding/xml"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the Tokyo Toshokan site base. The bare host 302-redirects to
// the www host, so we target www directly to avoid the redirect round-trip.
// §6.R: not a deployment address — this provider's fixed upstream.
const DefaultBaseURL = "https://www.tokyotosho.info"

// rssPath is the Tokyo Toshokan RSS search endpoint.
const rssPath = "/rss.php"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (network/timeout, or a 5xx). Tokyo Toshokan's live latency is variable and
// occasionally exceeds a single DefaultTimeout window (observed 2026-06-16 on the
// nezha heavy-test node: typical 4–8 s, occasionally >20 s → a user-facing
// "unknown error"). A bounded retry converts that transient slowness into a
// successful search. Terminal errors (404 → ErrNotFound, 401/403 → ErrForbidden,
// decode failure) are NEVER retried — retrying them would only waste the budget.
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
)

// publicTrackers are well-known public BitTorrent tracker announce URLs added to
// every built magnet (the upstream magnet only carries its own ehtracker announce,
// so we enrich with the public-tracker commons for better swarm reach). Protocol
// data, not a deployment address — package constants (§6.R).
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// magnetRe extracts the btih info_hash from a magnet URI embedded in the item
// description HTML. Tokyo Toshokan uses BASE32 (32 chars, A-Z2-7) info_hashes.
var magnetRe = regexp.MustCompile(`magnet:\?xt=urn:btih:([A-Za-z0-9]+)`)

// sizeRe extracts the "Size: <num><unit>" value from the description HTML.
var sizeRe = regexp.MustCompile(`Size:\s*([0-9.]+)\s*([KMGT]?i?B)`)

// Client fetches + parses the Tokyo Toshokan RSS feed. The base URL + *http.Client
// are injectable so tests drive it against an httptest.Server (no live calls in
// the default `go test`).
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

// rssFeed / rssItem mirror the Tokyo Toshokan RSS 2.0 XML (no custom namespace —
// the magnet + size live inside the description CDATA HTML, parsed post-decode).
type rssFeed struct {
	Items []rssItem `xml:"channel>item"`
}

type rssItem struct {
	Title       string `xml:"title"`
	PubDate     string `xml:"pubDate"`
	Category    string `xml:"category"`
	Description string `xml:"description"`
	GUID        string `xml:"guid"`
}

// Search queries the Tokyo Toshokan RSS feed and maps the items to
// provider.SearchItem, extracting the magnet (and thus info_hash) from each
// item's description HTML. The RSS feed returns the result set in one response,
// so page is accepted for interface compatibility but only page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	u := c.baseURL + rssPath + "?" + url.Values{
		"terms": {query},
	}.Encode()

	feed, err := c.fetchFeed(ctx, u)
	if err != nil {
		return nil, err
	}

	items := make([]provider.SearchItem, 0, len(feed.Items))
	for _, it := range feed.Items {
		hash := extractInfoHash(it.Description)
		// Skip any item without an extractable info_hash (anti-bluff: only real,
		// downloadable rows reach the user's provider list — the no-magnet row in
		// the fixture must be filtered out).
		if hash == "" {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      strings.TrimSpace(it.Title),
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, it.Title),
			SizeBytes:  parseHumanSize(it.Description),
			// Tokyo Toshokan's RSS carries no seeders/leechers field, so Seeders
			// stays 0 (documented). This is honest absence, not a bug.
			Seeders:  0,
			Leechers: 0,
			Date:     parsePubDate(it.PubDate),
			Category: strings.TrimSpace(it.Category),
		})
	}

	return &provider.SearchResult{
		Provider:   providerID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}, nil
}

// fetchFeed fetches + decodes the RSS feed at u, retrying up to maxAttempts on
// TRANSIENT failures (network/timeout from http.Do, or a 5xx). Terminal errors
// (404/403/401/decode) return immediately. The retry budget is bounded by the
// caller's ctx — each backoff aborts early if ctx is cancelled/expired.
func (c *Client) fetchFeed(ctx context.Context, u string) (*rssFeed, error) {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		feed, transient, err := c.fetchFeedOnce(ctx, u)
		if err == nil {
			return feed, nil
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

// fetchFeedOnce performs a single fetch+decode. The bool reports whether the
// error is transient (worth retrying): true for a network/timeout error or a
// 5xx status; false for terminal errors (404/403/401/decode) and on success.
func (c *Client) fetchFeedOnce(ctx context.Context, u string) (*rssFeed, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, false, fmt.Errorf("%s: build request: %w", providerID, err)
	}
	req.Header.Set("Accept", "application/xml")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient (this is the tokyotosho-slow-upstream case).
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

	var feed rssFeed
	if err := xml.NewDecoder(resp.Body).Decode(&feed); err != nil {
		return nil, false, fmt.Errorf("%s: decode: %w", providerID, provider.ErrUnknown)
	}
	return &feed, false, nil
}

// Health performs a lightweight probe (a fixed query) to confirm Tokyo Toshokan
// is reachable + returns parseable RSS.
func (c *Client) Health(ctx context.Context) error {
	_, err := c.Search(ctx, "anime", 0)
	return err
}

// extractInfoHash pulls the btih info_hash out of the magnet URI embedded in the
// item description HTML, normalized to upper-case (BASE32 is case-insensitive but
// upstream emits upper). Returns "" when no magnet is present.
func extractInfoHash(description string) string {
	m := magnetRe.FindStringSubmatch(description)
	if m == nil {
		return ""
	}
	return strings.ToUpper(strings.TrimSpace(m[1]))
}

// buildMagnet constructs a magnet URI from a btih info_hash + display name,
// appending the public-tracker commons.
func buildMagnet(infoHash, name string) string {
	v := url.Values{}
	v.Set("dn", name)
	for _, tr := range publicTrackers {
		v.Add("tr", tr)
	}
	return "magnet:?xt=urn:btih:" + infoHash + "&" + v.Encode()
}

// sizeUnits maps Tokyo Toshokan's DECIMAL (SI) size suffixes to byte multipliers.
// Tokyo Toshokan reports e.g. "113.89MB" using powers of 1000, distinct from
// nyaa's IEC ("400 MiB") powers of 1024. The "iB" forms are also accepted
// defensively in case the upstream ever emits them.
var sizeUnits = []struct {
	suffix string
	mult   float64
}{
	{"TiB", 1 << 40},
	{"GiB", 1 << 30},
	{"MiB", 1 << 20},
	{"KiB", 1 << 10},
	{"TB", 1e12},
	{"GB", 1e9},
	{"MB", 1e6},
	{"KB", 1e3},
	{"B", 1},
}

// parseHumanSize extracts the "Size: <num><unit>" value from the description HTML
// and converts it to bytes; returns 0 when unparseable.
func parseHumanSize(description string) int64 {
	m := sizeRe.FindStringSubmatch(description)
	if m == nil {
		return 0
	}
	num, unit := m[1], m[2]
	for _, u := range sizeUnits {
		if unit == u.suffix {
			f, err := strconv.ParseFloat(num, 64)
			if err != nil {
				return 0
			}
			return int64(f * u.mult)
		}
	}
	return 0
}

// parsePubDate converts a Tokyo Toshokan RSS RFC1123 pubDate (GMT) to RFC3339;
// returns "" when blank/unparseable.
func parsePubDate(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if t, err := time.Parse(time.RFC1123, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	if t, err := time.Parse(time.RFC1123Z, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	return ""
}
