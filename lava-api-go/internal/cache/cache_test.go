package cache

import "testing"

func TestKeyDeterministic(t *testing.T) {
	a := Key("GET", "/forum/{id}",
		map[string]string{"id": "42"},
		map[string][]string{"q": {"foo"}, "page": {"1"}},
		"abc",
	)
	b := Key("GET", "/forum/{id}",
		map[string]string{"id": "42"},
		map[string][]string{"page": {"1"}, "q": {"foo"}},
		"abc",
	)
	if a != b {
		t.Fatalf("Key not deterministic across query-key order:\n  %s\n  %s", a, b)
	}
}

func TestKeyDistinguishesPathVars(t *testing.T) {
	a := Key("GET", "/topic/{id}", map[string]string{"id": "1"}, nil, "")
	b := Key("GET", "/topic/{id}", map[string]string{"id": "2"}, nil, "")
	if a == b {
		t.Fatal("Key collided across distinct id values")
	}
}

func TestKeyDistinguishesAuthRealm(t *testing.T) {
	a := Key("GET", "/favorites", nil, nil, "user-A")
	b := Key("GET", "/favorites", nil, nil, "user-B")
	if a == b {
		t.Fatal("Key collided across distinct auth realms")
	}
}

func TestKeyAnonDefault(t *testing.T) {
	a := Key("GET", "/x", nil, nil, "")
	b := Key("GET", "/x", nil, nil, "anon")
	if a != b {
		t.Fatal("Key for empty realm must match key for explicit 'anon'")
	}
}
