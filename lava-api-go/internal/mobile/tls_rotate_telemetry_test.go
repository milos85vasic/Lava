package mobile

import (
	"context"
	"net"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/observability"
)

// TestRotatingCert_EmitsTelemetryOnRotation_LVA072 is the §6.AC guard: the
// mid-process leaf swap (LVA-068) MUST surface to telemetry so an operator can
// see a long-lived embed rotate its cert — LVA-068 wired the swap but left it
// SILENT, which §6.AC forbids.
//
// Falsifiability (§6.T.1): remove the `recordRotation(...)` CALL at the swap site
// in rotatingCert.current() and this test FAILS ("expected exactly 1 rotation
// telemetry event, got 0"). The earlier wave-10 attempt was discarded because its
// rehearsal mutated the default seam var (which the test overrides) — this test's
// falsifiable mutation is the CALL SITE, not the var.
func TestRotatingCert_EmitsTelemetryOnRotation_LVA072(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, certFileName)
	keyPath := filepath.Join(dir, keyFileName)
	deviceIP := net.ParseIP("198.51.100.7") // TEST-NET-2

	type ev struct {
		msg   string
		attrs observability.NonFatalAttributes
	}
	var events []ev
	origRec := recordRotation
	recordRotation = func(_ context.Context, msg string, attrs observability.NonFatalAttributes) {
		events = append(events, ev{msg: msg, attrs: attrs})
	}
	defer func() { recordRotation = origRec }()

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
	// Advance into the expiry margin so current() rotates.
	tlsNow = func() time.Time { return seedLeaf.NotAfter.Add(-certExpiryMargin).Add(time.Hour) }
	defer func() { tlsNow = restore }()

	if _, err := rc.current(); err != nil {
		t.Fatalf("current(): %v", err)
	}

	if len(events) != 1 {
		t.Fatalf("expected exactly 1 rotation telemetry event, got %d — the mid-process leaf swap MUST surface to telemetry (§6.AC / LVA-072)", len(events))
	}
	e := events[0]
	if e.attrs[observability.AttrFeature] != "tls" {
		t.Errorf("feature=%q want tls", e.attrs[observability.AttrFeature])
	}
	if e.attrs[observability.AttrOperation] != "cert-rotation" {
		t.Errorf("operation=%q want cert-rotation", e.attrs[observability.AttrOperation])
	}
	if e.attrs["old_not_after"] == "" || e.attrs["new_not_after"] == "" {
		t.Errorf("old/new NotAfter must be populated: old=%q new=%q", e.attrs["old_not_after"], e.attrs["new_not_after"])
	}
	if e.attrs["new_not_after"] == e.attrs["old_not_after"] {
		t.Errorf("new NotAfter must differ from old (proof of a real re-mint): both=%q", e.attrs["new_not_after"])
	}
	if e.attrs["ip_sans"] != deviceIP.String() {
		t.Errorf("ip_sans=%q want %q", e.attrs["ip_sans"], deviceIP.String())
	}
	// §6.H: no private-key material in any telemetry attribute value.
	for k, v := range e.attrs {
		if strings.Contains(v, "PRIVATE KEY") || strings.Contains(v, "BEGIN EC") {
			t.Errorf("§6.H: attr %q leaks key material: %q", k, v)
		}
	}
}

// TestRotatingCert_NoTelemetryWhenNoRotation guards the negative: a current()
// call on a fresh (not-near-expiry) leaf must NOT emit a rotation event — telemetry
// noise on every handshake would itself be a defect.
func TestRotatingCert_NoTelemetryWhenNoRotation(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, certFileName)
	keyPath := filepath.Join(dir, keyFileName)
	deviceIP := net.ParseIP("198.51.100.7")

	var count int
	origRec := recordRotation
	recordRotation = func(_ context.Context, _ string, _ observability.NonFatalAttributes) { count++ }
	defer func() { recordRotation = origRec }()

	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	restore := tlsNow
	tlsNow = func() time.Time { return base }
	defer func() { tlsNow = restore }()
	seed, err := mintAndPersist([]net.IP{deviceIP}, certPath, keyPath)
	if err != nil {
		t.Fatalf("seed mint: %v", err)
	}
	rc := newRotatingCert(seed, []net.IP{deviceIP}, certPath, keyPath)

	if _, err := rc.current(); err != nil {
		t.Fatalf("current(): %v", err)
	}
	if count != 0 {
		t.Fatalf("a fresh leaf must NOT emit a rotation event, got %d", count)
	}
}
