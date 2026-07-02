package kinozal

import "testing"

// extractIDFromHref is the pure parsing helper that turns a raw scraped
// result-row anchor href into the torrent ID a user taps to open a topic
// (search.go). A silent bug in it is invisible to the happy-path fixture test
// because the fixture only feeds well-formed hrefs; this test drives the real
// production helper with the edge inputs the fixture never exercises and
// asserts on the exact returned ID string — no fakes, no "didn't panic".
//
// (The former parseIntAfterColon helper + its test were removed when the parser
// was corrected to the real kinozal structure: seeders/leechers now come from
// bare-integer td.sl_s / td.sl_p cells via atoiTrim, not from "S:/L:" prefixed
// span text, so the colon-splitting helper is no longer part of any real code
// path.)

func TestExtractIDFromHref(t *testing.T) {
	cases := []struct {
		name string
		href string
		want string
	}{
		{"canonical details href", "/details.php?id=1481461", "1481461"},
		{"id among other params", "/details.php?foo=bar&id=42&baz=q", "42"},
		// FALSIFIABILITY: id genuinely absent -> empty string. If the
		// helper instead returned the whole query or panicked on a missing
		// key, the user would get a result row that opens the wrong topic.
		{"no id param present", "/details.php?foo=bar", ""},
		// FALSIFIABILITY: url.Parse error branch (75%->covered). A control
		// byte makes url.Parse fail; the helper MUST swallow it and return
		// "" rather than propagate a parse error into the row.
		{"malformed url returns empty", "ht\ttp://\x7f/x?id=9", ""},
		{"empty href", "", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := extractIDFromHref(tc.href); got != tc.want {
				t.Fatalf("extractIDFromHref(%q) = %q, want %q", tc.href, got, tc.want)
			}
		})
	}
}

// TestIsSizeText / TestAtoiTrim cover the two helpers that replaced
// parseIntAfterColon when the parser was corrected to the real kinozal
// structure. isSizeText decides which td.s cell in a row is the size (the one
// carrying a Cyrillic unit); atoiTrim turns a bare seeders/leechers cell into
// an int. Both are on the real user-visible parse path (search.go).
func TestIsSizeText(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{"gigabytes cyrillic", "1.71 ГБ", true},
		{"megabytes cyrillic", "700 МБ", true},
		{"kilobytes cyrillic", "512 КБ", true},
		{"terabytes cyrillic", "1.2 ТБ", true},
		// FALSIFIABILITY: the comment-count cell "0" and the date cell must NOT
		// be mistaken for a size. If isSizeText matched them, the row's Size
		// would render as "0" or a date string to the user.
		{"comment count is not a size", "0", false},
		{"upload date is not a size", "сегодня в 18:20", false},
		// FALSIFIABILITY: the OLD parser matched Latin units. A real kinozal
		// size never carries them, so matching "GB" would be the exact bug that
		// made every real search parse an empty size.
		{"latin GB is not a kinozal size", "1.71 GB", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isSizeText(tc.in); got != tc.want {
				t.Fatalf("isSizeText(%q) = %v, want %v", tc.in, got, tc.want)
			}
		})
	}
}

func TestAtoiTrim(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want int
	}{
		{"bare seeders cell", "42", 42},
		{"padded cell", "  7  ", 7},
		// FALSIFIABILITY: a malformed cell degrades to 0 rather than panicking
		// and killing the whole page parse.
		{"non-numeric yields zero", "many", 0},
		{"empty yields zero", "", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := atoiTrim(tc.in); got != tc.want {
				t.Fatalf("atoiTrim(%q) = %d, want %d", tc.in, got, tc.want)
			}
		})
	}
}
