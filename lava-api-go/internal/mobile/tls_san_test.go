package mobile

import (
	"crypto/x509"
	"encoding/pem"
	"net"
	"os"
	"path/filepath"
	"testing"
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
