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
)

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

	// Reuse existing material if present and valid.
	if cert, err := tls.LoadX509KeyPair(certPath, keyPath); err == nil {
		return tlsConfigFrom(cert), nil
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

func tlsConfigFrom(cert tls.Certificate) *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		NextProtos:   []string{"h2", "http/1.1"},
	}
}

// generateSelfSigned mints a fresh ECDSA P-256 self-signed certificate covering
// loopback + the supplied LAN IPs + a DNS wildcard, returning the parsed
// tls.Certificate plus its PEM encodings for persistence.
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
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(certValidity),
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
