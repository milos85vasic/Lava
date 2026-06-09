package rutracker

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/provider"
)

// TestAdapter_Login_WrongCredits_NotFakeSuccess_E2E drives ProviderAdapter.Login
// through the REAL *Client against a server that returns the rutracker
// WRONG-CREDENTIALS page (the "неверный пароль" sentence + the login-form, NO
// Set-Cookie). The Client maps that to a WrongCredits AuthResponseDto variant.
//
// LVA-046 (same bug class as LVA-032 / LVA-025): ProviderAdapter.Login narrowed
// the union with the BLIND generated accessor resp.AsAuthResponseDtoSuccess(),
// which does a raw json.Unmarshal of the union bytes into AuthResponseDtoSuccess
// and NEVER consults the "type" discriminator. AuthResponseDtoSuccess.User is a
// non-pointer UserDto, so unmarshalling a {"type":"WrongCredits"} union (with no
// "user" key) into it SUCCEEDS with NO error — User stays zero-valued. The
// `if err != nil` guard the author relied on to detect "not Success" therefore
// never fires for WrongCredits, and Login returns
// provider.LoginResult{Success:true, AuthToken:""} — a FAKE SUCCESSFUL LOGIN
// with an empty session token. The user is told login succeeded while their
// credentials were rejected; every subsequent authenticated call sends an empty
// cookie and 401s.
//
// Sixth Law clause 3: primary assertion is on the user-visible return — Login
// MUST surface provider.ErrUnauthorized for a WrongCredits upstream response,
// never a Success result.
//
// Falsifiability: with the blind accessor in place, this test FAILS because
// Login returns (Success:true, nil) instead of (nil, ErrUnauthorized).
func TestAdapter_Login_WrongCredits_NotFakeSuccess_E2E(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/login.php") {
			// WrongCredits page: login-form present + the wrong-password
			// sentence, NO Set-Cookie. The Client builds a WrongCredits
			// AuthResponseDto variant from exactly this shape.
			_, _ = w.Write([]byte("<html><body><form id=\"login-form\">" +
				"неверный пароль</form></body></html>"))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	res, err := a.Login(context.Background(), provider.LoginOpts{Username: "user", Password: "wrong"})
	if err != provider.ErrUnauthorized {
		t.Fatalf("Login(wrong-credentials) err = %v, want provider.ErrUnauthorized; "+
			"the blind AsAuthResponseDtoSuccess() accessor reported a FAKE success "+
			"for a WrongCredits upstream response (LVA-046)", err)
	}
	if res != nil {
		t.Fatalf("Login(wrong-credentials) result = %+v, want nil — a WrongCredits "+
			"response MUST NOT yield a LoginResult (LVA-046)", res)
	}
}

// TestAdapter_Login_NonSuccessVariantCarryingUser_E2E is the sharpest
// falsifiability proof of the LVA-046 discriminator bug. It constructs a
// WrongCredits AuthResponseDto that ALSO carries a stray "user" object on the
// wire (a forward-compatible / cross-backend payload the Ktor parity path could
// emit). The blind generated accessor maps it straight into
// AuthResponseDtoSuccess{Type:"WrongCredits", User:{Token:"FAKE"}} with NO error,
// so the discriminator-ignoring code path returns Success:true with a non-empty
// FAKE token — the textbook LVA-032 type-confusion. A discriminator-honoring
// narrowing rejects it.
//
// This test asserts on the discriminator-narrowing helper used by the fix
// directly so it stays valid regardless of which upstream emits the variant.
//
// Falsifiability: replace the discriminator-honoring narrowing with the blind
// resp.AsAuthResponseDtoSuccess() and this test FAILS (it reports the FAKE token
// as a success).
func TestAdapter_Login_NonSuccessVariantCarryingUser_E2E(t *testing.T) {
	// Build a WrongCredits union that ALSO carries a user object — the
	// cross-backend payload that defeats the blind accessor.
	var dto gen.AuthResponseDto
	if err := dto.FromAuthResponseDtoWrongCredits(gen.AuthResponseDtoWrongCredits{
		Type: gen.WrongCredits,
	}); err != nil {
		t.Fatalf("build WrongCredits union: %v", err)
	}
	// Merge a stray user object onto the WrongCredits union — this is the
	// wire shape the blind accessor silently mis-maps to Success.
	if err := dto.MergeAuthResponseDtoSuccess(gen.AuthResponseDtoSuccess{
		User: gen.UserDto{Token: "FAKE-SESSION-TOKEN"},
	}); err != nil {
		t.Fatalf("merge stray user: %v", err)
	}
	// MergeAuthResponseDtoSuccess overwrites Type to "Success"; restore the
	// WrongCredits discriminator so the union is honestly a non-Success
	// variant that merely carries user bytes.
	if err := dto.MergeAuthResponseDtoWrongCredits(gen.AuthResponseDtoWrongCredits{
		Type: gen.WrongCredits,
	}); err != nil {
		t.Fatalf("restore WrongCredits discriminator: %v", err)
	}

	disc, err := dto.Discriminator()
	if err != nil {
		t.Fatalf("Discriminator: %v", err)
	}
	if disc != string(gen.WrongCredits) {
		t.Fatalf("test fixture discriminator = %q, want WrongCredits", disc)
	}

	// The production narrowing MUST treat this as a non-Success variant.
	res := loginResultFromAuthResponse(&dto)
	if res != nil {
		t.Fatalf("loginResultFromAuthResponse(WrongCredits+strayUser) = %+v, want nil; "+
			"the discriminator was ignored and a FAKE token (%q) was reported as success (LVA-046)",
			res, res.AuthToken)
	}
}
