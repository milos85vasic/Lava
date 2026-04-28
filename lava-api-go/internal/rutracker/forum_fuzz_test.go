package rutracker

import (
	"os"
	"path/filepath"
	"testing"
)

// seedFuzzCorpus loads every *.html file in testdata/forum/ into the
// fuzz corpus. The seeds give the fuzz engine a head start on realistic
// DOM shapes; the engine then mutates from there. A panic in the parser
// for ANY input — well-formed or adversarial — is a Sixth Law violation
// (the parser is exposed to attacker-controlled bytes via the upstream
// HTTP response and MUST not crash the process).
func seedFuzzCorpus(f *testing.F) {
	f.Helper()
	dir := filepath.Join("testdata", "forum")
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
	// A handful of explicit adversarial seeds.
	f.Add([]byte(""))                          // empty
	f.Add([]byte("<html"))                     // truncated
	f.Add([]byte(`<<<>>><div class="tree-root">`))
	f.Add([]byte("\xff\xfe\xfd\xfc"))          // invalid UTF-8
	f.Add([]byte(`<div class="tree-root"><div><div></div><div></div></div></div>`))
}

func FuzzParseForum(f *testing.F) {
	seedFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		_, err := ParseForum(data)
		// We do NOT assert err==nil: malformed bytes can legitimately
		// produce an error. We DO assert the parser does not panic.
		// And the contract is: either err != nil OR result was returned;
		// they are not mutually exclusive in our parser (it returns
		// ForumDto with empty children for "no .tree-root" rather than
		// erroring), so we only require "no panic".
		_ = err
	})
}

func FuzzParseCategoryPage(f *testing.F) {
	seedFuzzCorpus(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		_, err := ParseCategoryPage(data, "1")
		_ = err
	})
}
