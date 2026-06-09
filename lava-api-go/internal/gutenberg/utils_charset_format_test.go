package gutenberg

import "testing"

// LVA-022: Gutendex emits text MIME keys WITH a charset suffix
// ("text/plain; charset=us-ascii", "text/html; charset=utf-8"). The prior
// exact-key matching produced a BLANK format label for the large class of
// text-only books and skipped the preferred ordering in URL selection.

// Falsifiability: revert bestFormatName to exact-key matching
// (`if _, ok := formats["text/plain"]`) → this returns "" and the test fails
// "bestFormatName(charset-suffixed) = \"\", want Text".
func TestBestFormatName_CharsetSuffixedTextKey_NotBlank(t *testing.T) {
	formats := map[string]string{
		"text/html; charset=utf-8":     "https://g/h.html",
		"text/plain; charset=us-ascii": "https://g/t.txt",
	}
	if got := bestFormatName(formats); got != "Text" {
		t.Fatalf("bestFormatName(charset-suffixed) = %q, want Text (was blank before the fix)", got)
	}
}

// Falsifiability: revert pickBestFormatURL to exact-key matching → the
// charset-suffixed text/plain key is skipped and the sorted-key fallback
// returns the pdf url, failing this test.
func TestPickBestFormatURL_PrefersCharsetText_OverPdf(t *testing.T) {
	formats := map[string]string{
		"application/pdf":           "https://g/book.pdf",
		"text/plain; charset=utf-8": "https://g/book.txt",
	}
	if got := pickBestFormatURL(formats); got != "https://g/book.txt" {
		t.Fatalf("pickBestFormatURL = %q, want the text/plain url (prefix-matched, preferred over pdf)", got)
	}
}

// Exact bare keys must still work (regression guard for the common case).
func TestFormatHelpers_BareKeysStillWork(t *testing.T) {
	formats := map[string]string{"application/epub+zip": "https://g/b.epub"}
	if got := bestFormatName(formats); got != "EPUB" {
		t.Fatalf("bestFormatName(epub) = %q, want EPUB", got)
	}
	if got := pickBestFormatURL(formats); got != "https://g/b.epub" {
		t.Fatalf("pickBestFormatURL(epub) = %q, want the epub url", got)
	}
}
