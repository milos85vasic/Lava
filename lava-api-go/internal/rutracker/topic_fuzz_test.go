package rutracker

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

// seedTopicFuzzCorpus loads every fixture under testdata/topic/ plus a
// handful of adversarial inputs covering the styles / nestings the
// post-body parser branches on.
func seedTopicFuzzCorpus(f *testing.F) {
	f.Helper()
	dir := filepath.Join("testdata", "topic")
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
	f.Add([]byte(""))
	f.Add([]byte("<html"))
	f.Add([]byte("\xff\xfe\xfd\xfc")) // invalid UTF-8
	// Deeply nested post-b elements — ParsePostBody recurses for each.
	deep := strings.Repeat(`<span class="post-b">`, 200) + "x" + strings.Repeat("</span>", 200)
	f.Add([]byte(deep))
	// Malformed style attribute.
	f.Add([]byte(`<span style="color:::::">x</span><span style="font-size:abc;text-align:nope">y</span>`))
	// Malformed q-wrap structure (missing children).
	f.Add([]byte(`<div class="q-wrap"></div><div class="c-wrap"></div><div class="sp-wrap"></div>`))
}

// FuzzParsePostBody exercises ParsePostBody with arbitrary bytes wrapped
// in a .post_body container. The parser MUST NOT panic.
func FuzzParsePostBody(f *testing.F) {
	seedTopicFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		// Wrap data in a post_body div so the entry shape matches the
		// production call site. We bound the input length here only to
		// keep individual fuzz iterations fast — adversarial inputs that
		// blow up resource-wise would be a separate DoS class, not a
		// correctness panic.
		if len(data) > 1<<16 {
			data = data[:1<<16]
		}
		html := append([]byte(`<html><body><div class="post_body">`), data...)
		html = append(html, []byte(`</div></body></html>`)...)
		doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
		if err != nil {
			return
		}
		_ = ParsePostBody(doc.Find(".post_body").First())
	})
}

func FuzzParseCommentsPage(f *testing.F) {
	seedTopicFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = ParseCommentsPage(data)
	})
}

func FuzzParseTopicPage(f *testing.F) {
	seedTopicFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = ParseTopicPage(data)
	})
}
