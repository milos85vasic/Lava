package cache

import "testing"

// TestKeyEmptyValueSliceIgnored covers the uncovered branch in Key:
//
//	if len(vs) == 0 { continue }
//
// A query parameter present in the map but carrying an empty value slice
// MUST NOT contribute to the canonical key. The observable property is that
// such a key is byte-identical to a key built without that parameter at all.
// Without the `continue` guard, the empty slice would emit a "k=" fragment
// and diverge — so this asserts on the real returned key string.
func TestKeyEmptyValueSliceIgnored(t *testing.T) {
	withEmpty := Key("GET", "/search",
		nil,
		map[string][]string{"q": {"linux"}, "empty": {}},
		"abc",
	)
	withoutKey := Key("GET", "/search",
		nil,
		map[string][]string{"q": {"linux"}},
		"abc",
	)
	if withEmpty != withoutKey {
		t.Fatalf("empty-value-slice query param must not affect the key:\n  with empty slice: %s\n  without key:      %s", withEmpty, withoutKey)
	}
}

// TestKeyMultiValueOrderInsensitive covers the value-sorting stability path:
//
//	sortedVs := append([]string(nil), vs...)
//	sort.Strings(sortedVs)
//
// Repeated query values supplied in different orders MUST yield the same key
// (the join is over the sorted copy). This asserts the actual key strings are
// equal — a behavior a real cache lookup depends on for hit/miss correctness.
func TestKeyMultiValueOrderInsensitive(t *testing.T) {
	a := Key("GET", "/search",
		nil,
		map[string][]string{"genre": {"rock", "jazz", "blues"}},
		"",
	)
	b := Key("GET", "/search",
		nil,
		map[string][]string{"genre": {"blues", "rock", "jazz"}},
		"",
	)
	if a != b {
		t.Fatalf("multi-value query param order must not affect the key:\n  %s\n  %s", a, b)
	}
}

// TestKeyMultiValueDistinctSetsDiffer guards the inverse: genuinely different
// value sets MUST produce different keys (so the sort-for-stability above does
// not collapse distinct requests into one cache entry).
func TestKeyMultiValueDistinctSetsDiffer(t *testing.T) {
	a := Key("GET", "/search", nil, map[string][]string{"genre": {"rock", "jazz"}}, "")
	b := Key("GET", "/search", nil, map[string][]string{"genre": {"rock", "blues"}}, "")
	if a == b {
		t.Fatal("Key collided across distinct multi-value query sets")
	}
}
