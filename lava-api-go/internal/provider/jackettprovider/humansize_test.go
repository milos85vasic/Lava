package jackettprovider

import "testing"

// TestHumanSize covers the pure-logic byte formatter humanSize, which renders
// the user-visible torrent "size" string in every Jackett-backed search /
// browse / topic result. Before this test the function had NO dedicated test:
// it was only incidentally exercised by the Search path's fixture sizes, so the
// non-positive branch (size <= 0 -> "" so omitempty drops the field), the raw
// "N B" branch (size < 1024), and — critically — the multi-iteration division
// loop that reaches TB/PB/EB suffixes were never asserted. A wrong suffix or a
// dropped digit here is a real user-facing defect (a 2 TB release shown as
// "2.00 GB"), invisible to existing tests.
//
// Primary assertion is on the actual returned string (the value a user sees),
// not on call counts. Table-driven, pure logic, no DB, no network.
//
// Falsifiability rehearsal (§6.J / Sixth Law clause 2): see commit body.
func TestHumanSize(t *testing.T) {
	const (
		kb = int64(1024)
		mb = kb * 1024
		gb = mb * 1024
		tb = gb * 1024
		pb = tb * 1024
		eb = pb * 1024
	)
	cases := []struct {
		name  string
		bytes int64
		want  string
	}{
		{"zero drops field", 0, ""},
		{"negative drops field", -1, ""},
		{"one byte", 1, "1 B"},
		{"just under a KB stays in bytes", 1023, "1023 B"},
		{"exactly one KB", kb, "1.00 KB"},
		{"one and a half MB", mb + mb/2, "1.50 MB"},
		{"two GB", 2 * gb, "2.00 GB"},
		// The next three force the division loop to iterate multiple times,
		// reaching the TB/PB/EB suffixes that incidental Search coverage misses.
		{"three TB", 3 * tb, "3.00 TB"},
		{"five PB", 5 * pb, "5.00 PB"},
		{"two EB", 2 * eb, "2.00 EB"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := humanSize(tc.bytes)
			if got != tc.want {
				t.Errorf("humanSize(%d) = %q, want %q", tc.bytes, got, tc.want)
			}
		})
	}
}
