package mobile

import (
	"context"
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
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"digital.vasic.lava.apigo/internal/observability"
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
			return newRotatingCert(cert, sanIPs, certPath, keyPath).tlsConfig(), nil
		}
		// else: stale SANs OR expired (within the safety margin) — regenerate
		// below so peers get a valid, IP-covering leaf.
	}

	cert, err := mintAndPersist(sanIPs, certPath, keyPath)
	if err != nil {
		return nil, err
	}
	return newRotatingCert(cert, sanIPs, certPath, keyPath).tlsConfig(), nil
}

// mintAndPersist generates a fresh self-signed cert covering sanIPs and writes
// the cert+key PEM to disk (0600 on the key — it is private material). Returns
// the parsed tls.Certificate. Shared by the initial load path and the
// mid-process re-mint (rotatingCert.current), so persistence behaviour is
// identical whether the cert is minted at Start or rotated in mid-flight.
func mintAndPersist(sanIPs []net.IP, certPath, keyPath string) (tls.Certificate, error) {
	cert, certPEM, keyPEM, err := generateSelfSigned(sanIPs)
	if err != nil {
		// no-telemetry: error is returned directly to the caller (Start or rotatingCert);
		// the caller (rotatingCert.getCurrent) records via recordRotation on failure.
		return tls.Certificate{}, err
	}
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return tls.Certificate{}, fmt.Errorf("write cert: %w", err)
	}
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return tls.Certificate{}, fmt.Errorf("write key: %w", err)
	}
	return cert, nil
}

// rotatingCert holds the active leaf behind an atomic pointer and re-mints it
// in-flight when it comes within certExpiryMargin of NotAfter. This is the
// LVA-068 mid-process rotation: loadOrCreateTLS only checked expiry AT LOAD
// (LVA-064), so a multi-month-running embed could cross its leaf's NotAfter
// without a restart and start handing every LAN peer an expired leaf. The
// holder is wired as tls.Config.GetCertificate, the standard Go idiom for
// zero-downtime cert rotation: every TLS handshake calls current(), which
// returns the live leaf or atomically swaps in a freshly-minted one — existing
// accepted connections are never dropped, only NEW handshakes after the swap
// observe the new leaf.
type rotatingCert struct {
	// cert is *tls.Certificate, swapped atomically so concurrent GetCertificate
	// callbacks (one per in-flight handshake) always read a consistent leaf.
	cert atomic.Pointer[tls.Certificate]
	// sanIPs / certPath / keyPath are the inputs to a re-mint: the IP-SAN set the
	// leaf must keep covering and the on-disk paths to persist the new material
	// (so a subsequent restart reuses the rotated leaf, not the expired one).
	sanIPs   []net.IP
	certPath string
	keyPath  string
	// mintMu serialises re-mints so a burst of near-expiry handshakes mints once,
	// not once-per-handshake. A handshake that loses the race re-reads the (now
	// fresh) pointer under the same lock.
	mintMu sync.Mutex
}

func newRotatingCert(cert tls.Certificate, sanIPs []net.IP, certPath, keyPath string) *rotatingCert {
	rc := &rotatingCert{sanIPs: sanIPs, certPath: certPath, keyPath: keyPath}
	c := cert
	rc.cert.Store(&c)
	return rc
}

// current returns the leaf to present for an incoming handshake. The fast path
// is a single atomic load; only when the active leaf is within certExpiryMargin
// of expiry does it take mintMu, re-mint+persist a fresh leaf covering the same
// IP-SANs, and atomically swap it in. If the re-mint fails (e.g. disk full) the
// existing (near-expiry but still-valid-until-NotAfter) leaf is returned so the
// server keeps serving rather than failing the handshake outright — the next
// handshake retries the re-mint.
func (rc *rotatingCert) current() (*tls.Certificate, error) {
	cur := rc.cert.Load()
	leaf := parsedLeaf(*cur)
	if leaf != nil && !certExpired(leaf) {
		return cur, nil
	}

	rc.mintMu.Lock()
	defer rc.mintMu.Unlock()

	// Re-check under the lock: another handshake may have already rotated.
	cur = rc.cert.Load()
	if leaf := parsedLeaf(*cur); leaf != nil && !certExpired(leaf) {
		return cur, nil
	}

	fresh, err := mintAndPersist(rc.sanIPs, rc.certPath, rc.keyPath)
	if err != nil {
		// Keep serving the current leaf (still valid until NotAfter) rather than
		// dropping the connection; the next handshake retries. §6.AC: surface the
		// re-mint failure so an operator sees a long-lived embed degrade.
		// no-telemetry: recordRotation (= observability.RecordWarning) IS the §6.AC telemetry call below.
		recordRotation(context.Background(), "embed TLS leaf re-mint failed; serving near-expiry leaf", observability.NonFatalAttributes{
			observability.AttrFeature:      "tls",
			observability.AttrOperation:    "cert-rotation",
			observability.AttrErrorClass:   "remint-failed",
			observability.AttrErrorMessage: err.Error(),
		})
		return cur, nil
	}
	rc.cert.Store(&fresh)
	// LVA-072 / §6.AC: the mid-process leaf swap (LVA-068) was SILENT — an operator
	// had no signal that a long-lived embed rotated its cert. Surface it with public
	// cert metadata only (old/new NotAfter + IP-SANs are NOT secrets per §6.H; the
	// private key material is never in attrs).
	recordRotation(context.Background(), "embed TLS leaf rotated mid-process", observability.NonFatalAttributes{
		observability.AttrFeature:   "tls",
		observability.AttrOperation: "cert-rotation",
		"old_not_after":             notAfterString(cur),
		"new_not_after":             notAfterString(&fresh),
		"ip_sans":                   ipSANsString(rc.sanIPs),
	})
	return &fresh, nil
}

// recordRotation is the §6.AC telemetry seam for the mid-process leaf swap. It
// defaults to observability.RecordWarning so a rotation surfaces to the operator
// non-fatal feed. Tests override it to capture the emitted event + assert context.
var recordRotation = observability.RecordWarning

// notAfterString formats a leaf's NotAfter as RFC3339, or "" if unparsable. Public
// certificate metadata — never key material (§6.H).
func notAfterString(c *tls.Certificate) string {
	if c == nil {
		return ""
	}
	leaf := parsedLeaf(*c)
	if leaf == nil {
		return ""
	}
	return leaf.NotAfter.UTC().Format(time.RFC3339)
}

// ipSANsString joins the SAN IPs for telemetry context. IP addresses are public
// certificate metadata, not secrets (§6.H).
func ipSANsString(ips []net.IP) string {
	parts := make([]string, 0, len(ips))
	for _, ip := range ips {
		parts = append(parts, ip.String())
	}
	return strings.Join(parts, ",")
}

// tlsConfig wires rc.current as GetCertificate so every handshake re-checks
// expiry and rotates if needed. GetCertificate takes precedence over a static
// Certificates slice, so the leaf is always freshly resolved per handshake.
func (rc *rotatingCert) tlsConfig() *tls.Config {
	return &tls.Config{
		GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			return rc.current()
		},
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"h2", "http/1.1"},
	}
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
		// no-telemetry: pure parse helper (parsedLeaf); nil return propagates to caller.
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
		// no-telemetry: best-effort SAN population; loopback is always covered by
		// generateSelfSigned regardless of this helper's result.
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
