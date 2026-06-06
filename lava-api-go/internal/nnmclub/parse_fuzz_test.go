package nnmclub

import (
	"os"
	"path/filepath"
	"testing"
)

func fuzzSeed(f *testing.F, name string) {
	f.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err == nil {
		f.Add(b)
	}
}

// FuzzParseSearchPage fuzzes the nnmclub tracker.php parser against untrusted
// upstream HTML. Invariants: never panic; TotalPages >= 1; every emitted row
// has a non-empty ID (id-less rows must be skipped, else the client's detail
// nav breaks).
func FuzzParseSearchPage(f *testing.F) {
	fuzzSeed(f, "search/search_results.html")
	fuzzSeed(f, "search/search_empty.html")
	f.Add([]byte(``))
	f.Add([]byte(`<table class="forumline"><tr><a class="genmed" href="viewtopic.php?t=">e</a></tr></table>`))
	f.Add([]byte(`<table class="forumline"><tr><a class="genmed" href="%zz">bad</a></tr></table>`))
	f.Add([]byte(`<a href="tracker.php?start=notanumber">p</a>`))

	f.Fuzz(func(t *testing.T, html []byte) {
		res, err := ParseSearchPage(html, 1)
		if err != nil {
			return
		}
		if res == nil {
			t.Fatal("nil result, nil error")
		}
		if res.TotalPages < 1 {
			t.Fatalf("TotalPages = %d, want >= 1", res.TotalPages)
		}
		for i, item := range res.Results {
			if item.ID == "" {
				t.Fatalf("Results[%d] empty ID — id-less rows must be skipped", i)
			}
		}
	})
}

// FuzzParseBrowsePage fuzzes the viewforum.php parser (shares the row
// selectors with search). Same empty-ID invariant.
func FuzzParseBrowsePage(f *testing.F) {
	fuzzSeed(f, "browse/browse_results.html")
	f.Add([]byte(``))
	f.Add([]byte(`<table class="forumline"><tr><a class="genmed" href="viewtopic.php?t=5">x</a></tr></table>`))

	f.Fuzz(func(t *testing.T, html []byte) {
		res, err := ParseBrowsePage(html, 1)
		if err != nil {
			return
		}
		if res == nil {
			t.Fatal("nil result, nil error")
		}
		for i, item := range res.Items {
			if item.ID == "" {
				t.Fatalf("Items[%d] empty ID — id-less rows must be skipped", i)
			}
		}
	})
}

// FuzzParseTopicPage fuzzes the viewtopic.php parser. The id is supplied by
// the caller, so the result ID must equal the passed id (never crash, never
// drop the id).
func FuzzParseTopicPage(f *testing.F) {
	fuzzSeed(f, "topic/topic_normal.html")
	f.Add([]byte(``))
	f.Add([]byte(`<div id="pagecontent"><div class="postbody">x</div></div>`))
	f.Add([]byte(`<a href="magnet:?xt=urn:btih:abc">m</a>`))

	f.Fuzz(func(t *testing.T, html []byte) {
		res, err := ParseTopicPage(html, "999")
		if err != nil {
			return
		}
		if res == nil {
			t.Fatal("nil result, nil error")
		}
		if res.ID != "999" {
			t.Fatalf("ID = %q, want 999 (caller-supplied id preserved)", res.ID)
		}
		if res.Provider != "nnmclub" {
			t.Fatalf("Provider = %q, want nnmclub", res.Provider)
		}
	})
}
