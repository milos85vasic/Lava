package discovery_test

import (
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/discovery"
)

// These tests exercise Announce's deterministic code paths WITHOUT requiring a
// multicast round-trip (the existing mdns_test.go browse-based Challenge Tests
// cover the round-trip but skip cleanly when multicast is unavailable, leaving
// the default-fill and validation-error branches uncovered on such hosts).

// TestAnnounce_RejectsMalformedServiceType drives the validation-error path:
// the default instance + port are filled in, but a structurally invalid
// service-type (not _service._proto) MUST surface the upstream validation
// error rather than register a bogus advertisement. Assertion is on the
// user-visible error message an operator passing a bad type would see. Because
// validation fails before zeroconf registration, no LAN advertisement is made,
// so this is safe to run anywhere.
func TestAnnounce_RejectsMalformedServiceType(t *testing.T) {
	t.Parallel()
	srv, err := discovery.Announce("", "not-a-valid-service-type", 0)
	if err == nil {
		if srv != nil {
			srv.Stop()
		}
		t.Fatal("Announce with a malformed service-type returned nil error; expected validation refusal")
	}
	if srv != nil {
		t.Errorf("Announce error path returned non-nil *Service (%v); it must be nil", srv)
	}
	if !strings.Contains(err.Error(), "ServiceType") {
		t.Errorf("error %q does not name the offending ServiceType", err)
	}
}

// TestAnnounce_RejectsOutOfRangePort confirms the port is validated by the
// upstream after default-fill: an explicit, out-of-range negative port is
// rejected (it is non-zero, so the default-fill leaves it as-is and validation
// catches it). Real observable: the refusal error naming the port.
func TestAnnounce_RejectsOutOfRangePort(t *testing.T) {
	t.Parallel()
	srv, err := discovery.Announce("inst", "_lavap4negport._tcp", -1)
	if err == nil {
		if srv != nil {
			srv.Stop()
		}
		t.Fatal("Announce with port=-1 returned nil error; expected range refusal")
	}
	if !strings.Contains(err.Error(), "Port") {
		t.Errorf("error %q does not name the offending Port", err)
	}
}

// TestAnnounce_DefaultFillThenRegister drives the all-defaults happy path
// (instance == "", serviceType filled by the caller to a test-private type to
// avoid polluting the real _lava-api._tcp LAN namespace, port == 0). It proves
// the default-fill branches execute and a real *Service is returned, and that
// Stop is idempotent (callable twice without panic) — the lifecycle contract a
// shutdown handler relies on. We use a test-private service-type so this never
// advertises the production lava-api-go service on the operator's LAN.
func TestAnnounce_DefaultFillThenRegister(t *testing.T) {
	t.Parallel()
	srv, err := discovery.Announce("", "_lavap4dfltfill._tcp", 0)
	if err != nil {
		t.Fatalf("Announce with default instance+port: %v", err)
	}
	if srv == nil {
		t.Fatal("Announce returned nil *Service on the success path")
	}
	// Stop must be idempotent — a shutdown path may call it more than once.
	srv.Stop()
	srv.Stop()
}
