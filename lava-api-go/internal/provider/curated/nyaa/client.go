// Package nyaa is a curated, compiled-in provider for Nyaa, sourced from its
// public RSS search feed (anonymous, no Cloudflare). It is part of the
// embedded-curated-providers feature (Defect B): each popular public tracker is
// a Go provider.Provider compiled into lava-api-go so the on-device api-app's
// GET /providers catalogue exposes it with SEARCH + MAGNET_LINK and zero
// external Jackett dependency. Nyaa is the anime-niche complement to the
// general-purpose curated set.
//
// §6.E honesty: Nyaa's RSS `q` parameter genuinely filters (verified live —
// q=naruto vs q=bleach return disjoint, on-topic results), so CapSearch is
// honest.
package nyaa

import (
	"context"
	"encoding/xml"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the Nyaa site base. nyaa.si is the canonical host. §6.R: not
// a deployment address — it is this provider's fixed upstream.
const DefaultBaseURL = "https://nyaa.si"

// rssPath is the Nyaa RSS search endpoint (the page=rss query mode).
const rssPath = "/"

// nyaaNS is the XML namespace of Nyaa's per-item custom elements.
const nyaaNS = "https://nyaa.si/xmlns/nyaa"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (network/timeout, or a 5xx). Nyaa's live latency is variable and occasionally
// exceeds a single DefaultTimeout window → a user-facing "unknown error". A
// bounded retry converts that transient slowness into a successful search.
// Terminal errors (404 → ErrNotFound, 401/403 → ErrForbidden, decode failure)
// are NEVER retried — retrying them would only waste the budget.
const (
	maxAttempts  = 3
	retryBackoff = 500 * time.Millisecond
)

// publicTrackers are well-known public BitTorrent tracker announce URLs added to
// every built magnet. Protocol data (the public-tracker commons), not a
// deployment address — package constants (§6.R).
var publicTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// Client fetches + parses the Nyaa RSS feed. The base URL + *http.Client are
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

// rssFeed / rssItem mirror the Nyaa RSS XML. The nyaa:-prefixed elements are
// matched by their namespace URL (Go's encoding/xml `namespace local` form).
type rssFeed struct {
	Items []rssItem `xml:"channel>item"`
}

type rssItem struct {
	Title    string `xml:"title"`
	PubDate  string `xml:"pubDate"`
	InfoHash string `xml:"https://nyaa.si/xmlns/nyaa infoHash"`
	Seeders  string `xml:"https://nyaa.si/xmlns/nyaa seeders"`
	Leechers string `xml:"https://nyaa.si/xmlns/nyaa leechers"`
	Size     string `xml:"https://nyaa.si/xmlns/nyaa size"`
	Category string `xml:"https://nyaa.si/xmlns/nyaa category"`
}

// Search queries the Nyaa RSS feed and maps the items to provider.SearchItem,
// building a magnet link from each info_hash. The RSS feed returns the result
// set in one response, so page is accepted for interface compatibility but only
// page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	u := c.baseURL + rssPath + "?" + url.Values{
		"page": {"rss"},
		"q":    {query},
		"c":    {"0_0"},
		"f":    {"0"},
	}.Encode()

	feed, err := c.fetchFeed(ctx, u)
	if err != nil {
		return nil, err
	}

	items := make([]provider.SearchItem, 0, len(feed.Items))
	for _, it := range feed.Items {
		hash := strings.ToLower(strings.TrimSpace(it.InfoHash))
		// Skip any item without a usable 40-hex v1 info_hash (anti-bluff: only
		// real, downloadable rows reach the user's provider list).
		if len(hash) != 40 {
			continue
		}
		items = append(items, provider.SearchItem{
			ID:         hash,
			Title:      it.Title,
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, it.Title),
			SizeBytes:  parseHumanSize(it.Size),
			Seeders:    atoiInt(it.Seeders),
			Leechers:   atoiInt(it.Leechers),
			Date:       parsePubDate(it.PubDate),
			Category:   it.Category,
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

	var feed rssFeed
	if err := xml.NewDecoder(resp.Body).Decode(&feed); err != nil {
		return nil, false, fmt.Errorf("%s: decode: %w", providerID, provider.ErrUnknown)
	}
	return &feed, false, nil
}

// Health performs a lightweight probe (a fixed query) to confirm Nyaa is
// reachable + returns parseable RSS.
func (c *Client) Health(ctx context.Context) error {
	_, err := c.Search(ctx, "anime", 0)
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

// sizeUnits maps Nyaa's binary (IEC) size suffixes to byte multipliers.
var sizeUnits = []struct {
	suffix string
	mult   float64
}{
	{"TiB", 1 << 40},
	{"GiB", 1 << 30},
	{"MiB", 1 << 20},
	{"KiB", 1 << 10},
	{"B", 1},
}

// parseHumanSize converts a Nyaa human size like "400 MiB" / "1.5 GiB" to bytes;
// returns 0 when unparseable.
func parseHumanSize(s string) int64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	for _, u := range sizeUnits {
		if strings.HasSuffix(s, u.suffix) {
			num := strings.TrimSpace(strings.TrimSuffix(s, u.suffix))
			f, err := strconv.ParseFloat(num, 64)
			if err != nil {
				// no-telemetry: pure string-to-float parse for RSS size field; malformed
				// values return 0 (unknown size); no ctx available in this helper.
				return 0
			}
			return int64(f * u.mult)
		}
	}
	return 0
}

// parsePubDate converts a Nyaa RSS RFC1123Z pubDate to RFC3339; returns "" when
// blank/unparseable.
func parsePubDate(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if t, err := time.Parse(time.RFC1123Z, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	if t, err := time.Parse(time.RFC1123, s); err == nil {
		return t.UTC().Format(time.RFC3339)
	}
	return ""
}
