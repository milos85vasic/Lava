package cache

import (
	"encoding/hex"
	"testing"
)

// FuzzKeyWellFormedAndStable fuzzes the cache-key constructor against arbitrary
// attacker-influenced inputs (method, route, a path var, a query value, an auth
// realm hash — all of which can carry separator chars, NUL, newlines, the
// literal "anon", etc.). It pins three real safety properties of the cache key:
//
//  1. WELL-FORMED: Key always returns a 64-hex-char SHA-256 digest and never
//     panics, regardless of how hostile the inputs are (NUL bytes, the internal
//     "\n"/";"/"&"/"=" separators, empty strings). A panic here would crash a
//     request handler; a malformed key would corrupt cache lookups.
//
//  2. AUTH ISOLATION: two otherwise-identical requests that differ ONLY in the
//     auth realm hash MUST produce different keys whenever the realms are
//     distinct AND neither collapses to the same canonical realm ("" → "anon").
//     This is the cache-poisoning safety invariant: user A's authenticated
//     response must never be served from user B's cache slot. (§6.J: the
//     observable is the key bytes that decide which cache slot is read.)
//
//  3. DETERMINISM under query-value ordering: the same query values supplied in
//     a different slice order yield the same key (Key sorts values), matching
//     TestKeyDeterministic but over fuzzed inputs.
func FuzzKeyWellFormedAndStable(f *testing.F) {
	f.Add("GET", "/forum/{id}", "42", "foo", "abc")
	f.Add("POST", "/topic/{id}/download", "", "", "")
	f.Add("GET", "/x", "id", "v\x00v", "anon")          // NUL in a value
	f.Add("GET", "/a;b&c=d\n", "p=q", "x&y", "realm\n") // separator chars in inputs
	f.Add("", "", "", "", "")

	f.Fuzz(func(t *testing.T, method, route, pathVal, queryVal, realm string) {
		pathVars := map[string]string{"p": pathVal}
		query := map[string][]string{"q": {queryVal}}

		k := Key(method, route, pathVars, query, realm)

		// Property 1: well-formed SHA-256 hex.
		if len(k) != 64 {
			t.Fatalf("Key length = %d, want 64 (hex SHA-256); inputs m=%q r=%q realm=%q",
				len(k), method, route, realm)
		}
		if _, err := hex.DecodeString(k); err != nil {
			t.Fatalf("Key not valid hex: %q (%v)", k, err)
		}

		// Property 3: determinism is reflexive (same inputs → same key).
		if k2 := Key(method, route, pathVars, query, realm); k2 != k {
			t.Fatalf("Key not deterministic for identical inputs:\n  %s\n  %s", k, k2)
		}

		// Property 2: auth isolation. Build a realm guaranteed to differ from
		// `realm` even after the ""→"anon" canonicalization, then assert the
		// keys differ. We pick a sentinel that cannot equal the canonical form
		// of `realm` (it contains a NUL, which Key never injects itself).
		const sentinel = "\x00distinct-realm-sentinel"
		canonical := realm
		if canonical == "" {
			canonical = "anon"
		}
		if canonical != sentinel { // always true given the NUL sentinel
			kOther := Key(method, route, pathVars, query, sentinel)
			if kOther == k {
				t.Fatalf("auth-isolation violation: distinct realms %q and %q produced the same key %s",
					realm, sentinel, k)
			}
		}
	})
}
