package rutracker

import (
	"os"
	"path/filepath"
	"testing"
)

// seedSearchFuzzCorpus loads every *.html file in testdata/search/ as
// fuzz seeds. Same rationale as the forum scraper's seedFuzzCorpus —
// the parser is exposed to attacker-controlled bytes via the rutracker
// HTTP response and a panic on ANY input is a Sixth Law violation.
func seedSearchFuzzCorpus(f *testing.F) {
	f.Helper()
	dir := filepath.Join("testdata", "search")
	entries, err := os.ReadDir(dir)
	if err != nil {
		f.Fatalf("read fixture dir: %v", err)
	}
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".html" {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			f.Fatalf("read fixture %s: %v", e.Name(), err)
		}
		f.Add(b)
	}
	// Adversarial seeds: truncated, deeply nested, invalid UTF-8,
	// empty data-ts_text, malformed numerics in the .seedmed slot.
	f.Add([]byte(""))
	f.Add([]byte("<html"))
	f.Add([]byte(`<div class="hl-tr"><span class="tor-size" data-ts_text=""></span></div>`))
	f.Add([]byte("\xff\xfe\xfd\xfc"))
	f.Add([]byte(`<tr class="hl-tr"><span class="seedmed">notanumber</span><span class="tor-size" data-ts_text="-1">x</span></tr>`))
}

func FuzzParseSearchPage(f *testing.F) {
	seedSearchFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		_, err := ParseSearchPage(data)
		// Contract: no panic on any input. Errors are permitted (a
		// truly malformed reader can fail goquery), values returned
		// alongside nil error are also acceptable. We assert only the
		// no-panic invariant.
		_ = err
	})
}
