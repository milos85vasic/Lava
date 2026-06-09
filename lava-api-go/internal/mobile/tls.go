package mobile

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// certFileName / keyFileName are the on-disk names of the persisted self-signed
// TLS material, written alongside the SQLite DB so they survive Stop→Start.
const (
	certFileName = "lava-embed-cert.pem"
	keyFileName  = "lava-embed-key.pem"
	// certValidity bounds the self-signed cert's lifetime. Ten years is long
	// enough that an on-device embed never needs rotation handling in this
	// sub-project (client-trust + rotation is a later sub-project per the
	// package comment).
	certValidity = 10 * 365 * 24 * time.Hour
	// certExpiryMargin is the safety window before NotAfter within which a
	// persisted cert is treated as already-stale and regenerated. Regenerating
	// slightly ahead of true expiry avoids handing a peer a leaf that expires
	// mid-session right after the TLS handshake (LVA-064).
	certExpiryMargin = 24 * time.Hour
)

// tlsNow is the clock seam for cert minting (NotBefore/NotAfter) and the
// persisted-cert expiry gate. It defaults to time.Now in production; tests
// override it to mint a back-dated (expired) cert and confirm the load path
// regenerates. Keeping a single seam for both mint + check means a test can
// mint "in the past" and the real-now check then correctly sees expiry.
var tlsNow = time.Now

// loadOrCreateTLS returns a *tls.Config holding the self-signed certificate for
// the embed. The cert+key live in the SQLite DB's directory; if both files
// already exist and parse, they are REUSED (so the same cert is presented
// across restarts — important so LAN peers that pinned the leaf out-of-band do
// not see it change every boot). Otherwise a fresh ECDSA P-256 self-signed cert
// is generated, written to disk, and used.
//
// sanIPs are added to the cert's IPAddresses so peers addressing the embed by
// LAN IP do not get a host-mismatch error. The cert's DNS SANs are limited to
// "localhost" (security-review finding 2: the previous bare "*" wildcard DNS
// SAN matched ANY hostname, defeating name verification entirely). LAN peers
// address the embed by IP and rely on the IPAddresses SAN; full client-trust
// handling is a later sub-project.
func loadOrCreateTLS(sqlitePath string, sanIPs []net.IP) (*tls.Config, error) {
	dir := filepath.Dir(sqlitePath)
	certPath := filepath.Join(dir, certFileName)
	keyPath := filepath.Join(dir, keyFileName)

	// Reuse existing material if present, valid, AND still covering the current
	// device IPs. The device's LAN IP can change between boots (DHCP renewal,
	// switching networks); a persisted cert whose IP SANs predate that change no
	// longer covers the new bind/advertise IP, so every LAN peer addressing the
	// embed by that IP hits a TLS host-mismatch (LVA-061). Only reuse when the
	// leaf SANs already cover every current sanIP — otherwise fall through and
	// regenerate with the up-to-date IP set. When the IP set is unchanged the
	// cert is reused verbatim, preserving any out-of-band leaf pin (the reason
	// the cert is persisted at all).
	if cert, err := tls.LoadX509KeyPair(certPath, keyPath); err == nil {
		if leaf := parsedLeaf(cert); leaf != nil &&
			certCoversAllIPs(leaf, sanIPs) &&
			!certExpired(leaf) {
			return tlsConfigFrom(cert), nil
		}
		// else: stale SANs OR expired (within the safety margin) — regenerate
		// below so peers get a valid, IP-covering leaf.
	}

	cert, certPEM, keyPEM, err := generateSelfSigned(sanIPs)
	if err != nil {
		return nil, err
	}

	// Persist (0600 on the key — it is private material).
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return nil, fmt.Errorf("write cert: %w", err)
	}
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return nil, fmt.Errorf("write key: %w", err)
	}
	return tlsConfigFrom(cert), nil
}

// parsedLeaf returns the parsed leaf x509 certificate for a loaded keypair.
// tls.LoadX509KeyPair may leave cert.Leaf nil (it is only populated on newer Go
// when the leaf is parsed during load), so fall back to parsing cert.Certificate[0]
// ourselves. Returns nil if the leaf cannot be obtained, in which case the
// caller treats the persisted material as not-reusable and regenerates.
func parsedLeaf(cert tls.Certificate) *x509.Certificate {
	if cert.Leaf != nil {
		return cert.Leaf
	}
	if len(cert.Certificate) == 0 {
		return nil
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil
	}
	return leaf
}

// certCoversAllIPs reports whether the leaf's IPAddresses SAN set contains every
// IP in want. An empty want set is trivially covered (no LAN IPs to advertise —
// loopback coverage from generateSelfSigned is unaffected). This is the LVA-061
// staleness check: if the current device IPs are not all covered, the persisted
// cert is stale and MUST be regenerated.
func certCoversAllIPs(leaf *x509.Certificate, want []net.IP) bool {
	for _, w := range want {
		if w == nil {
			continue
		}
		found := false
		for _, sanIP := range leaf.IPAddresses {
			if sanIP.Equal(w) {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

// certExpired reports whether the leaf is past its NotAfter, or within
// certExpiryMargin of it (so a near-expiry leaf is regenerated before it can
// expire mid-session). This is the LVA-064 staleness check: before LVA-064 the
// reuse gate only checked IP-SAN coverage (LVA-061) and silently reused a
// persisted cert whose NotAfter had already passed — every peer then hit
// "x509: certificate has expired or is not yet valid". The clock is tlsNow so
// tests can exercise the expiry branch deterministically without sleeping.
func certExpired(leaf *x509.Certificate) bool {
	return !tlsNow().Add(certExpiryMargin).Before(leaf.NotAfter)
}

func tlsConfigFrom(cert tls.Certificate) *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		NextProtos:   []string{"h2", "http/1.1"},
	}
}

// generateSelfSigned mints a fresh ECDSA P-256 self-signed certificate covering
// loopback + the supplied LAN IPs (IP SANs) plus the "localhost" DNS name —
// no wildcard DNS SAN (removed per the 2026-06-02 security review). Returns the
// parsed tls.Certificate plus its PEM encodings for persistence.
func generateSelfSigned(sanIPs []net.IP) (tls.Certificate, []byte, []byte, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("generate key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("serial: %w", err)
	}

	ips := append([]net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback}, sanIPs...)

	tmpl := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "lava-api-go embed"},
		NotBefore:             tlsNow().Add(-time.Hour),
		NotAfter:              tlsNow().Add(certValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IPAddresses:           ips,
		// DNS SANs are limited to "localhost" (security-review finding 2): a
		// bare "*" wildcard SAN matched ANY hostname and defeated name-based
		// verification. Peers address the embed by LAN IP (covered by
		// IPAddresses above) or by "localhost" on-device. Out-of-band leaf
		// trust remains the model for now.
		DNSNames: []string{"localhost"},
	}

	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("create certificate: %w", err)
	}

	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("marshal key: %w", err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("parse generated keypair: %w", err)
	}
	return cert, certPEM, keyPEM, nil
}

// localIPs returns the host's non-loopback, non-link-local unicast IP
// addresses, used to populate the cert SANs so LAN peers addressing the embed
// by IP do not get a host-mismatch error. Errors are swallowed (best-effort):
// loopback is always covered by generateSelfSigned regardless.
func localIPs() []net.IP {
	var out []net.IP
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return out
	}
	for _, a := range addrs {
		var ip net.IP
		switch v := a.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		}
		if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
			continue
		}
		out = append(out, ip)
	}
	return out
}
