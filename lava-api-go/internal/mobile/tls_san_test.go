package mobile

import (
	"crypto/x509"
	"encoding/pem"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// readPersistedCert parses the cert PEM the embed persisted next to dbPath.
func readPersistedCert(t *testing.T, dbPath string) *x509.Certificate {
	t.Helper()
	certPath := filepath.Join(filepath.Dir(dbPath), certFileName)
	pemBytes, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read persisted cert %s: %v", certPath, err)
	}
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		t.Fatalf("cert PEM at %s did not decode", certPath)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("parse persisted cert: %v", err)
	}
	return cert
}

// certCoversIP reports whether the cert's IPAddresses SAN contains ip.
func certCoversIP(cert *x509.Certificate, ip net.IP) bool {
	for _, sanIP := range cert.IPAddresses {
		if sanIP.Equal(ip) {
			return true
		}
	}
	return false
}

// TestLoadOrCreateTLS_RegeneratesWhenPersistedCertMissesCurrentIP is the LVA-061
// regression guard. The embed persists a self-signed cert next to the SQLite DB
// and REUSES it across restarts so LAN peers that pinned the leaf out-of-band do
// not see it change every boot. But the device's LAN IP can change between boots
// (DHCP lease renewal, switching networks). If the persisted cert's IP SANs do
// not include the CURRENT bind/advertise IP, every LAN peer addressing the embed
// by that new IP hits a TLS host-mismatch (x509: certificate is valid for <old
// IPs>, not <new IP>) — the embed is unreachable by IP until the cert is
// regenerated.
//
// Before the fix, loadOrCreateTLS blindly reused any persisted cert that parsed,
// never checking SAN coverage. This test:
//  1. Mints + persists a cert whose only LAN IP-SAN is a STALE address.
//  2. Calls loadOrCreateTLS with a DIFFERENT current IP (the new device IP).
//  3. Asserts the persisted-on-disk cert now COVERS the current IP — i.e. it was
//     regenerated, not reused.
//
// Uses real x509 cert generation throughout (generateSelfSigned) — no crypto is
// mocked.
//
// FALSIFIABILITY: removing the SAN-coverage check in loadOrCreateTLS (so it
// always reuses any cert that parses) makes this test FAIL — the on-disk cert
// after the call still misses the current IP. Proven in the LVA-061 Bluff-Audit.
func TestLoadOrCreateTLS_RegeneratesWhenPersistedCertMissesCurrentIP(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "lava.db")
	dir := filepath.Dir(dbPath)

	staleIP := net.ParseIP("192.0.2.10")     // TEST-NET-1, the old device IP
	currentIP := net.ParseIP("198.51.100.7") // TEST-NET-2, the new device IP

	// 1. Persist a REAL cert whose only LAN IP-SAN is the stale address.
	_, certPEM, keyPEM, err := generateSelfSigned([]net.IP{staleIP})
	if err != nil {
		t.Fatalf("seed cert: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, certFileName), certPEM, 0o644); err != nil {
		t.Fatalf("write seed cert: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, keyFileName), keyPEM, 0o600); err != nil {
		t.Fatalf("write seed key: %v", err)
	}

	// Sanity: the seeded cert genuinely lacks the current IP (else the test
	// would be vacuous).
	seeded := readPersistedCert(t, dbPath)
	if certCoversIP(seeded, currentIP) {
		t.Fatalf("seeded cert unexpectedly already covers current IP %v — test is vacuous", currentIP)
	}

	// 2. Load with the CURRENT (different) device IP.
	if _, err := loadOrCreateTLS(dbPath, []net.IP{currentIP}); err != nil {
		t.Fatalf("loadOrCreateTLS: %v", err)
	}

	// 3. The persisted cert must now COVER the current IP — proving it was
	//    regenerated (the user-visible outcome: a LAN peer addressing the embed
	//    by the new IP can now verify the leaf).
	after := readPersistedCert(t, dbPath)
	if !certCoversIP(after, currentIP) {
		t.Fatalf("persisted cert still does not cover current IP %v after load (IPs=%v) — "+
			"stale cert was reused; LAN peers on the new IP will hit a host-mismatch",
			currentIP, after.IPAddresses)
	}
}

// TestLoadOrCreateTLS_ReusesWhenPersistedCertCoversCurrentIP is the negative
// half: when the persisted cert ALREADY covers the current device IP, it MUST be
// reused unchanged — regenerating needlessly would break out-of-band leaf pins
// (the whole reason the cert is persisted). This guards against an
// over-eager fix that regenerates on every boot.
//
// FALSIFIABILITY: making loadOrCreateTLS regenerate unconditionally makes this
// test FAIL — the on-disk cert bytes change across the call even though the
// current IP was already covered.
func TestLoadOrCreateTLS_ReusesWhenPersistedCertCoversCurrentIP(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "lava.db")
	dir := filepath.Dir(dbPath)

	currentIP := net.ParseIP("198.51.100.7")

	// Persist a cert that already covers the current IP.
	_, certPEM, keyPEM, err := generateSelfSigned([]net.IP{currentIP})
	if err != nil {
		t.Fatalf("seed cert: %v", err)
	}
	certPath := filepath.Join(dir, certFileName)
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		t.Fatalf("write seed cert: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, keyFileName), keyPEM, 0o600); err != nil {
		t.Fatalf("write seed key: %v", err)
	}

	before, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read seed cert: %v", err)
	}

	if _, err := loadOrCreateTLS(dbPath, []net.IP{currentIP}); err != nil {
		t.Fatalf("loadOrCreateTLS: %v", err)
	}

	after, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read cert after load: %v", err)
	}
	if string(before) != string(after) {
		t.Fatalf("cert was regenerated even though it already covered the current IP %v — "+
			"out-of-band leaf pins would break needlessly", currentIP)
	}
}

// TestLoadOrCreateTLS_RegeneratesWhenPersistedCertExpired is the LVA-064
// regression guard. The embed REUSES a persisted cert across restarts (LVA-061
// added the IP-SAN coverage gate). But the self-signed cert has a finite
// NotAfter; once it passes (or comes within the safety margin), reusing it
// hands every LAN peer a leaf that fails verification with "x509: certificate
// has expired or is not yet valid" — the embed is unreachable over TLS until
// the cert is regenerated.
//
// Before the fix, loadOrCreateTLS reused any persisted cert whose IP SANs still
// covered the current device IP, never checking NotAfter — so an expired cert
// (whose SANs are unchanged) was silently reused forever. This test:
//  1. Mints + persists a REAL cert with tlsNow set far in the past, so its
//     NotAfter (mint-time + 10y validity) is itself in the past relative to the
//     real current time — i.e. an already-expired leaf whose IP SAN still
//     covers the current device IP.
//  2. Restores tlsNow and calls loadOrCreateTLS with the SAME current IP (so the
//     IP-SAN gate alone would say "reuse").
//  3. Asserts the persisted-on-disk cert is now UN-expired — i.e. it was
//     regenerated, not reused.
//
// Uses real x509 cert generation throughout (generateSelfSigned) — no crypto is
// mocked. The clock seam tlsNow is the only injection point.
//
// FALSIFIABILITY: removing the certExpired check from the reuse gate in
// loadOrCreateTLS (so it reuses any IP-covering cert regardless of NotAfter)
// makes this test FAIL — the on-disk cert after the call is still the expired
// leaf. Proven in the LVA-064 Bluff-Audit.
func TestLoadOrCreateTLS_RegeneratesWhenPersistedCertExpired(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "lava.db")
	dir := filepath.Dir(dbPath)

	currentIP := net.ParseIP("198.51.100.7") // TEST-NET-2, the device IP (unchanged)

	// 1. Mint a REAL cert "in the past" so its NotAfter (past + certValidity) is
	//    itself already in the past relative to real now. Validity is 10y; mint
	//    20y ago so NotAfter is ~10y in the past — unambiguously expired.
	restore := tlsNow
	tlsNow = func() time.Time { return time.Now().Add(-20 * 365 * 24 * time.Hour) }
	_, certPEM, keyPEM, err := generateSelfSigned([]net.IP{currentIP})
	tlsNow = restore
	if err != nil {
		t.Fatalf("seed cert: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, certFileName), certPEM, 0o644); err != nil {
		t.Fatalf("write seed cert: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, keyFileName), keyPEM, 0o600); err != nil {
		t.Fatalf("write seed key: %v", err)
	}

	// Sanity: the seeded cert is genuinely expired AND covers the current IP
	// (else the test would be vacuous — it must isolate the expiry branch from
	// the LVA-061 IP-SAN branch).
	seeded := readPersistedCert(t, dbPath)
	if !certCoversIP(seeded, currentIP) {
		t.Fatalf("seeded cert unexpectedly misses current IP %v — test would not isolate the expiry branch", currentIP)
	}
	if seeded.NotAfter.After(time.Now()) {
		t.Fatalf("seeded cert NotAfter %v is not in the past — test is vacuous (cert not actually expired)", seeded.NotAfter)
	}

	// 2. Load with the SAME current device IP (IP-SAN gate alone would reuse).
	if _, err := loadOrCreateTLS(dbPath, []net.IP{currentIP}); err != nil {
		t.Fatalf("loadOrCreateTLS: %v", err)
	}

	// 3. The persisted cert must now be UN-expired — proving it was regenerated
	//    (the user-visible outcome: a LAN peer can complete the TLS handshake
	//    instead of hitting "certificate has expired").
	after := readPersistedCert(t, dbPath)
	if !after.NotAfter.After(time.Now()) {
		t.Fatalf("persisted cert is still expired after load (NotAfter=%v) — "+
			"expired cert was reused; LAN peers will hit \"x509: certificate has expired\"",
			after.NotAfter)
	}
}
