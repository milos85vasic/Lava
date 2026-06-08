package jackett

import "testing"

// These tests cover the itemToResult attribute-folding branches that the
// happy-path parser tests leave uncovered: a non-numeric seeders value, a
// non-numeric size value, the newznab:attr spelling some indexers emit, and
// infohash case-normalization. A real Torznab feed from a misbehaving indexer
// exercises exactly these defenses; a regression would silently corrupt the
// seeders/size a user sees in search results.

// TestParseResults_UnparseableSeedersStaysUnknown: a seeders attr whose value
// is not an integer (e.g. "N/A", empty, "many") MUST leave Seeders at the -1
// "unknown" sentinel rather than 0 — distinguishing "indexer didn't say" from
// "genuinely zero seeders", which the UI surfaces differently.
//
// FALSIFIABILITY: removing the `err == nil` guard on the seeders branch would
// make Atoi's 0-on-error overwrite the -1 sentinel, failing the want=-1 assert.
func TestParseResults_UnparseableSeedersStaysUnknown(t *testing.T) {
	const feed = `<?xml version="1.0"?>
<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
  <item><title>BadSeeders</title>
    <enclosure url="http://x/y" length="100" type="application/x-bittorrent"/>
    <torznab:attr name="seeders" value="N/A"/>
  </item>
</channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 item, got %d", len(results))
	}
	if results[0].Seeders != -1 {
		t.Errorf("unparseable seeders = %d, want -1 (unknown, not 0)", results[0].Seeders)
	}
}

// TestParseResults_UnparseableSizeFallsBackToEnclosure: a size attr that is not
// a valid int64 (e.g. "1.5 GB") MUST be ignored, and the size MUST fall back to
// the enclosure length. A user sorting/filtering by size relies on this.
//
// FALSIFIABILITY: removing the `err == nil` guard on the size branch would make
// ParseInt's 0-on-error be treated as a real 0, but since the fallback only
// triggers when r.Size == 0 the enclosure length would still apply — so the
// stronger assertion is that a *valid* attr wins over enclosure length (covered
// elsewhere) and an *invalid* attr yields the enclosure length here.
func TestParseResults_UnparseableSizeFallsBackToEnclosure(t *testing.T) {
	const feed = `<?xml version="1.0"?>
<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
  <item><title>BadSize</title>
    <enclosure url="http://x/y" length="734003200" type="application/x-bittorrent"/>
    <torznab:attr name="size" value="1.5 GB"/>
  </item>
</channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if results[0].Size != 734003200 {
		t.Errorf("size with unparseable attr = %d, want 734003200 (enclosure fallback)", results[0].Size)
	}
}

// TestParseResults_NewznabAttrSpelling: some indexers emit <newznab:attr> rather
// than <torznab:attr>. encoding/xml matches on the local name "attr" regardless
// of namespace prefix, so both spellings MUST fold identically into the Result.
//
// FALSIFIABILITY: if the rssItem Attrs tag were namespace-qualified to torznab
// only, the newznab attrs here would be dropped and infohash would be empty.
func TestParseResults_NewznabAttrSpelling(t *testing.T) {
	const feed = `<?xml version="1.0"?>
<rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
  <item><title>NewznabItem</title>
    <enclosure url="http://x/y" length="100" type="application/x-bittorrent"/>
    <newznab:attr name="seeders" value="42"/>
    <newznab:attr name="infohash" value="ABCDEF0123456789ABCDEF0123456789ABCDEF01"/>
  </item>
</channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if results[0].Seeders != 42 {
		t.Errorf("newznab seeders = %d, want 42", results[0].Seeders)
	}
	// Infohash MUST be lower-cased regardless of attr spelling.
	if results[0].Infohash != "abcdef0123456789abcdef0123456789abcdef01" {
		t.Errorf("infohash = %q, want lower-cased", results[0].Infohash)
	}
}

// TestParseResults_MagnetAttrPreferredOverEnclosure: when an explicit
// magneturl attr is present, it wins over any enclosure-derived magnet. This
// pins the MagnetURL precedence the download flow depends on.
func TestParseResults_MagnetAttrPreferredOverEnclosure(t *testing.T) {
	const wantMagnet = "magnet:?xt=urn:btih:deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
	const feed = `<?xml version="1.0"?>
<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
  <item><title>WithMagnetAttr</title>
    <enclosure url="http://x/dl?file=y" length="100" type="application/x-bittorrent"/>
    <torznab:attr name="magneturl" value="` + wantMagnet + `"/>
  </item>
</channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if results[0].MagnetURL != wantMagnet {
		t.Errorf("MagnetURL = %q, want explicit attr %q", results[0].MagnetURL, wantMagnet)
	}
	// The enclosure is an HTTP .torrent, so this is NOT a magnet enclosure.
	if results[0].IsMagnetEnclosure() {
		t.Errorf("IsMagnetEnclosure() = true for an HTTP .torrent enclosure")
	}
}
