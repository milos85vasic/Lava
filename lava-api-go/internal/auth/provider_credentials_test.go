package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// ProviderCredentials(r) is the request-level entry point every
// authenticated handler uses to recover the caller's provider credentials
// from the wire-level Auth-Token header (auth.HeaderName). The existing
// TestProviderCredentials_ZeroValueIsAnonymous misnamed test actually
// exercises provider.Credentials' zero value, not this function — so the
// real HTTP-extraction path (Header.Get(HeaderName) → ParseAuthToken) was
// uncovered. These tests assert the parsed struct returned from a real
// *http.Request, the user-visible result the handlers branch on.

func TestProviderCredentials_ReadsHeaderAndParses(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/search", nil)
	r.Header.Set(HeaderName, "nnmclub:cookie:nnm_session=xyz")

	parsed := ProviderCredentials(r)
	if parsed == nil {
		t.Fatal("expected non-nil ParsedCredentials for a populated Auth-Token header")
	}
	if parsed.ProviderID != "nnmclub" {
		t.Errorf("ProviderID: got %q, want nnmclub", parsed.ProviderID)
	}
	if parsed.Creds.Type != "cookie" {
		t.Errorf("Creds.Type: got %q, want cookie", parsed.Creds.Type)
	}
	if parsed.Creds.CookieValue != "nnm_session=xyz" {
		t.Errorf("Creds.CookieValue: got %q, want nnm_session=xyz", parsed.Creds.CookieValue)
	}
}

func TestProviderCredentials_LegacyBareCookieFromHeader(t *testing.T) {
	// A colon-free header is the legacy bare-cookie form; the wrapper must
	// surface it as a rutracker cookie (backward compat with old clients).
	r := httptest.NewRequest(http.MethodGet, "/search", nil)
	r.Header.Set(HeaderName, "bb_session=0-12345-abcdef")

	parsed := ProviderCredentials(r)
	if parsed == nil {
		t.Fatal("expected non-nil for legacy bare cookie")
	}
	if parsed.ProviderID != "rutracker" {
		t.Errorf("ProviderID: got %q, want rutracker", parsed.ProviderID)
	}
	if parsed.Creds.CookieValue != "bb_session=0-12345-abcdef" {
		t.Errorf("Creds.CookieValue: got %q", parsed.Creds.CookieValue)
	}
}

func TestProviderCredentials_MissingHeaderIsNil(t *testing.T) {
	// No Auth-Token header → anonymous → nil. A handler relying on this
	// to gate anonymous access would break silently if the wrapper instead
	// fabricated a credential.
	r := httptest.NewRequest(http.MethodGet, "/search", nil)

	if parsed := ProviderCredentials(r); parsed != nil {
		t.Errorf("expected nil for absent Auth-Token header, got %+v", parsed)
	}
}
