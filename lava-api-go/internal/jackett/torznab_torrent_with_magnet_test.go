// torznab_torrent_with_magnet_test.go — real-fixture parse test for the
// cross-signal Torznab <item> shape that RuTracker-via-Jackett emits: a
// .torrent HTTP enclosure that ALSO carries magneturl + infohash + seeders
// attrs. The existing torznab_test.go fixture covers a pure-.torrent item and
// a pure-magnet item separately; this test covers the combined case where a
// download is offered as BOTH an HTTP .torrent AND a magnet, which is the
// common RuTracker reality and the case a real user's "download" tap depends
// on (the app may prefer the .torrent file but fall back to the magnet).
//
// Offline parser test (no network) — feeding a realistic RSS fixture into the
// real ParseResults and asserting the extracted struct fields IS real evidence
// of the parse logic per the Jackett dossier; the network round-trip is
// already covered by TestSearch_EndToEnd.
//
// FALSIFIABILITY (Sixth Law clause 2): in torznab.go itItemToResult, change the
// magnet-fallback guard so the .torrent's magneturl attr is dropped — e.g.
// replace `case attrMagnetURL: r.MagnetURL = strings.TrimSpace(a.Value)` with a
// no-op — and this test FAILS at:
//
//	MagnetURL = "" , want "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=sintel"
//
// Likewise dropping the infohash lower-casing (removing strings.ToLower) FAILS
// the Infohash assertion (the fixture's value is UPPER-case). Reverting either
// restores the contract.
package jackett

import (
	"os"
	"path/filepath"
	"testing"
)

func loadTorrentWithMagnetFixture(t *testing.T) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "torznab_torrent_with_magnet.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	return b
}

// TestParseResults_TorrentWithMagnetAndInfohash asserts every extracted field
// of a .torrent enclosure that also carries magneturl/infohash/seeders attrs.
func TestParseResults_TorrentWithMagnetAndInfohash(t *testing.T) {
	results, err := ParseResults(loadTorrentWithMagnetFixture(t))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 item, got %d", len(results))
	}
	got := results[0]

	// title
	if want := "Sintel 2010 1080p BluRay x264"; got.Title != want {
		t.Errorf("Title = %q, want %q", got.Title, want)
	}

	// .torrent enclosure URL (HTTP /dl/ proxy link, NOT the magnet) — this is
	// the field a real "download .torrent" tap dereferences.
	wantURL := "http://jackett:9117/dl/rutracker/?jackett_apikey=KEY&path=sintel&file=sintel"
	if got.DownloadURL != wantURL {
		t.Errorf("DownloadURL = %q, want %q", got.DownloadURL, wantURL)
	}
	if got.EnclosureType != EnclosureTypeTorrent {
		t.Errorf("EnclosureType = %q, want %q", got.EnclosureType, EnclosureTypeTorrent)
	}
	// Despite carrying a magneturl attr, the ENCLOSURE is a .torrent — the
	// item must NOT be classified as a magnet enclosure (the app would
	// otherwise skip the .torrent HTTP download path).
	if got.IsMagnetEnclosure() {
		t.Errorf("IsMagnetEnclosure() = true, want false (enclosure is application/x-bittorrent)")
	}

	// magnet — extracted from the explicit magneturl attr (NOT synthesized
	// from the HTTP enclosure URL).
	wantMagnet := "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=sintel"
	if got.MagnetURL != wantMagnet {
		t.Errorf("MagnetURL = %q, want %q", got.MagnetURL, wantMagnet)
	}

	// infohash — lower-cased from the fixture's UPPER-case value.
	wantHash := "08ada5a7a6183aae1e09d831df6748d566095a10"
	if got.Infohash != wantHash {
		t.Errorf("Infohash = %q, want %q (lower-cased)", got.Infohash, wantHash)
	}

	// seeders
	if got.Seeders != 1337 {
		t.Errorf("Seeders = %d, want 1337", got.Seeders)
	}

	// size — from the size attr.
	if got.Size != 2147483648 {
		t.Errorf("Size = %d, want 2147483648", got.Size)
	}
}
