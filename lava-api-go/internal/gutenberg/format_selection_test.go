// Package gutenberg — format_selection_test.go is a §6.L 68th
// table-driven unit test for the previously-uncovered download-format
// selection mappers in utils.go (pickBestFormatURL, bestFormatName).
//
// These two functions decide which downloadable file URL a Gutenberg
// book exposes to the user and what label the UI shows — directly
// user-visible behavior. A bug here means the user downloads the wrong
// format (or no file at all) while every higher-level test still passes,
// because the higher-level tests use synthetic format maps that happen
// to contain the first-preferred MIME type. This is exactly the
// "would a bug here be invisible to existing tests?" filter from §6.N.2:
// yes — so it is a bluff-rich target.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//   Mutation: in utils.go pickBestFormatURL, reverse the `preferred`
//     slice so application/pdf is preferred over application/epub+zip.
//   Observed: TestPickBestFormatURL_PrefersEpubOverPdf FAILS:
//     "picked %q want the EPUB url" with got=the pdf URL.
//   Reverted: yes (production code restored; final commit unmutated).
package gutenberg

import "testing"

const (
	epubURL = "https://gutenberg.example/1342.epub"
	textURL = "https://gutenberg.example/1342.txt"
	htmlURL = "https://gutenberg.example/1342.html"
	pdfURL  = "https://gutenberg.example/1342.pdf"
)

// TestPickBestFormatURL_PrefersEpubOverPdf is the load-bearing
// preference-order test: when both EPUB and PDF are available, the user
// MUST be handed the EPUB (the first entry in the preference list).
func TestPickBestFormatURL_PrefersEpubOverPdf(t *testing.T) {
	formats := map[string]string{
		"application/pdf":      pdfURL,
		"application/epub+zip": epubURL,
		"text/html":            htmlURL,
	}
	got := pickBestFormatURL(formats)
	if got != epubURL {
		t.Fatalf("picked %q want the EPUB url %q (EPUB is the highest-preference format)", got, epubURL)
	}
}

func TestPickBestFormatURL_Table(t *testing.T) {
	cases := []struct {
		name    string
		formats map[string]string
		want    string
	}{
		{"nil map", nil, ""},
		{"empty map", map[string]string{}, ""},
		{"epub wins over text+html", map[string]string{"text/plain": textURL, "text/html": htmlURL, "application/epub+zip": epubURL}, epubURL},
		{"text wins when no epub", map[string]string{"text/html": htmlURL, "text/plain": textURL}, textURL},
		{"html wins when only html+pdf", map[string]string{"application/pdf": pdfURL, "text/html": htmlURL}, htmlURL},
		{"pdf is last-resort preferred", map[string]string{"application/pdf": pdfURL}, pdfURL},
		{"empty-url preferred entry is skipped", map[string]string{"application/epub+zip": "", "text/plain": textURL}, textURL},
		{"unknown mime falls back to sorted-key non-empty", map[string]string{"application/x-mobipocket-ebook": "https://g.example/m.mobi"}, "https://g.example/m.mobi"},
		{"all empty urls yields empty", map[string]string{"application/epub+zip": "", "text/plain": ""}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := pickBestFormatURL(tc.formats)
			if got != tc.want {
				t.Fatalf("pickBestFormatURL=%q want %q", got, tc.want)
			}
		})
	}
}

// TestPickBestFormatURL_UnknownMimeDeterministic pins the deterministic
// fallback when no preferred MIME matches: keys are sorted, so the
// returned URL is stable across runs (a map-iteration-order bug here
// would be a flaky download-the-wrong-file defect invisible to a single
// test run).
func TestPickBestFormatURL_UnknownMimeDeterministic(t *testing.T) {
	formats := map[string]string{
		"zzz/last":  "https://g.example/z",
		"aaa/first": "https://g.example/a",
		"mmm/mid":   "https://g.example/m",
	}
	first := pickBestFormatURL(formats)
	if first != "https://g.example/a" {
		t.Fatalf("unknown-mime fallback=%q want the sorted-first key's url https://g.example/a", first)
	}
	// Determinism: 50 repeats MUST all agree.
	for i := 0; i < 50; i++ {
		if got := pickBestFormatURL(formats); got != first {
			t.Fatalf("non-deterministic fallback on iter %d: %q != %q", i, got, first)
		}
	}
}

func TestBestFormatName_Table(t *testing.T) {
	cases := []struct {
		name    string
		formats map[string]string
		want    string
	}{
		{"nil", nil, ""},
		{"epub", map[string]string{"application/epub+zip": epubURL}, "EPUB"},
		{"text", map[string]string{"text/plain": textURL}, "Text"},
		{"html", map[string]string{"text/html": htmlURL}, "HTML"},
		{"epub beats text+html", map[string]string{"text/html": htmlURL, "text/plain": textURL, "application/epub+zip": epubURL}, "EPUB"},
		{"text beats html", map[string]string{"text/html": htmlURL, "text/plain": textURL}, "Text"},
		{"unknown only yields empty", map[string]string{"application/pdf": pdfURL}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := bestFormatName(tc.formats)
			if got != tc.want {
				t.Fatalf("bestFormatName=%q want %q", got, tc.want)
			}
		})
	}
}

// TestBestFormatName_MatchesPickedURLFamily pins the cross-function
// invariant a user relies on: the label shown (bestFormatName) must
// describe the file actually handed over (pickBestFormatURL). If EPUB is
// the chosen URL, the label must say EPUB — a mismatch means the UI lies
// about what the user is downloading.
func TestBestFormatName_MatchesPickedURLFamily(t *testing.T) {
	formats := map[string]string{
		"application/pdf":      pdfURL,
		"application/epub+zip": epubURL,
		"text/plain":           textURL,
	}
	url := pickBestFormatURL(formats)
	name := bestFormatName(formats)
	if url == epubURL && name != "EPUB" {
		t.Fatalf("picked EPUB url but label=%q want EPUB (UI label must match downloaded file)", name)
	}
}
