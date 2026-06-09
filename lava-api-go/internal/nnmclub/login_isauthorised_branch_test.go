package nnmclub

import "testing"

// IsAuthorised has two independent positive markers:
//
//  1. a logout link  (a[href*=logout])                       — covered by TestIsAuthorised_LoggedIn
//  2. a profile-link fallback: NO login.php link present AND  — UNCOVERED before this file
//     at least one profile.php link present.
//
// The fallback (login.go) is the subtle one: a page that merely
// contains a profile link is NOT enough — there must ALSO be no
// login.php link. NNM-Club renders a "login" anchor on anonymous pages
// even when a profile link is incidentally present (e.g. another user's
// profile), so the guard prevents a false "you are logged in" verdict.
//
// LATENT BUG THIS TEST EXPOSED: the fallback's CSS attribute selectors
// were written unquoted — a[href*=login.php] / a[href*=profile.php]. An
// unquoted CSS attribute value containing '.' is not a valid identifier,
// so cascadia/goquery matched it against NOTHING and the fallback could
// never fire (dead code). The first test below failed RED until the
// selectors were quoted in login.go. Falsifiability: revert the quotes
// in login.go and TestIsAuthorised_ProfileLinkFallback_NoLoginLink fails
// with "expected IsAuthorised=true ...".
//
// These tests drive the real production IsAuthorised (no fakes) and
// assert on its bool return — the exact value the auth flow branches on.
// Inputs are inline HTML so the test is fully hermetic.

func TestIsAuthorised_ProfileLinkFallback_NoLoginLink(t *testing.T) {
	// No logout link, no login.php link, but a profile.php link exists.
	// Per the fallback this MUST be treated as authorised.
	html := []byte(`<html><body>
		<a href="/forum/profile.php?mode=viewprofile&u=42">My profile</a>
		<a href="/forum/index.php">Forum</a>
	</body></html>`)
	if !IsAuthorised(html) {
		t.Fatal("profile-link fallback: expected IsAuthorised=true when a profile.php link is present and no login.php link exists")
	}
}

func TestIsAuthorised_ProfileLinkPresentButLoginLinkAlsoPresent(t *testing.T) {
	// A profile.php link is present, BUT a login.php link is ALSO present
	// (the anonymous-page case). The guard (login.php count == 0) MUST
	// veto the fallback, so this is NOT authorised.
	html := []byte(`<html><body>
		<a href="/forum/login.php">Log in</a>
		<a href="/forum/profile.php?mode=viewprofile&u=7">Some user</a>
	</body></html>`)
	if IsAuthorised(html) {
		t.Fatal("login.php guard: expected IsAuthorised=false when a login.php link is present even though a profile.php link also exists")
	}
}

func TestIsAuthorised_NeitherMarker(t *testing.T) {
	// No logout link, no profile.php link, no login.php link → both
	// markers fail → not authorised. Exercises the final `return false`.
	html := []byte(`<html><body><a href="/forum/index.php">Home</a></body></html>`)
	if IsAuthorised(html) {
		t.Fatal("expected IsAuthorised=false when neither a logout link nor a profile.php link is present")
	}
}
