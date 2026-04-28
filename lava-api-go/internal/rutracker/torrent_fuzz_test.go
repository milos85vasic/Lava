package rutracker

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// seedTorrentFuzzCorpus loads every fixture under testdata/torrent/ and
// adds a handful of adversarial inputs covering the size-branch
// substring trick, malformed magnet anchors, missing #topic-title,
// truncated bytes, and invalid UTF-8.
func seedTorrentFuzzCorpus(f *testing.F) {
	f.Helper()
	dir := filepath.Join("testdata", "torrent")
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
	// Truncated input — a partial response from a hung upstream.
	f.Add([]byte("<html><body><a id=\"topic-title\" href=\"viewtopic.php?t=1\">incomplete"))
	// Deeply nested post body — exercise the recursion in ParsePostBody
	// reached via parseTorrentDescription.
	deep := "<html><body><tbody id=\"post1\"><div class=\"post_body\">" +
		strings.Repeat(`<span class="post-b">`, 200) + "x" + strings.Repeat("</span>", 200) +
		"</div></tbody></body></html>"
	f.Add([]byte(deep))
	// Malformed magnet attribute (not a magnet URL).
	f.Add([]byte(`<html><body><a id="topic-title" href="viewtopic.php?t=2">x</a><a class="magnet-link" href="not a magnet">m</a></body></html>`))
	// No #topic-title at all.
	f.Add([]byte(`<html><body><p>no topic title here, just text</p></body></html>`))
	// Invalid UTF-8.
	f.Add([]byte("\xff\xfe\xfd\xfc"))
}

// FuzzParseTorrent exercises ParseTorrent with arbitrary bytes. The
// parser MUST NOT panic.
func FuzzParseTorrent(f *testing.F) {
	seedTorrentFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		// Cap input length per iteration — adversarial DoS-shaped
		// inputs are a separate class from correctness panics.
		if len(data) > 1<<16 {
			data = data[:1<<16]
		}
		_, _ = ParseTorrent(data)
	})
}
