package kinozal

import (
	"testing"
)

// FuzzParseSearchPage fuzzes the kinozal search/browse HTML parser. The
// parser ingests untrusted upstream HTML (kinozal.tv), so malformed,
// truncated, or adversarial markup must never panic and must never emit a
// result row with an empty ID (the production code explicitly skips id-less
// rows — an empty ID would crash the Android client's detail navigation).
func FuzzParseSearchPage(f *testing.F) {
	// Seed with the real testdata corpus + structural edge cases.
	f.Add(loadTestData("search/search_results.html"))
	f.Add(loadTestData("browse/browse_results.html"))
	f.Add([]byte(``))
	f.Add([]byte(`<html`))
	f.Add([]byte(`<table class="tumblers"><tr><a class="namer" href="/details.php?id=">empty</a></tr></table>`))
	f.Add([]byte(`<table class="tumblers"><tr><a class="namer" href="%zz">bad-url</a></tr></table>`))
	f.Add([]byte(`<table class="tumblers"><tr><span class="sider">S: notanumber</span></tr></table>`))

	f.Fuzz(func(t *testing.T, html []byte) {
		res, err := ParseSearchPage(html)
		if err != nil {
			// A parse error is an acceptable outcome for garbage input; the
			// invariant is "no panic", which we reached.
			return
		}
		if res == nil {
			t.Fatal("nil result with nil error")
		}
		if res.TotalPages < 1 {
			t.Fatalf("TotalPages = %d, want >= 1 (paging invariant)", res.TotalPages)
		}
		for i, item := range res.Results {
			if item.ID == "" {
				t.Fatalf("Results[%d] has empty ID — id-less rows must be skipped", i)
			}
		}
	})
}

// FuzzParseTopicPage fuzzes the kinozal topic detail parser. Malformed topic
// HTML must never panic; the returned struct is always provider="kinozal".
func FuzzParseTopicPage(f *testing.F) {
	f.Add(loadTestData("topic/topic.html"))
	f.Add([]byte(``))
	f.Add([]byte(`<h1>`))
	f.Add([]byte(`<a class="magnet" href="magnet:?xt=urn:btih:abc">m</a>`))
	f.Add([]byte(`<a href="details.php?id=%zz">bad</a>`))

	f.Fuzz(func(t *testing.T, html []byte) {
		res, err := ParseTopicPage(html)
		if err != nil {
			return
		}
		if res == nil {
			t.Fatal("nil result with nil error")
		}
		if res.Provider != "kinozal" {
			t.Fatalf("Provider = %q, want kinozal", res.Provider)
		}
	})
}
