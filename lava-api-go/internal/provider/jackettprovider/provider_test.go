package jackettprovider

import (
	"context"
	"errors"
	"testing"

	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/provider"
)

// fakeJackettClient is a test double for the narrow jackettClient surface the
// provider depends on. It is NOT the system under test (the SUT is
// JackettIndexerProvider); it only stands in for the jackett.Client network
// boundary so the provider's delegation + mapping logic runs for real.
type fakeJackettClient struct {
	searchIndexer string
	searchQuery   string
	searchResults []jackett.Result
	searchErr     error

	downloadURL string
	downloadRes  *jackett.DownloadResult
	downloadErr  error
}

func (f *fakeJackettClient) Search(_ context.Context, indexerID, query string) ([]jackett.Result, error) {
	f.searchIndexer = indexerID
	f.searchQuery = query
	return f.searchResults, f.searchErr
}

func (f *fakeJackettClient) Download(_ context.Context, downloadURL string) (*jackett.DownloadResult, error) {
	f.downloadURL = downloadURL
	return f.downloadRes, f.downloadErr
}

// TestProvider_Metadata asserts the catalogue-metadata the /v1/providers
// endpoint surfaces is correct for a Jackett indexer.
//
// Anti-bluff: the assertions are on the user-visible descriptor fields the
// client renders the provider list + auth UI from.
func TestProvider_Metadata(t *testing.T) {
	p := New("1337x", "1337x", &fakeJackettClient{})

	if got := p.ID(); got != "1337x" {
		t.Errorf("ID() = %q, want 1337x", got)
	}
	if got := p.DisplayName(); got != "1337x" {
		t.Errorf("DisplayName() = %q, want 1337x", got)
	}
	if got := p.Kind(); got != "jackett" {
		t.Errorf("Kind() = %q, want jackett", got)
	}
	if got := p.AuthType(); got != provider.AuthNone {
		t.Errorf("AuthType() = %q, want NONE", got)
	}
	if !p.SupportsAnonymous() {
		t.Error("SupportsAnonymous() = false, want true")
	}
	if got := p.Encoding(); got != "UTF-8" {
		t.Errorf("Encoding() = %q, want UTF-8", got)
	}
	// Capability honesty (§6.E): the declared caps are exactly the ones the
	// provider can actually serve.
	wantCaps := map[provider.ProviderCapability]bool{
		provider.CapSearch:          true,
		provider.CapMagnetLink:      true,
		provider.CapTorrentDownload: true,
	}
	if len(p.Capabilities()) != len(wantCaps) {
		t.Fatalf("Capabilities() = %v, want %d caps", p.Capabilities(), len(wantCaps))
	}
	for _, c := range p.Capabilities() {
		if !wantCaps[c] {
			t.Errorf("unexpected capability %q", c)
		}
	}
}

// TestProvider_SearchDelegatesWithIndexerIDAndMaps asserts Search passes the
// provider's OWN indexer id (not the query, not "all") to the jackett client,
// and maps the parsed Torznab results into the uniform provider.SearchResult.
//
// Bluff-Audit:
//
//	Test:     TestProvider_SearchDelegatesWithIndexerIDAndMaps
//	Mutation: in Search(), delegate with a hardcoded "all" instead of a.indexerID.
//	Observed: "delegated indexer = \"all\", want 1337x".
//	Reverted: yes.
func TestProvider_SearchDelegatesWithIndexerIDAndMaps(t *testing.T) {
	fake := &fakeJackettClient{
		searchResults: []jackett.Result{
			{
				Title:         "Ubuntu 24.04",
				GUID:          "guid-1",
				DownloadURL:   "http://jackett/dl/1337x/abc",
				EnclosureType: jackett.EnclosureTypeTorrent,
				Seeders:       42,
				Size:          1460985071,
				Infohash:      "deadbeef",
			},
			{
				Title:         "Debian 12",
				GUID:          "magnet:?xt=urn:btih:cafe",
				DownloadURL:   "magnet:?xt=urn:btih:cafe",
				EnclosureType: jackett.EnclosureTypeMagnet,
				Seeders:       7,
			},
		},
	}
	p := New("1337x", "1337x", fake)

	res, err := p.Search(context.Background(), provider.SearchOpts{Query: "ubuntu"}, provider.Credentials{})
	if err != nil {
		t.Fatalf("Search: %v", err)
	}

	// Delegation: the provider's OWN indexer id + the query reached the client.
	if fake.searchIndexer != "1337x" {
		t.Errorf("delegated indexer = %q, want 1337x", fake.searchIndexer)
	}
	if fake.searchQuery != "ubuntu" {
		t.Errorf("delegated query = %q, want ubuntu", fake.searchQuery)
	}

	// Mapping: the user-visible result rows.
	if res.Provider != "1337x" {
		t.Errorf("result.Provider = %q, want 1337x", res.Provider)
	}
	if len(res.Results) != 2 {
		t.Fatalf("results len = %d, want 2", len(res.Results))
	}
	torrent := res.Results[0]
	if torrent.Title != "Ubuntu 24.04" || torrent.Seeders != 42 || torrent.DownloadURL != "http://jackett/dl/1337x/abc" {
		t.Errorf("torrent row mismapped: %+v", torrent)
	}
	if torrent.InfoHash != "deadbeef" || torrent.SizeBytes != 1460985071 {
		t.Errorf("torrent infohash/size mismapped: %+v", torrent)
	}
	magnet := res.Results[1]
	if magnet.MagnetLink != "magnet:?xt=urn:btih:cafe" {
		t.Errorf("magnet row MagnetLink = %q, want the magnet URI", magnet.MagnetLink)
	}
}

// TestProvider_DownloadFileDelegates asserts DownloadFile resolves the id via
// the jackett client's Download and surfaces the torrent bytes.
func TestProvider_DownloadFileDelegates(t *testing.T) {
	fake := &fakeJackettClient{
		downloadRes: &jackett.DownloadResult{
			TorrentBytes: []byte("d8:announce..."),
			ContentType:  "application/x-bittorrent",
		},
	}
	p := New("1337x", "1337x", fake)

	dl, err := p.DownloadFile(context.Background(), "http://jackett/dl/1337x/abc", provider.Credentials{})
	if err != nil {
		t.Fatalf("DownloadFile: %v", err)
	}
	if fake.downloadURL != "http://jackett/dl/1337x/abc" {
		t.Errorf("delegated download url = %q", fake.downloadURL)
	}
	if string(dl.Body) != "d8:announce..." {
		t.Errorf("download body = %q, want the .torrent bytes", string(dl.Body))
	}
	if dl.Provider != "1337x" {
		t.Errorf("download.Provider = %q, want 1337x", dl.Provider)
	}
}

// TestProvider_ExtendedCapsUnsupported asserts an extended capability the
// indexer does NOT declare returns the standard not-implemented sentinel
// (capability honesty, §6.E — the middleware 501s before reaching here, but the
// method itself must also honestly refuse).
func TestProvider_ExtendedCapsUnsupported(t *testing.T) {
	p := New("1337x", "1337x", &fakeJackettClient{})

	if _, err := p.GetForumTree(context.Background(), provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetForumTree err = %v, want ErrUnsupported", err)
	}
	if _, err := p.GetTopic(context.Background(), "x", 1, provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetTopic err = %v, want ErrUnsupported", err)
	}
	if _, err := p.Login(context.Background(), provider.LoginOpts{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("Login err = %v, want ErrUnsupported", err)
	}
	if _, err := p.GetComments(context.Background(), "x", 1, provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetComments err = %v, want ErrUnsupported", err)
	}
}

// TestProvider_HealthCheckHealthy asserts the provider reports healthy (the
// Jackett sidecar health is the API's concern, not the per-indexer provider's).
func TestProvider_HealthCheckHealthy(t *testing.T) {
	p := New("1337x", "1337x", &fakeJackettClient{})
	hs, err := p.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck: %v", err)
	}
	if !hs.Healthy {
		t.Error("HealthCheck Healthy = false, want true")
	}
}

// compile-time: the SUT satisfies the full Provider interface.
var _ provider.Provider = (*JackettIndexerProvider)(nil)
