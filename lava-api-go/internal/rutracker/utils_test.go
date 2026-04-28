package rutracker

import (
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

// docFromHTML is a small helper for unit-testing selector helpers. The
// utils take *goquery.Selection so we wrap the snippet in a goquery
// Document and pass `.Selection` to the helper under test.
func docFromHTML(t *testing.T, html string) *goquery.Document {
	t.Helper()
	d, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse html: %v", err)
	}
	return d
}

func TestGetTitle_StripsTags(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"[Genre] Title [1080p]", "Title"},
		{"Just Plain Title", "Just Plain Title"},
		{"[Tag] Single", "Single"},
		{"NoTags", "NoTags"},
	}
	for _, tc := range cases {
		got := getTitle(tc.in)
		if got != tc.want {
			t.Errorf("getTitle(%q): got %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestGetTags_ConcatenatesBrackets(t *testing.T) {
	got := getTags("[Genre] Title [1080p]")
	want := "[Genre] [1080p] "
	if got != want {
		t.Errorf("getTags: got %q, want %q", got, want)
	}
	if got2 := getTags("NoTags"); got2 != "" {
		t.Errorf("getTags on plain text: got %q, want empty", got2)
	}
}

func TestNodeText_CollapsesWhitespace(t *testing.T) {
	html := "<p>hello&nbsp;world  with   spaces</p>"
	d := docFromHTML(t, html)
	got := nodeText(d.Find("p"))
	want := "hello world with spaces"
	if got != want {
		t.Errorf("nodeText: got %q, want %q", got, want)
	}
}

func TestQueryParam_ReturnsValue(t *testing.T) {
	html := `<a href="profile.php?u=42&x=y">name</a>`
	d := docFromHTML(t, html)
	a := d.Find("a")
	if got := queryParam(a, "u"); got != "42" {
		t.Errorf("queryParam(u): got %q, want \"42\"", got)
	}
	if got := queryParam(a, "missing"); got != "" {
		t.Errorf("queryParam(missing): got %q, want empty", got)
	}
	if v, ok := queryParamOrNull(a, "missing"); ok || v != "" {
		t.Errorf("queryParamOrNull(missing): got (%q,%v), want (\"\",false)", v, ok)
	}
	if v, ok := queryParamOrNull(a, "u"); !ok || v != "42" {
		t.Errorf("queryParamOrNull(u): got (%q,%v), want (\"42\",true)", v, ok)
	}
}

// TestNodeText_MultiMatchJoinsWithSpace pins the multi-element join
// semantics that were missing before the Phase-6.2 code-quality fix.
// goquery's Selection.Text() concatenates the text of every matched
// element WITHOUT a separator; Jsoup's Elements.text() (which the Kotlin
// parser uses) joins with " ". A row carrying two `.seedmed` siblings
// ("12" and "34") MUST yield "12 34", not "1234". Without this guarantee
// nodeIntOrNil silently parses 1234 from what the Kotlin code rightly
// rejects as non-numeric.
func TestNodeText_MultiMatchJoinsWithSpace(t *testing.T) {
	html := `<table><tbody><tr><td><span class="seedmed">12</span><span class="seedmed">34</span></td></tr></tbody></table>`
	d := docFromHTML(t, html)
	sel := d.Find("tr").Find(".seedmed")
	if sel.Length() != 2 {
		t.Fatalf("setup: expected 2 .seedmed siblings, got %d", sel.Length())
	}
	got := nodeText(sel)
	want := "12 34"
	if got != want {
		t.Errorf("nodeText multi-match: got %q, want %q (Jsoup Elements.text() joins with single space)", got, want)
	}
}

// TestNodeIntOrNil_MultiMatchReturnsAbsent verifies the integer parser
// inherits the multi-match join — "12 34" is non-numeric, so the helper
// MUST return (0, false), matching Kotlin's `"12 34".toIntOrNull() == null`.
// A pre-fix nodeText returns "1234" and nodeIntOrNil silently produces
// (1234, true). That is exactly the silent corruption guarded against.
func TestNodeIntOrNil_MultiMatchReturnsAbsent(t *testing.T) {
	html := `<table><tbody><tr><td><span class="seedmed">12</span><span class="seedmed">34</span></td></tr></tbody></table>`
	d := docFromHTML(t, html)
	sel := d.Find("tr").Find(".seedmed")
	v, ok := nodeIntOrNil(sel)
	if ok {
		t.Errorf("nodeIntOrNil multi-match: got (%d, true), want (0, false) — Kotlin's toIntOrNull on \"12 34\" is null", v)
	}
	if v != 0 {
		t.Errorf("nodeIntOrNil multi-match: value should be 0 on miss, got %d", v)
	}
}

// TestParseTorrentStatus_PriorityOrder verifies that when a row carries
// multiple status classes simultaneously, the FIRST entry in the priority
// table wins (matching Kotlin's `when` block). Duplicate must outrank
// Approved because it is listed first.
//
// Note: HTML5 parsing requires `<tr>` to be wrapped in a `<table>` —
// otherwise the parser strips the row entirely. We wrap accordingly.
func TestParseTorrentStatus_PriorityOrder(t *testing.T) {
	html := `<table><tbody><tr><td><span class="tor-dup">dup</span><span class="tor-approved">ok</span></td></tr></tbody></table>`
	d := docFromHTML(t, html)
	row := d.Find("tr")
	got := parseTorrentStatus(row)
	if got == nil {
		t.Fatal("parseTorrentStatus: got nil, want \"Duplicate\"")
	}
	if *got != "Duplicate" {
		t.Errorf("parseTorrentStatus: got %q, want \"Duplicate\" (first match wins)", *got)
	}

	// Single match returns that status.
	html2 := `<table><tbody><tr><td><span class="tor-approved">ok</span></td></tr></tbody></table>`
	d2 := docFromHTML(t, html2)
	row2 := d2.Find("tr")
	got2 := parseTorrentStatus(row2)
	if got2 == nil || *got2 != "Approved" {
		gotStr := "<nil>"
		if got2 != nil {
			gotStr = *got2
		}
		t.Errorf("parseTorrentStatus(approved-only): got %q, want \"Approved\"", gotStr)
	}

	// No status classes returns nil.
	html3 := `<table><tbody><tr><td>plain row</td></tr></tbody></table>`
	d3 := docFromHTML(t, html3)
	if got3 := parseTorrentStatus(d3.Find("tr")); got3 != nil {
		t.Errorf("parseTorrentStatus(no-classes): got %q, want nil", *got3)
	}
}
