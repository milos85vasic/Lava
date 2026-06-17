package nnmclub

import "testing"

// parseIntText is the helper that converts scraped seeders/leechers cell text
// (browse.go:43-44, search.go:52-53) into the integer a real user sees on a
// SearchItem. Real nnmclub rows carry surrounding whitespace and, when a tracker
// stat is unavailable, non-numeric placeholders ("N/A", "—", "-"). The contract
// the callers depend on is: whitespace is stripped, a clean number parses, and
// ANY unparseable text degrades to 0 (strconv.Atoi error is deliberately
// swallowed) — never a panic, never garbage propagated into the response. The
// happy-path fixture tests leave the empty-input branch and the Atoi-error
// branch (the 80%-coverage gap on utils.go:39) unexercised.
//
// FALSIFIABILITY (§6.J): replacing `v, _ := strconv.Atoi(s)` with
// `v, _ := strconv.Atoi("0")` (always-zero) makes the "leading whitespace
// number"/"trailing whitespace number"/"plain number" cases return 0 instead of
// their value, firing the want assertion below. Removing the `if s == ""`
// guard would make Atoi("") return (0,err) — still 0 here, so the
// always-zero mutation is the discriminating break and is the one recorded.
func TestParseIntText_DegradesAndTrims(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want int
	}{
		{"plain number", "42", 42},
		{"leading whitespace", "  7", 7},
		{"trailing whitespace", "1234\t", 1234},
		{"surrounding whitespace", "  99  ", 99},
		{"empty string", "", 0},
		{"whitespace only", "   ", 0},
		{"non-numeric placeholder N/A", "N/A", 0},
		{"em-dash placeholder", "—", 0},
		{"hyphen placeholder", "-", 0},
		{"embedded space (thousands sep)", "1 234", 0},
		{"trailing junk", "12abc", 0},
		{"zero", "0", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseIntText(tc.in); got != tc.want {
				t.Errorf("parseIntText(%q) = %d, want %d", tc.in, got, tc.want)
			}
		})
	}
}
