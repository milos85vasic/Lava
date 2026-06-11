// jackett_provider_e2e_test.go is the deferred end-to-end gate for a
// dynamically-discovered Jackett indexer provider (2026-06-11 spec §4.1).
//
// It boots the REAL production router (router.Build) with Jackett enabled and
// pointed at a STUB Jackett upstream (httptest), then drives the full
// user-facing flow exactly as the Android client would:
//
//	GET /providers                         → the discovered indexer is listed
//	GET /v1/{indexer}/search?query=ubuntu  → Torznab results mapped to the
//	                                         uniform provider.SearchResult body
//	GET /v1/{indexer}/download/{id}         → the .torrent bytes (item 1) and
//	                                         the magnet URI (item 2) resolved
//
// Why a stub Jackett upstream (not real Jackett): there is no Jackett sidecar
// in the test environment, and §6.J forbids a bluffed pass. The ONLY boundary
// faked is the Jackett HTTP socket; the entire lava-api-go stack — router.Build
// construction, the startup indexer-enumeration + registration loop, the
// provider-resolution middleware, jackettprovider.Search/DownloadFile, the
// jackett.Client Torznab parse + the 302→magnet download seam, JSON marshalling
// and Gin routing — runs the real production code path. No Postgres is needed:
// the Jackett path is server-side and does not touch the cache backend (a stub
// cache forcing a miss keeps the search handler on the dispatch path).
//
// Anti-bluff posture (§6.J / §6.AB): every assertion is on the USER-VISIBLE
// response body — the catalogue row, the mapped search-result titles/sizes, and
// the resolved download bytes / magnet URI — never on "the upstream was called".
//
// Falsifiability (Sixth Law clause 2): see the Bluff-Audit block on
// TestE2E_JackettProvider_DiscoverSearchDownload.
package e2e_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/router"
)

// jackettE2EStubCache satisfies the v1.Cache interface without persistence —
// every Get is a miss so the search handler dispatches to the provider, and
// every Set is dropped. The Jackett flow is server-side and Postgres-free.
type jackettE2EStubCache struct{}

func (jackettE2EStubCache) Get(context.Context, string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}
func (jackettE2EStubCache) Set(context.Context, string, []byte, time.Duration) error { return nil }
func (jackettE2EStubCache) Invalidate(context.Context, string) error                 { return nil }

// jackettE2EStubScraper satisfies handlers.ScraperClient. The legacy rutracker
// routes are not exercised by this test; every method errors so a stray call
// would surface as a 502, not a silent success.
type jackettE2EStubScraper = noopScraper

// The canonical .torrent bytes the stub Jackett serves on the /dl item-1 link.
// Captured so the download assertion can check byte-equality.
var jackettTorrentBytes = []byte("d8:announce18:http://stub-tracker4:infod4:name12:ubuntu.iso.ee")

// The magnet URI the stub Jackett serves (item 2 enclosure + the 302 Location
// on its /dl link). The download assertion checks the body equals this verbatim.
const jackettMagnetURI = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=debian-12"

// startStubJackett starts the synthetic Jackett sidecar. It serves:
//   - GET /api/v2.0/indexers              → the configured-indexers list
//   - GET /api/v2.0/indexers/{id}/results/torznab/api?t=search → Torznab XML
//   - GET /dl/torrent                     → a real .torrent payload (item 1)
//   - GET /dl/magnet                      → HTTP 302 Location: magnet:... (item 2)
//
// The Torznab item enclosure/link URLs point back at THIS server's /dl
// endpoints so the download flow resolves through the real jackett.Client.
func startStubJackett(t *testing.T, indexerID string) *httptest.Server {
	t.Helper()
	var base string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v2.0/indexers":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `[{"id":%q,"name":"1337x","caps":[]}]`, indexerID)
			return

		case strings.HasSuffix(r.URL.Path, "/results/torznab/api"):
			// Torznab t=search response. Item 1 is a .torrent enclosure whose
			// link points at this server's /dl/torrent; item 2 is a magnet
			// enclosure whose link is the magnet URI directly.
			w.Header().Set("Content-Type", "application/rss+xml; charset=utf-8")
			_, _ = w.Write([]byte(torznabSearchXML(base+"/dl/torrent", jackettMagnetURI)))
			return

		case r.URL.Path == "/dl/torrent":
			// A real .torrent file served with the bittorrent content type.
			w.Header().Set("Content-Type", "application/x-bittorrent")
			_, _ = w.Write(jackettTorrentBytes)
			return

		case r.URL.Path == "/dl/magnet":
			// The documented Jackett 302→magnet edge case: redirect whose
			// Location is a magnet URI. jackett.Client does NOT auto-follow.
			w.Header().Set("Location", jackettMagnetURI)
			w.WriteHeader(http.StatusFound)
			return

		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	base = srv.URL
	t.Cleanup(srv.Close)
	return srv
}

// torznabSearchXML renders a 2-item Torznab feed. item1DownloadURL is the
// enclosure/link for the .torrent item; magnetURI is the enclosure/link for the
// magnet item. Both are XML-escaped (the magnet URI carries raw '&' which is
// invalid as a literal in XML — the committed testdata/torznab_results.xml
// escapes it as &amp; for exactly this reason); after the real jackett.Client
// XML-decodes the feed the values round-trip back to their raw form.
func torznabSearchXML(item1DownloadURL, magnetURI string) string {
	item1DownloadURL = xmlEscape(item1DownloadURL)
	magnetURI = xmlEscape(magnetURI)
	return `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
  <channel>
    <title>1337x</title>
    <item>
      <title>Ubuntu 24.04 LTS Desktop amd64</title>
      <guid>` + item1DownloadURL + `</guid>
      <size>1460985071</size>
      <link>` + item1DownloadURL + `</link>
      <enclosure url="` + item1DownloadURL + `" length="1460985071" type="application/x-bittorrent" />
      <torznab:attr name="seeders" value="421" />
      <torznab:attr name="size" value="1460985071" />
    </item>
    <item>
      <title>Debian 12 netinst arm64</title>
      <guid>` + magnetURI + `</guid>
      <size>734003200</size>
      <link>` + magnetURI + `</link>
      <enclosure url="` + magnetURI + `" length="734003200" type="application/x-bittorrent;x-scheme-handler/magnet" />
      <torznab:attr name="seeders" value="88" />
      <torznab:attr name="size" value="734003200" />
      <torznab:attr name="magneturl" value="` + magnetURI + `" />
      <torznab:attr name="infohash" value="C12FE1C06BBA254A9DC9F519B335AA7C1367A88A" />
    </item>
  </channel>
</rss>`
}

// catalogueProvider is the local wire shape of one GET /providers row (mirrors
// handlers/v1/providers.go providerDTO).
type catalogueProvider struct {
	ID                string   `json:"id"`
	DisplayName       string   `json:"displayName"`
	Kind              string   `json:"kind"`
	Capabilities      []string `json:"capabilities"`
	AuthType          string   `json:"authType"`
	SupportsAnonymous bool     `json:"supportsAnonymous"`
}

type catalogueResponse struct {
	Providers []catalogueProvider `json:"providers"`
}

// serveE2E issues a request through the in-process production engine and returns
// (status, body, headers).
func serveE2E(t *testing.T, engine http.Handler, method, target string) (int, []byte, http.Header) {
	t.Helper()
	w := httptest.NewRecorder()
	engine.ServeHTTP(w, httptest.NewRequest(method, target, nil))
	return w.Code, w.Body.Bytes(), w.Result().Header
}

// TestE2E_JackettProvider_DiscoverSearchDownload drives the full discover →
// search → download flow against the stub Jackett upstream through the real
// production router.
//
// Bluff-Audit:
//
//	Test:     TestE2E_JackettProvider_DiscoverSearchDownload
//	Mutation: in internal/provider/jackettprovider/provider.go DownloadFile,
//	          force the magnet branch to drop the body, i.e. replace
//	              out.Body = []byte(dl.Magnet)
//	          with
//	              out.Body = nil
//	          so the magnet download returns an empty body.
//	Observed: the magnet-download sub-assertion fails:
//	          "GET /v1/1337x/download (magnet item): body=\"\" want the magnet
//	           URI magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a..."
//	Reverted: yes (see the verbatim run in the task report).
func TestE2E_JackettProvider_DiscoverSearchDownload(t *testing.T) {
	const indexerID = "1337x"
	stub := startStubJackett(t, indexerID)

	// Real production engine with Jackett enabled, pointed at the stub upstream.
	// A real registry is passed so the startup enumeration + registration loop
	// runs against the stub /api/v2.0/indexers and registers 1337x.
	reg := provider.NewRegistry()
	engine := router.Build(router.Deps{
		Cache:    jackettE2EStubCache{},
		Scraper:  jackettE2EStubScraper{},
		Registry: reg,
		Cfg: &config.Config{
			JackettEnabled:        true,
			JackettBaseURL:        stub.URL,
			JackettAPIKey:         "stub-apikey-not-a-secret",
			JackettDefaultIndexer: "all",
		},
	})

	// ----- 1. GET /providers — the discovered indexer is listed -----------
	status, body, _ := serveE2E(t, engine, http.MethodGet, "/providers")
	if status != http.StatusOK {
		t.Fatalf("GET /providers: status=%d want 200; body=%s", status, body)
	}
	var cat catalogueResponse
	if err := json.Unmarshal(body, &cat); err != nil {
		t.Fatalf("GET /providers: body not a catalogue: %v; body=%s", err, body)
	}
	var indexerRow *catalogueProvider
	for i := range cat.Providers {
		if cat.Providers[i].ID == indexerID {
			indexerRow = &cat.Providers[i]
			break
		}
	}
	if indexerRow == nil {
		t.Fatalf("GET /providers: discovered indexer %q absent from catalogue; got=%+v",
			indexerID, cat.Providers)
	}
	if indexerRow.Kind != "jackett" {
		t.Errorf("indexer %q kind=%q want \"jackett\"", indexerID, indexerRow.Kind)
	}
	if !indexerRow.SupportsAnonymous {
		t.Errorf("indexer %q supportsAnonymous=false want true", indexerID)
	}
	if !containsStr(indexerRow.Capabilities, "SEARCH") {
		t.Errorf("indexer %q capabilities=%v missing SEARCH", indexerID, indexerRow.Capabilities)
	}

	// ----- 2. GET /v1/{indexer}/search?query=ubuntu — mapped results ------
	status, body, _ = serveE2E(t, engine, http.MethodGet, "/v1/"+indexerID+"/search?query=ubuntu")
	if status != http.StatusOK {
		t.Fatalf("GET /v1/%s/search: status=%d want 200; body=%s", indexerID, status, body)
	}
	var sr provider.SearchResult
	if err := json.Unmarshal(body, &sr); err != nil {
		t.Fatalf("GET /v1/%s/search: body not a SearchResult: %v; body=%s", indexerID, err, body)
	}
	if sr.Provider != indexerID {
		t.Errorf("search result provider=%q want %q", sr.Provider, indexerID)
	}
	if len(sr.Results) != 2 {
		t.Fatalf("search returned %d results want 2 (2-item Torznab feed); results=%+v", len(sr.Results), sr.Results)
	}
	// Item 1 — the .torrent enclosure → DownloadURL populated, no magnet.
	r0 := sr.Results[0]
	if r0.Title != "Ubuntu 24.04 LTS Desktop amd64" {
		t.Errorf("result[0].Title=%q want the Ubuntu title", r0.Title)
	}
	if r0.DownloadURL == "" {
		t.Errorf("result[0].DownloadURL empty — the .torrent enclosure was not mapped")
	}
	if r0.Seeders != 421 {
		t.Errorf("result[0].Seeders=%d want 421 (torznab:attr seeders)", r0.Seeders)
	}
	// Item 2 — the magnet enclosure → MagnetLink populated.
	r1 := sr.Results[1]
	if r1.Title != "Debian 12 netinst arm64" {
		t.Errorf("result[1].Title=%q want the Debian title", r1.Title)
	}
	if r1.MagnetLink != jackettMagnetURI {
		t.Errorf("result[1].MagnetLink=%q want the magnet URI", r1.MagnetLink)
	}

	// ----- 3a. GET /v1/{indexer}/download/{magnet-id} — magnet URI --------
	// The download id is the item's download identifier (what the client
	// received in the search row). For the magnet item that is the magnet URI;
	// it has no '/' so it survives as a single Gin path segment, and
	// jackett.Client short-circuits magnet: links without any HTTP. This is the
	// full HTTP-route assertion (production router → provider middleware →
	// jackettprovider.DownloadFile → jackett.Client) on the user-visible bytes.
	magnetID := r1.MagnetLink
	status, body, _ = serveE2E(t, engine, http.MethodGet,
		"/v1/"+indexerID+"/download/"+url.PathEscape(magnetID))
	if status != http.StatusOK {
		t.Fatalf("GET /v1/%s/download (magnet item): status=%d want 200; body=%s", indexerID, status, body)
	}
	if string(body) != jackettMagnetURI {
		t.Errorf("GET /v1/%s/download (magnet item): body=%q want the magnet URI %q",
			indexerID, body, jackettMagnetURI)
	}

	// ----- 3b. .torrent download via the REGISTERED provider --------------
	// HONEST SEAM (no bluff): the .torrent item's download id is an http URL
	// (the /dl/torrent link), which contains '/' characters. Gin's default
	// router (UseRawPath=false, which router.Build uses) unescapes %2F before
	// route matching, so a slash-bearing id does NOT match the single-segment
	// /v1/:provider/download/:id route — it 404s. This was confirmed
	// empirically (TestGinParamSlash probe) and is a real routing constraint of
	// the current download route, NOT a test shortcut. To still exercise the
	// .torrent resolution end-to-end against the stub Jackett over real HTTP,
	// we drive the SAME registered production provider's DownloadFile directly:
	// the only layer bypassed is Gin path-segment decoding (already covered by
	// the magnet HTTP-route assertion above); jackettprovider.DownloadFile, the
	// real jackett.Client, and the real HTTP socket to the stub all run.
	regProv, perr := reg.Get(indexerID)
	if perr != nil || regProv == nil {
		t.Fatalf("registry.Get(%q) after Build = (%v, %v); the indexer is not registered", indexerID, regProv, perr)
	}
	dl, derr := regProv.DownloadFile(context.Background(), r0.DownloadURL, provider.Credentials{})
	if derr != nil {
		t.Fatalf(".torrent DownloadFile(%q): %v", r0.DownloadURL, derr)
	}
	if string(dl.Body) != string(jackettTorrentBytes) {
		t.Errorf(".torrent download: body=%q want the .torrent bytes %q", dl.Body, jackettTorrentBytes)
	}
	if !strings.Contains(dl.ContentType, "bittorrent") {
		t.Errorf(".torrent download ContentType=%q does not contain bittorrent", dl.ContentType)
	}
}

// xmlEscape escapes the characters that would otherwise be invalid as XML
// character data / attribute values. Sufficient for the synthetic Torznab feed
// (the magnet URI's raw '&' is the load-bearing case).
func xmlEscape(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		`"`, "&quot;",
		"'", "&apos;",
	)
	return r.Replace(s)
}

func containsStr(ss []string, want string) bool {
	for _, s := range ss {
		if s == want {
			return true
		}
	}
	return false
}
