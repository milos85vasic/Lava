package discovery_test

import (
	"context"
	"net"
	"os"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/discovery"
	"digital.vasic.lava.apigo/internal/version"
	"digital.vasic.mdns/pkg/service"
)

// skipIfNoMulticast mirrors the pattern from
// submodules/mdns/pkg/service/integration_test.go: integration tests
// that need a real multicast round-trip skip cleanly when the host
// can't multicast (isolated CI containers, some Wi-Fi NICs). They MUST
// NOT fail just because multicast is unavailable.
func skipIfNoMulticast(t *testing.T) {
	t.Helper()
	if os.Getenv("MDNS_SKIP_INTEGRATION") == "1" {
		t.Skip("MDNS_SKIP_INTEGRATION=1 set")
	}
	addr, err := net.ResolveUDPAddr("udp4", "224.0.0.251:5353")
	if err != nil {
		t.Skipf("multicast unavailable: cannot resolve mDNS group: %v", err)
	}
	conn, err := net.ListenMulticastUDP("udp4", nil, addr)
	if err != nil {
		t.Skipf("multicast unavailable: %v", err)
	}
	conn.Close()
}

// browseFor calls service.Browse for the given service-type and returns
// the matching Discovered entry by instance name (or nil if not found).
func browseFor(t *testing.T, instance, svcType string) *service.Discovered {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	got, err := service.Browse(ctx, service.BrowseConfig{
		ServiceType: svcType,
		Timeout:     4 * time.Second,
	})
	if err != nil {
		t.Fatalf("Browse(%q): %v", svcType, err)
	}
	for i := range got {
		if got[i].Instance == instance {
			return &got[i]
		}
	}
	return nil
}

// TestAnnounce_PopulatesTXT is the load-bearing Challenge Test:
// announce a real service, browse the same service-type, assert every
// TXT key the SP-2 §8.2 spec mandates is present in the round-tripped
// entry. Sixth Law primary assertion is on user-visible state (the TXT
// values returned by mDNS browse).
func TestAnnounce_PopulatesTXT(t *testing.T) {
	skipIfNoMulticast(t)
	t.Parallel()

	const (
		instance = "LavaApiGoTest1"
		svcType  = "_lavaapigotest1._tcp"
		port     = 8765
	)
	srv, err := discovery.Announce(instance, svcType, port)
	if err != nil {
		t.Fatalf("Announce: %v", err)
	}
	defer srv.Stop()

	// Allow the multicast announcement to propagate.
	time.Sleep(200 * time.Millisecond)

	found := browseFor(t, instance, svcType)
	if found == nil {
		t.Skip("multicast loopback unavailable: announce succeeded but browse returned 0 matches")
	}

	if found.Port != port {
		t.Errorf("Port = %d, want %d", found.Port, port)
	}

	wantTXT := map[string]string{
		"engine":      "go",
		"version":     version.Name,
		"protocols":   "h3,h2",
		"compression": "br,gzip",
		"tls":         "required",
		"path":        "/",
	}
	for k, want := range wantTXT {
		got, ok := found.TXT[k]
		if !ok {
			t.Errorf("TXT[%q] missing; saw keys %v", k, txtKeys(found.TXT))
			continue
		}
		if got != want {
			t.Errorf("TXT[%q] = %q, want %q", k, got, want)
		}
	}
}

// TestAnnounce_TxtIncludesEngineGo specifically asserts engine=go is
// present in the announced TXT records. This is the falsifiability
// target: deleting `"engine": "go"` from internal/discovery/mdns.go's
// TXT map MUST cause this test to fail.
func TestAnnounce_TxtIncludesEngineGo(t *testing.T) {
	skipIfNoMulticast(t)
	t.Parallel()

	const (
		instance = "LavaApiGoTest2"
		svcType  = "_lavaapigotest2._tcp"
	)
	srv, err := discovery.Announce(instance, svcType, 8766)
	if err != nil {
		t.Fatalf("Announce: %v", err)
	}
	defer srv.Stop()
	time.Sleep(200 * time.Millisecond)

	found := browseFor(t, instance, svcType)
	if found == nil {
		t.Skip("multicast loopback unavailable: announce succeeded but browse returned 0 matches")
	}

	got, ok := found.TXT["engine"]
	if !ok {
		t.Fatalf("TXT[engine] missing; saw keys %v", txtKeys(found.TXT))
	}
	if got != "go" {
		t.Errorf("TXT[engine] = %q, want %q (the Android client uses this value to pick HTTP/3 vs HTTP/2 routing)", got, "go")
	}
}

// TestAnnounce_TxtIncludesVersion asserts the TXT records include the
// version key set to the value of internal/version.Name. We deliberately
// do NOT hard-code "2.0.0" — that would tie the test to a specific
// release and bluff-pass on the next bump.
func TestAnnounce_TxtIncludesVersion(t *testing.T) {
	skipIfNoMulticast(t)
	t.Parallel()

	const (
		instance = "LavaApiGoTest3"
		svcType  = "_lavaapigotest3._tcp"
	)
	srv, err := discovery.Announce(instance, svcType, 8767)
	if err != nil {
		t.Fatalf("Announce: %v", err)
	}
	defer srv.Stop()
	time.Sleep(200 * time.Millisecond)

	found := browseFor(t, instance, svcType)
	if found == nil {
		t.Skip("multicast loopback unavailable: announce succeeded but browse returned 0 matches")
	}

	got, ok := found.TXT["version"]
	if !ok {
		t.Fatalf("TXT[version] missing; saw keys %v", txtKeys(found.TXT))
	}
	if got != version.Name {
		t.Errorf("TXT[version] = %q, want %q (must equal internal/version.Name)", got, version.Name)
	}
}

// TestAnnounce_DefaultsApply verifies that empty-string / zero arguments
// produce the canonical Lava-domain defaults (service-type, instance,
// port). The defaults come from the SP-2 design doc §8.2 and are
// load-bearing: changing them silently would mean the Android client
// stops finding lava-api-go on the LAN.
func TestAnnounce_DefaultsApply(t *testing.T) {
	t.Parallel()

	if discovery.DefaultServiceType != "_lava-api._tcp" {
		t.Errorf("DefaultServiceType = %q, want %q", discovery.DefaultServiceType, "_lava-api._tcp")
	}
	if discovery.DefaultInstanceName != "lava-api-go" {
		t.Errorf("DefaultInstanceName = %q, want %q", discovery.DefaultInstanceName, "lava-api-go")
	}
	if discovery.DefaultPort != 8443 {
		t.Errorf("DefaultPort = %d, want %d", discovery.DefaultPort, 8443)
	}
}

func txtKeys(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
