package auth

import (
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestParseAuthToken_LegacyBareCookie(t *testing.T) {
	parsed := ParseAuthToken("bb_session=0-12345-abcdef")
	if parsed == nil {
		t.Fatal("expected non-nil for legacy cookie")
	}
	if parsed.ProviderID != "rutracker" {
		t.Errorf("providerID: got %q, want rutracker", parsed.ProviderID)
	}
	if parsed.Creds.Type != "cookie" {
		t.Errorf("type: got %q, want cookie", parsed.Creds.Type)
	}
	if parsed.Creds.CookieValue != "bb_session=0-12345-abcdef" {
		t.Errorf("cookie: got %q", parsed.Creds.CookieValue)
	}
}

func TestParseAuthToken_CookieFormat(t *testing.T) {
	parsed := ParseAuthToken("nnmclub:cookie:nnm_session=xyz")
	if parsed == nil {
		t.Fatal("expected non-nil")
	}
	if parsed.ProviderID != "nnmclub" {
		t.Errorf("providerID: got %q, want nnmclub", parsed.ProviderID)
	}
	if parsed.Creds.Type != "cookie" {
		t.Errorf("type: got %q, want cookie", parsed.Creds.Type)
	}
	if parsed.Creds.CookieValue != "nnm_session=xyz" {
		t.Errorf("cookie: got %q", parsed.Creds.CookieValue)
	}
}

func TestParseAuthToken_TokenFormat(t *testing.T) {
	parsed := ParseAuthToken("archiveorg:token:my-api-token")
	if parsed == nil {
		t.Fatal("expected non-nil")
	}
	if parsed.ProviderID != "archiveorg" {
		t.Errorf("providerID: got %q, want archiveorg", parsed.ProviderID)
	}
	if parsed.Creds.Type != "token" {
		t.Errorf("type: got %q, want token", parsed.Creds.Type)
	}
	if parsed.Creds.Token != "my-api-token" {
		t.Errorf("token: got %q", parsed.Creds.Token)
	}
}

func TestParseAuthToken_ApiKeyFormat(t *testing.T) {
	parsed := ParseAuthToken("gutenberg:apikey:secret-key-123")
	if parsed == nil {
		t.Fatal("expected non-nil")
	}
	if parsed.ProviderID != "gutenberg" {
		t.Errorf("providerID: got %q, want gutenberg", parsed.ProviderID)
	}
	if parsed.Creds.Type != "apikey" {
		t.Errorf("type: got %q, want apikey", parsed.Creds.Type)
	}
	if parsed.Creds.APIKey != "secret-key-123" {
		t.Errorf("apiKey: got %q", parsed.Creds.APIKey)
	}
}

func TestParseAuthToken_PasswordFormat(t *testing.T) {
	parsed := ParseAuthToken("kinozal:password:myuser:mypass")
	if parsed == nil {
		t.Fatal("expected non-nil")
	}
	if parsed.ProviderID != "kinozal" {
		t.Errorf("providerID: got %q, want kinozal", parsed.ProviderID)
	}
	if parsed.Creds.Type != "password" {
		t.Errorf("type: got %q, want password", parsed.Creds.Type)
	}
	if parsed.Creds.Username != "myuser" {
		t.Errorf("username: got %q", parsed.Creds.Username)
	}
	if parsed.Creds.Password != "mypass" {
		t.Errorf("password: got %q", parsed.Creds.Password)
	}
}

// TestParseAuthToken_PasswordWithColonPreserved pins the user-visible behavior
// that a password CONTAINING a colon is forwarded whole to the upstream tracker,
// not truncated at the first colon. The credValue split MUST cap at two parts
// (username + the entire remainder as password) — i.e. strings.SplitN(value, ":", 2).
//
// Discrimination gap this closes (§6.AB / §6.N): TestParseAuthToken_PasswordFormat
// uses "myuser:mypass" — a password with NO colon — so it stays green even if the
// parser used the unlimited strings.Split, which would yield len(pwParts) != 2 for a
// colon-bearing password and silently drop BOTH username and password (zero-value
// Credentials → the user's login against e.g. kinozal/nnmclub FAILS). A user whose
// password legitimately contains ':' could never authenticate, and no prior test
// would have noticed.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2): with `strings.SplitN(credValue, ":", 2)`
// replaced by `strings.Split(credValue, ":")` in ParseAuthToken, this test FAILS:
//
//	password "p@ss:w0rd:!" split into 3 parts → len(pwParts) != 2 → Username/Password
//	never assigned → got Username "" want "alice"; got Password "" want "p@ss:w0rd:!"
//
// Reverted: yes. The unmutated parser keeps the password whole and this test passes.
func TestParseAuthToken_PasswordWithColonPreserved(t *testing.T) {
	const wantUser = "alice"
	const wantPass = "p@ss:w0rd:!" // password legitimately contains two colons
	parsed := ParseAuthToken("kinozal:password:" + wantUser + ":" + wantPass)
	if parsed == nil {
		t.Fatal("expected non-nil for colon-bearing password header")
	}
	if parsed.Creds.Type != "password" {
		t.Errorf("type: got %q, want password", parsed.Creds.Type)
	}
	if parsed.Creds.Username != wantUser {
		t.Errorf("username: got %q, want %q", parsed.Creds.Username, wantUser)
	}
	if parsed.Creds.Password != wantPass {
		t.Errorf("password truncated at colon: got %q, want %q (the whole remainder after the first colon MUST be the password)", parsed.Creds.Password, wantPass)
	}
}

func TestParseAuthToken_NoneFormat(t *testing.T) {
	parsed := ParseAuthToken("archiveorg:none")
	if parsed == nil {
		t.Fatal("expected non-nil")
	}
	if parsed.ProviderID != "archiveorg" {
		t.Errorf("providerID: got %q, want archiveorg", parsed.ProviderID)
	}
	if parsed.Creds.Type != "none" {
		t.Errorf("type: got %q, want none", parsed.Creds.Type)
	}
}

func TestParseAuthToken_Empty(t *testing.T) {
	parsed := ParseAuthToken("")
	if parsed != nil {
		t.Errorf("expected nil for empty header, got %+v", parsed)
	}
}

func TestParseAuthToken_Invalid(t *testing.T) {
	// Single token without colon → legacy bare cookie for rutracker.
	parsed := ParseAuthToken("invalid")
	if parsed == nil {
		t.Fatal("expected non-nil for legacy fallback")
	}
	if parsed.ProviderID != "rutracker" {
		t.Errorf("providerID: got %q, want rutracker", parsed.ProviderID)
	}
	if parsed.Creds.CookieValue != "invalid" {
		t.Errorf("cookie: got %q", parsed.Creds.CookieValue)
	}
}

func TestProviderCredentials_ZeroValueIsAnonymous(t *testing.T) {
	var creds provider.Credentials
	if creds.Type != "" {
		t.Errorf("zero value type should be empty, got %q", creds.Type)
	}
}
