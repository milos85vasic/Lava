package mobile

import (
	"net"
	"path/filepath"
	"testing"
	"time"
)

// TestRotatingCert_RemintsMidProcessWhenLeafNearsExpiry is the LVA-068
// regression guard. Before LVA-068 the embed only re-checked leaf expiry AT
// LOAD (LVA-064's loadOrCreateTLS gate). A multi-month-running embed could
// therefore cross its leaf's NotAfter WITHOUT a restart and start handing every
// LAN peer an expired leaf — the embed becomes unreachable over TLS with
// "x509: certificate has expired or is not yet valid" until someone restarts
// the process, which on a long-lived on-device embed may be never.
//
// The fix wires the leaf behind tls.Config.GetCertificate (the standard Go
// zero-downtime rotation idiom) via rotatingCert: every TLS handshake calls
// current(), which atomically re-mints + swaps a fresh leaf when the active one
// is within certExpiryMargin of NotAfter. Existing accepted connections are
// never dropped; only NEW handshakes after the swap observe the new leaf.
//
// This test:
//  1. Mints a REAL leaf at a fixed "now" and seeds a rotatingCert with it.
//  2. Advances tlsNow to PAST (NotAfter - margin) — i.e. the active leaf is now
//     within the expiry margin, exactly the multi-month-running condition.
//  3. Invokes current() (what GetCertificate calls per handshake).
//  4. Asserts the returned leaf is FRESHLY re-minted: a DIFFERENT serial and a
//     strictly later NotAfter, AND still covers the same device IP-SAN (so a LAN
//     peer addressing the embed by IP still verifies the rotated leaf).
//
// Uses real x509 generation throughout (generateSelfSigned via mintAndPersist);
// the only injection point is the tlsNow clock seam. No crypto is mocked.
//
// FALSIFIABILITY: disabling the re-mint (making rotatingCert.current return the
// stored leaf unconditionally) makes this test FAIL — current() returns the
// stale near-expiry leaf with the same serial and NotAfter. Proven in the
// LVA-068 Bluff-Audit.
func TestRotatingCert_RemintsMidProcessWhenLeafNearsExpiry(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, certFileName)
	keyPath := filepath.Join(dir, keyFileName)

	deviceIP := net.ParseIP("198.51.100.7") // TEST-NET-2, the device LAN IP

	// 1. Mint a REAL leaf at a fixed base time and seed the holder. Persisting
	//    here mirrors the production load path (mintAndPersist).
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	restore := tlsNow
	tlsNow = func() time.Time { return base }
	seed, err := mintAndPersist([]net.IP{deviceIP}, certPath, keyPath)
	if err != nil {
		tlsNow = restore
		t.Fatalf("seed mint: %v", err)
	}
	rc := newRotatingCert(seed, []net.IP{deviceIP}, certPath, keyPath)

	seedLeaf := parsedLeaf(seed)
	if seedLeaf == nil {
		tlsNow = restore
		t.Fatalf("seed leaf did not parse")
	}

	// 2. Advance the clock to within the expiry margin of the seed leaf's
	//    NotAfter (one hour PAST the (NotAfter - margin) boundary). The seed leaf
	//    is still technically valid (not yet past NotAfter) but inside the margin
	//    — exactly the LVA-068 multi-month-running, about-to-expire condition.
	tlsNow = func() time.Time { return seedLeaf.NotAfter.Add(-certExpiryMargin).Add(time.Hour) }
	defer func() { tlsNow = restore }()

	// Sanity: the seed leaf is genuinely within the margin now (else vacuous).
	if !certExpired(seedLeaf) {
		t.Fatalf("seed leaf is not within expiry margin at advanced clock — test is vacuous "+
			"(NotAfter=%v margin=%v now=%v)", seedLeaf.NotAfter, certExpiryMargin, tlsNow())
	}

	// 3. Invoke current() — what GetCertificate calls on every handshake.
	got, err := rc.current()
	if err != nil {
		t.Fatalf("current(): %v", err)
	}
	gotLeaf := parsedLeaf(*got)
	if gotLeaf == nil {
		t.Fatalf("rotated leaf did not parse")
	}

	// 4a. A FRESH leaf — different serial proves it was actually re-minted, not
	//     the stored one handed back.
	if gotLeaf.SerialNumber.Cmp(seedLeaf.SerialNumber) == 0 {
		t.Fatalf("current() returned the SAME leaf serial %v — leaf was NOT re-minted; "+
			"a multi-month-running embed will keep serving the expiring leaf",
			gotLeaf.SerialNumber)
	}
	// 4b. Strictly later NotAfter — the user-visible outcome: the rotated leaf is
	//     valid well into the future, so handshakes keep succeeding.
	if !gotLeaf.NotAfter.After(seedLeaf.NotAfter) {
		t.Fatalf("rotated leaf NotAfter %v is not after seed NotAfter %v — re-mint did not "+
			"extend validity", gotLeaf.NotAfter, seedLeaf.NotAfter)
	}
	// 4c. Still covers the device IP — a LAN peer addressing the embed by IP must
	//     still verify the rotated leaf (no host-mismatch regression).
	if !certCoversIP(gotLeaf, deviceIP) {
		t.Fatalf("rotated leaf no longer covers device IP %v (IPs=%v) — LAN peers would hit "+
			"a host-mismatch after rotation", deviceIP, gotLeaf.IPAddresses)
	}

	// 4d. The holder now serves the fresh leaf to subsequent handshakes too (the
	//     swap persisted in the atomic pointer, not just returned once).
	next, err := rc.current()
	if err != nil {
		t.Fatalf("second current(): %v", err)
	}
	if parsedLeaf(*next).SerialNumber.Cmp(gotLeaf.SerialNumber) != 0 {
		t.Fatalf("holder did not retain the rotated leaf across calls — re-mints every handshake")
	}
}

// TestRotatingCert_ReusesLeafWhenNotNearExpiry is the negative half: when the
// active leaf is comfortably far from NotAfter, current() MUST return it
// unchanged — re-minting on every handshake would churn the persisted material
// and defeat out-of-band leaf pinning. Guards against an over-eager rotation
// that mints regardless of remaining validity.
//
// FALSIFIABILITY: making current() always re-mint makes this test FAIL — the
// serial changes across the call even though the leaf had years of validity
// left.
func TestRotatingCert_ReusesLeafWhenNotNearExpiry(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, certFileName)
	keyPath := filepath.Join(dir, keyFileName)

	deviceIP := net.ParseIP("198.51.100.7")

	restore := tlsNow
	tlsNow = func() time.Time { return time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC) }
	defer func() { tlsNow = restore }()

	seed, err := mintAndPersist([]net.IP{deviceIP}, certPath, keyPath)
	if err != nil {
		t.Fatalf("seed mint: %v", err)
	}
	rc := newRotatingCert(seed, []net.IP{deviceIP}, certPath, keyPath)
	seedSerial := parsedLeaf(seed).SerialNumber

	// Clock unchanged — the leaf has the full certValidity (10y) ahead of it.
	got, err := rc.current()
	if err != nil {
		t.Fatalf("current(): %v", err)
	}
	if parsedLeaf(*got).SerialNumber.Cmp(seedSerial) != 0 {
		t.Fatalf("current() re-minted a leaf with years of validity remaining — " +
			"out-of-band leaf pins would churn needlessly")
	}
}
