package main

import "testing"

// TestResolveMDNSPort_FixedMode_ReturnsConfiguredUnchanged is the
// regression guard for "preserve EXISTING behavior exactly" outside dynamic
// mode: whatever the operator configured for LAVA_API_MDNS_PORT MUST come
// back byte-for-byte when the listen address is fixed.
func TestResolveMDNSPort_FixedMode_ReturnsConfiguredUnchanged(t *testing.T) {
	port, overrode := resolveMDNSPort(8443, "127.0.0.1:8443", false)
	if port != 8443 {
		t.Errorf("port = %d; want 8443 (unchanged)", port)
	}
	if overrode {
		t.Errorf("overrode = true; want false in fixed mode")
	}
}

// TestResolveMDNSPort_DynamicMode_UsesRealBoundPort is the load-bearing
// regression test for the mDNS-before-bind class of bug this session set
// out to fix: in dynamic mode, mDNS MUST be told the REAL bound port parsed
// out of resolvedListen — never the stale/pre-bind configured value, and
// never a literal "0".
func TestResolveMDNSPort_DynamicMode_UsesRealBoundPort(t *testing.T) {
	const configuredMDNSPort = 8443 // operator's default; irrelevant in dynamic mode
	const realBoundPort = 54321

	port, overrode := resolveMDNSPort(configuredMDNSPort, "0.0.0.0:54321", true)
	if port != realBoundPort {
		t.Fatalf("port = %d; want the REAL bound port %d, not the configured value %d",
			port, realBoundPort, configuredMDNSPort)
	}
	if !overrode {
		t.Errorf("overrode = false; want true (configured value %d differs from real port %d)",
			configuredMDNSPort, realBoundPort)
	}
}

// TestResolveMDNSPort_DynamicMode_NeverReturnsLiteralZero is the direct
// falsifiability target for the historical bug: before the fix, dynamic
// mode fed the UNRESOLVED, still-literal-"0" configured port straight into
// discovery.Announce. This test fails immediately if that regresses.
func TestResolveMDNSPort_DynamicMode_NeverReturnsLiteralZero(t *testing.T) {
	port, _ := resolveMDNSPort(8443, "127.0.0.1:19999", true)
	if port == 0 {
		t.Fatalf("port = 0 in dynamic mode; the resolved listen address's real port was not extracted")
	}
	if port != 19999 {
		t.Fatalf("port = %d; want 19999 (parsed from the resolved listen address)", port)
	}
}

// TestResolveMDNSPort_DynamicMode_NoOverrideWhenPortsAlreadyMatch covers
// the edge case where the operator's configured LAVA_API_MDNS_PORT
// happens to already equal the real bound port — no override should be
// reported (nothing to log).
func TestResolveMDNSPort_DynamicMode_NoOverrideWhenPortsAlreadyMatch(t *testing.T) {
	port, overrode := resolveMDNSPort(54321, "127.0.0.1:54321", true)
	if port != 54321 {
		t.Fatalf("port = %d; want 54321", port)
	}
	if overrode {
		t.Errorf("overrode = true; want false when configured and real ports already match")
	}
}

// TestResolveMDNSPort_DynamicMode_ZeroConfiguredNeverReportsOverride
// exercises the "operator left LAVA_API_MDNS_PORT at 0 / unset" edge: there
// is nothing to override (0 is not a real prior choice), so overrode must
// be false even though the returned port obviously differs from 0.
func TestResolveMDNSPort_DynamicMode_ZeroConfiguredNeverReportsOverride(t *testing.T) {
	port, overrode := resolveMDNSPort(0, "127.0.0.1:12345", true)
	if port != 12345 {
		t.Fatalf("port = %d; want 12345", port)
	}
	if overrode {
		t.Errorf("overrode = true with configuredMDNSPort=0; want false (nothing was actually overridden)")
	}
}

// TestResolveMDNSPort_MalformedResolvedListen_FallsBackToConfigured proves
// the defensive fallback branch: if resolvedListen is somehow not a valid
// host:port pair, resolveMDNSPort must not panic or return garbage — it
// falls back to the configured value.
func TestResolveMDNSPort_MalformedResolvedListen_FallsBackToConfigured(t *testing.T) {
	port, overrode := resolveMDNSPort(8443, "not-a-valid-address", true)
	if port != 8443 {
		t.Fatalf("port = %d; want fallback to configured 8443 on malformed input", port)
	}
	if overrode {
		t.Errorf("overrode = true on the fallback path; want false")
	}
}
