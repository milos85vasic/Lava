// Package torrentdownloads is a curated, compiled-in provider for
// TorrentDownloads, sourced from its public RSS search feed (anonymous, no
// Cloudflare challenge). It is part of the embedded-curated-providers feature
// (Defect B): each popular public tracker is a Go provider.Provider compiled
// into lava-api-go so the on-device api-app's GET /providers catalogue exposes
// it with SEARCH + MAGNET_LINK and zero external Jackett dependency.
//
// The feed is ISO-8859-1 (latin-1) encoded and carries a native byte <size> +
// an already-lowercase 40-hex <info_hash>. §6.E honesty: the RSS `search` term
// genuinely filters (verified live — search=ubuntu vs search=debian return
// disjoint, on-topic results), so CapSearch is honest.
package torrentdownloads

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// DefaultBaseURL is the TorrentDownloads site base. §6.R: not a deployment
// address — it is this provider's fixed upstream.
const DefaultBaseURL = "https://www.torrentdownloads.pro"

// rssPath is the TorrentDownloads RSS search endpoint.
const rssPath = "/rss.xml"

// DefaultTimeout bounds each upstream request when the caller does not supply a
// pre-configured *http.Client.
const DefaultTimeout = 20 * time.Second

// maxAttempts + retryBackoff bound a small retry on TRANSIENT upstream failures
// (network/timeout, or a 5xx). TorrentDownloads' live latency is variable and
// occasionally exceeds a single DefaultTimeout window → a user-facing "unknown
// error". A bounded retry converts that transient slowness into a successful
// search. Terminal errors (404 → ErrNotFound, 401/403 → ErrForbidden, decode
// failure) are NEVER retried — retrying them would only waste the budget.
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

// Client fetches + parses the TorrentDownloads RSS feed. The base URL +
// *http.Client are injectable so tests drive it against an httptest.Server.
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

// rssFeed / rssItem mirror the (plain, non-namespaced) TorrentDownloads RSS XML.
type rssFeed struct {
	Items []rssItem `xml:"channel>item"`
}

type rssItem struct {
	Title    string `xml:"title"`
	PubDate  string `xml:"pubDate"`
	InfoHash string `xml:"info_hash"`
	Seeders  string `xml:"seeders"`
	Leechers string `xml:"leechers"`
	Size     string `xml:"size"`
}

// Search queries the RSS feed and maps the items to provider.SearchItem. The
// feed returns the result set in one response, so page is accepted for interface
// compatibility but only page 0/1 returns rows.
func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	if strings.TrimSpace(query) == "" {
		return nil, provider.ErrNoData
	}
	if page > 1 {
		return &provider.SearchResult{Provider: providerID, Page: page, TotalPages: 1}, nil
	}

	u := c.baseURL + rssPath + "?" + url.Values{
		"type":   {"search"},
		"search": {query},
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
			Title:      strings.TrimSpace(it.Title),
			InfoHash:   hash,
			MagnetLink: buildMagnet(hash, strings.TrimSpace(it.Title)),
			SizeBytes:  atoiInt64(it.Size),
			Seeders:    atoiInt(it.Seeders),
			Leechers:   atoiInt(it.Leechers),
			Date:       parsePubDate(it.PubDate),
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

// fetchFeedOnce performs a single fetch+decode (via the latin-1-aware parseFeed).
// The bool reports whether the error is transient (worth retrying): true for a
// network/timeout error or a 5xx status; false for terminal errors
// (404/403/401/decode) and on success.
func (c *Client) fetchFeedOnce(ctx context.Context, u string) (*rssFeed, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, false, fmt.Errorf("%s: build request: %w", providerID, err)
	}
	req.Header.Set("Accept", "application/xml")

	resp, err := c.http.Do(req)
	if err != nil {
		// Network/timeout — transient (the slow-upstream case).
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

	feed, err := parseFeed(resp.Body)
	if err != nil {
		return nil, false, fmt.Errorf("%s: %w", providerID, provider.ErrUnknown)
	}
	return feed, false, nil
}

// parseFeed decodes the RSS body, transparently handling the feed's declared
// ISO-8859-1 (latin-1) charset (Go's xml.Decoder rejects a non-UTF-8 encoding
// declaration unless given a CharsetReader).
func parseFeed(r io.Reader) (*rssFeed, error) {
	dec := xml.NewDecoder(r)
	dec.CharsetReader = latin1CharsetReader
	var feed rssFeed
	if err := dec.Decode(&feed); err != nil {
		return nil, err
	}
	return &feed, nil
}

// latin1CharsetReader converts a non-UTF-8 (ISO-8859-1 / latin-1 / windows-1252
// family) byte stream to UTF-8 for the XML decoder. Each latin-1 byte maps to
// the Unicode code point of the same value (latin-1 IS the first 256 code
// points), which WriteRune then encodes as UTF-8. UTF-8/ASCII inputs pass
// through unchanged.
func latin1CharsetReader(charsetLabel string, in io.Reader) (io.Reader, error) {
	cl := strings.ToLower(strings.TrimSpace(charsetLabel))
	if cl == "" || cl == "utf-8" || cl == "utf8" || cl == "us-ascii" || cl == "ascii" {
		return in, nil
	}
	data, err := io.ReadAll(in)
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	b.Grow(len(data))
	for _, c := range data {
		b.WriteRune(rune(c))
	}
	return strings.NewReader(b.String()), nil
}

// Health performs a lightweight probe (a fixed query) to confirm the feed is
// reachable + parseable.
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

// parsePubDate converts a TorrentDownloads RSS RFC1123Z pubDate to RFC3339;
// returns "" when blank/unparseable.
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
