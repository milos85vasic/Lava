package mobile

import (
	"crypto/x509"
	"net"
	"testing"
)

// TestCertCoversAllIPs_Contract directly exercises the certCoversAllIPs
// staleness gate (LVA-061), which the existing loadOrCreateTLS tests only
// reach transitively as a black box. certCoversAllIPs decides whether a
// persisted self-signed leaf may be REUSED across a restart: if it returns
// false, the embed regenerates the cert so a LAN peer addressing the device
// by a new IP can still verify the leaf. A bug here is high-impact and was
// previously only covered indirectly — the nil-skip branch
// (`if w == nil { continue }`) was entirely uncovered (90.9% of statements).
//
// The SUT is the real certCoversAllIPs; the leaf is a real x509.Certificate
// whose IPAddresses SAN set is populated directly — no crypto and no
// filesystem are mocked. Each case asserts the actual boolean the gate
// returns, which is the observable contract loadOrCreateTLS relies on.
//
// FALSIFIABILITY (recorded in the Bluff-Audit): inverting the not-found
// return in certCoversAllIPs (`return false` -> `return true`) makes the
// "missing IP" and "partial coverage" cases FAIL, because a stale cert that
// does NOT cover the wanted IP would then be reported as covered (reused),
// which is exactly the LVA-061 host-mismatch bug this gate prevents.
func TestCertCoversAllIPs_Contract(t *testing.T) {
	ip1 := net.ParseIP("198.51.100.7") // TEST-NET-2
	ip2 := net.ParseIP("203.0.113.42") // TEST-NET-3
	ip3 := net.ParseIP("192.0.2.10")   // TEST-NET-1 (not in any SAN set below)
	loop := net.ParseIP("127.0.0.1")

	leafWith := func(sans ...net.IP) *x509.Certificate {
		return &x509.Certificate{IPAddresses: sans}
	}

	cases := []struct {
		name string
		leaf *x509.Certificate
		want []net.IP
		got  bool
	}{
		{
			name: "empty want is trivially covered",
			leaf: leafWith(ip1),
			want: nil,
			got:  true,
		},
		{
			name: "single wanted IP present in SANs",
			leaf: leafWith(loop, ip1),
			want: []net.IP{ip1},
			got:  true,
		},
		{
			name: "single wanted IP absent from SANs",
			leaf: leafWith(loop, ip1),
			want: []net.IP{ip3},
			got:  false,
		},
		{
			name: "all wanted IPs present (multi)",
			leaf: leafWith(loop, ip1, ip2),
			want: []net.IP{ip1, ip2},
			got:  true,
		},
		{
			name: "partial coverage is not coverage",
			leaf: leafWith(loop, ip1),
			want: []net.IP{ip1, ip2},
			got:  false,
		},
		{
			name: "nil entries in want are skipped, real entry still checked (present)",
			leaf: leafWith(ip1),
			want: []net.IP{nil, ip1, nil},
			got:  true,
		},
		{
			name: "nil entries in want are skipped, real entry still checked (absent)",
			leaf: leafWith(ip1),
			want: []net.IP{nil, ip3},
			got:  false,
		},
		{
			name: "all-nil want is trivially covered (every entry skipped)",
			leaf: leafWith(ip1),
			want: []net.IP{nil, nil},
			got:  true,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := certCoversAllIPs(tc.leaf, tc.want); got != tc.got {
				t.Fatalf("certCoversAllIPs(SANs=%v, want=%v) = %v; expected %v — "+
					"a wrong verdict here means loadOrCreateTLS either reuses a stale "+
					"cert that misses a current LAN IP (peers hit host-mismatch) or "+
					"needlessly regenerates a cert that already covers it (breaks "+
					"out-of-band leaf pins)", tc.leaf.IPAddresses, tc.want, got, tc.got)
			}
		})
	}
}
