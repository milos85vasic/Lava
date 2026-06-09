package rutracker

import (
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// These tests pin the rutracker.org query-string contract for the three
// search-parameter enum mappers (searchSortTypeValue / searchSortOrderValue
// / searchPeriodValue). The mapped strings ARE the `o=`, `s=`, and `tm=`
// values sent to rutracker.org's /tracker.php — a wrong mapping silently
// sorts the user's results by the wrong column (or omits the parameter),
// which is invisible to any test that doesn't assert on the concrete value.
// The numeric values are the rutracker.org contract documented in
// search.go's package header (Date→"1", Title→"2", Downloaded→"4",
// Seeds→"10", Leeches→"11", Size→"7"; Ascending→"1"/Descending→"2";
// AllTime→"-1"/Today→"1"/LastThreeDays→"3"/LastWeek→"7"/
// LastTwoWeeks→"14"/LastMonth→"32").

func TestSearchSortTypeValue_AllKnownMembers(t *testing.T) {
	cases := []struct {
		in   gen.SearchSortTypeDto
		want string
	}{
		{gen.SearchSortTypeDtoDate, "1"},
		{gen.SearchSortTypeDtoTitle, "2"},
		{gen.SearchSortTypeDtoDownloaded, "4"},
		{gen.SearchSortTypeDtoSize, "7"},
		{gen.SearchSortTypeDtoSeeds, "10"},
		{gen.SearchSortTypeDtoLeeches, "11"},
	}
	for _, c := range cases {
		if got := searchSortTypeValue(c.in); got != c.want {
			t.Errorf("searchSortTypeValue(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestSearchSortTypeValue_UnknownReturnsEmpty(t *testing.T) {
	// An unrecognised enum value (e.g. a future member the upstream
	// doesn't accept) must yield "" so the caller omits the `o=` param
	// rather than sending a bogus sort key.
	if got := searchSortTypeValue(gen.SearchSortTypeDto("Bogus")); got != "" {
		t.Errorf("searchSortTypeValue(unknown) = %q, want empty", got)
	}
}

func TestSearchSortOrderValue_AllKnownMembers(t *testing.T) {
	if got := searchSortOrderValue(gen.Ascending); got != "1" {
		t.Errorf("searchSortOrderValue(Ascending) = %q, want %q", got, "1")
	}
	if got := searchSortOrderValue(gen.Descending); got != "2" {
		t.Errorf("searchSortOrderValue(Descending) = %q, want %q", got, "2")
	}
}

func TestSearchSortOrderValue_UnknownReturnsEmpty(t *testing.T) {
	if got := searchSortOrderValue(gen.SearchSortOrderDto("Sideways")); got != "" {
		t.Errorf("searchSortOrderValue(unknown) = %q, want empty", got)
	}
}

func TestSearchPeriodValue_AllKnownMembers(t *testing.T) {
	cases := []struct {
		in   gen.SearchPeriodDto
		want string
	}{
		{gen.AllTime, "-1"},
		{gen.Today, "1"},
		{gen.LastThreeDays, "3"},
		{gen.LastWeek, "7"},
		{gen.LastTwoWeeks, "14"},
		{gen.LastMonth, "32"},
	}
	for _, c := range cases {
		if got := searchPeriodValue(c.in); got != c.want {
			t.Errorf("searchPeriodValue(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestSearchPeriodValue_UnknownReturnsEmpty(t *testing.T) {
	if got := searchPeriodValue(gen.SearchPeriodDto("Yesterday")); got != "" {
		t.Errorf("searchPeriodValue(unknown) = %q, want empty", got)
	}
}
