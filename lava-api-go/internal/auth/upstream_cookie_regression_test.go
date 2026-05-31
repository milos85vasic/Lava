// Package auth — upstream_cookie_regression_test.go is a §6.L 68th
// regression-immunity test for the SP-3.5 (2026-04-29) double-prefix
// production bug recorded in passthrough.go's forensic anchor.
//
// Forensic anchor (verbatim from passthrough.go): UpstreamCookie once
// unconditionally prepended "bb_session=", producing a double-prefixed
// "bb_session=bb_session=0-47500467-…" Cookie header. Rutracker parsed
// the session as anonymous — /search returned 0 hits while the operator
// was signed in. The fix added the "already contains '=' → forward
// verbatim" branch.
//
// This file pins that fixed behavior as a falsifiable regression test:
// the PRIMARY assertion is on the exact Cookie value handed to the
// upstream scraper (a user-visible / on-the-wire byte string — the same
// surface the 2026-04-29 real-device failure exposed), NOT a mock call
// count. Per §6.AB the assertions discriminate the non-crashing failure
// mode: a double-prefixed cookie does not crash; it silently
// de-authenticates the session.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//   Mutation: in passthrough.go UpstreamCookie, delete the
//     `if strings.Contains(tok, "=") { return tok }` branch so every
//     token is wrapped → the regression returns.
//   Observed: TestUpstreamCookie_AlreadyNameValue_ForwardsVerbatim FAILS:
//     "cookie=%q want %q" with got="bb_session=bb_session=0-1-…".
//   Reverted: yes (production code restored; final commit unmutated).
package auth

import (
	"net/http"
	"testing"
)

// reqWithToken builds a request carrying the given Auth-Token header
// value (empty string = no header at all, modelling the anonymous case).
func reqWithToken(t *testing.T, token string) *http.Request {
	t.Helper()
	r, err := http.NewRequest(http.MethodGet, "/search", nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if token != "" {
		r.Header.Set(HeaderName, token)
	}
	return r
}

// TestUpstreamCookie_AlreadyNameValue_ForwardsVerbatim is the
// load-bearing regression test for the double-prefix bug. A real Lava
// client stores the raw upstream Set-Cookie line (a `name=value; …`
// shape). UpstreamCookie MUST forward it verbatim — wrapping it would
// reproduce the de-authentication bug.
func TestUpstreamCookie_AlreadyNameValue_ForwardsVerbatim(t *testing.T) {
	// A realistic upstream cookie line (synthetic, not a real session).
	raw := "bb_session=0-1-deadbeefcafef00d; expires=Wed, 01-Jan-2031 00:00:00 GMT; Max-Age=31536000; path=/; domain=.rutracker.org"
	got := UpstreamCookie(reqWithToken(t, raw))
	if got != raw {
		t.Fatalf("cookie=%q want %q (a name=value cookie line MUST forward verbatim — wrapping reproduces the SP-3.5 double-prefix de-auth bug)", got, raw)
	}
	// Explicit guard against the exact historical corruption shape.
	if got == "bb_session="+raw {
		t.Fatalf("DOUBLE-PREFIX REGRESSION: cookie was wrapped to %q; the session would be parsed anonymous", got)
	}
}

// TestUpstreamCookie_BareToken_GetsCanonicalPrefix pins the other
// branch: a bare token (no '=') is wrapped as the canonical session
// cookie so a future client sending just the value still authenticates.
func TestUpstreamCookie_BareToken_GetsCanonicalPrefix(t *testing.T) {
	got := UpstreamCookie(reqWithToken(t, "0-1-deadbeefcafef00d"))
	want := "bb_session=0-1-deadbeefcafef00d"
	if got != want {
		t.Fatalf("cookie=%q want %q (bare token MUST be wrapped exactly once)", got, want)
	}
}

// TestUpstreamCookie_NoHeader_Anonymous pins the anonymous path: no
// Auth-Token → empty cookie (NOT "bb_session=" with empty value, which
// would send a malformed empty cookie upstream).
func TestUpstreamCookie_NoHeader_Anonymous(t *testing.T) {
	got := UpstreamCookie(reqWithToken(t, ""))
	if got != "" {
		t.Fatalf("cookie=%q want empty string (no Auth-Token = anonymous; MUST NOT fabricate a cookie)", got)
	}
}

// TestRealmHash_Distinguishes_Anonymous_From_EmptyHash pins the
// security-relevant invariant from RealmHash's KDoc: no token yields ""
// (anonymous), NOT the SHA-256 of the empty string. Conflating the two
// would let an anonymous request collide in the cache with a (degenerate)
// authenticated one.
func TestRealmHash_Distinguishes_Anonymous_From_EmptyHash(t *testing.T) {
	anon := RealmHash(reqWithToken(t, ""))
	if anon != "" {
		t.Fatalf("RealmHash(no token)=%q want empty string (anonymous marker, NOT a hash)", anon)
	}
	// The SHA-256 of the empty string is e3b0c442... — the anonymous
	// marker MUST NOT equal it.
	const sha256OfEmptyString = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if anon == sha256OfEmptyString {
		t.Fatalf("anonymous marker equals SHA-256(\"\"); RealmHash conflates anonymous with a real hash")
	}
	// A present token MUST hash to a stable, non-empty, 64-hex-char value.
	h := RealmHash(reqWithToken(t, "tok"))
	if len(h) != 64 {
		t.Fatalf("RealmHash(tok) len=%d want 64 (SHA-256 hex)", len(h))
	}
	if h == RealmHash(reqWithToken(t, "tok2")) {
		t.Fatalf("RealmHash collides across distinct tokens; realm separation broken")
	}
}
