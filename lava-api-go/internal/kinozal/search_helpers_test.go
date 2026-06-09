package kinozal

import "testing"

// extractIDFromHref and parseIntAfterColon are the two pure parsing
// helpers that turn raw scraped HTML fragments into user-visible search
// data: extractIDFromHref produces the torrent ID a user taps to open a
// topic (search.go:36), and parseIntAfterColon produces the seeders /
// leechers counts shown on every result row (search.go:49,51).
//
// A silent bug in either is invisible to the happy-path fixture test
// (ParseSearchPage @ 96%) because the fixture only feeds well-formed
// hrefs and well-formed "Сидов: N" strings. These tests drive the real
// production helpers with the edge inputs the fixtures never exercise and
// assert on the exact returned value (the ID string / the int count) —
// no fakes, no "didn't panic".

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

func TestParseIntAfterColon(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want int
	}{
		{"russian seeders label", "Сидов: 42", 42},
		{"leading/trailing space around number", "Личеров:   7  ", 7},
		// FALSIFIABILITY: no colon -> 0 (the len(parts)!=2 guard, the
		// uncovered branch). If the guard were dropped, SplitN returns a
		// 1-element slice and parts[1] would panic with index-out-of-range,
		// crashing the whole search-result parse for a malformed row.
		{"no colon yields zero", "Сидов 42", 0},
		{"empty string yields zero", "", 0},
		// FALSIFIABILITY: non-numeric after the colon -> Atoi error is
		// swallowed -> 0, so one bad row degrades to zero seeders rather
		// than corrupting the whole page.
		{"non-numeric after colon yields zero", "Сидов: many", 0},
		{"colon but empty value yields zero", "Сидов:", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseIntAfterColon(tc.in); got != tc.want {
				t.Fatalf("parseIntAfterColon(%q) = %d, want %d", tc.in, got, tc.want)
			}
		})
	}
}
