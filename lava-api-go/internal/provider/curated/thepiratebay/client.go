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
// address — it is this provider's fixed upstream. Kept for the single-URL test
// seam and as the sole member of DefaultBaseURLs.
const DefaultBaseURL = "https://apibay.org"

// DefaultBaseURLs is the FAILOVER list of apibay JSON API mirrors, tried in
// order — the same domain-rotation resilience the YTS provider carries (see
// internal/provider/curated/yts/client.go). The structure exists so that if
// apibay.org rotates out of DNS (the YTS-class failure: yts.mx was observed
// NXDOMAIN on 2026-06-13 while siblings served HTTP 200) a verified-live
// replacement host is a ONE-LINE addition here, not a client rewrite.
//
// HONEST MIRROR INVENTORY (§6.L — a fabricated mirror is a bluff): as of
// 2026-06-13, live HTTP probing found exactly ONE real apibay-compatible JSON
// endpoint. apibay.org/q.php served HTTP 200 application/json; the TPB frontend
// proxies (thepiratebay.org/apibay 302-redirects to a static SPA, tpb.party and
// thepiratebay10.xyz 404 the /q.php path) do NOT expose a compatible JSON API.
// apibay.org IS The Pirate Bay's canonical internal JSON API and the wrapper
// ecosystem (Jackett #9447 and the apibay TS/Python libraries) all hit
// apibay.org exclusively. So this list contains the ONE verified-live host; we
// deliberately do NOT pad it with dead .xyz/.party domains that would only add
// latency and failover noise. The failover code path is real and unit-tested
// (httptest servers) so adding a second real host later requires no further
// change. These are protocol mirrors of the SAME public API (the provider's
// identity), not deployment addresses — package constants, same §6.R rationale
// as publicTrackers below.
var DefaultBaseURLs = []string{
	"https://apibay.org",
}

// searchPath is the apibay query endpoint. `cat=0` = all categories.
const searchPath = "/q.php"

// DefaultTimeout bounds the whole HTTP client (TLS + headers + body).
const DefaultTimeout = 20 * time.Second

// perAttemptTimeout bounds EACH mirror attempt during failover so one
// dead/hanging mirror cannot consume the entire search budget. Only applied
// when more than one mirror is configured.
const perAttemptTimeout = 8 * time.Second

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

// Client talks to the apibay JSON API across a failover list of mirror base
// URLs. The base URLs + *http.Client are injectable so tests drive it against
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
// one answers successfully — the resilience against apibay domain rotation.
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
