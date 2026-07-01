package httpx

import (
	"net/http"
	"testing"
)

func newReq(t *testing.T, target string) *http.Request {
	t.Helper()
	r, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		t.Fatalf("new request %q: %v", target, err)
	}
	return r
}

// TestProxyFunc_ParsesSchemes proves a non-empty LAVA_API_UPSTREAM_PROXY of
// each supported scheme yields a resolver that routes a (non-loopback) request
// through that exact proxy URL. socks5 is accepted here; net/http's Transport
// dials socks5 proxy URLs natively, so no extra dependency is needed.
func TestProxyFunc_ParsesSchemes(t *testing.T) {
	cases := []struct{ name, in string }{
		{"http", "http://proxy.example:8080"},
		{"https", "https://proxy.example:8443"},
		{"socks5", "socks5://127.0.0.1:1080"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fn, err := ProxyFunc(c.in)
			if err != nil {
				t.Fatalf("ProxyFunc(%q): %v", c.in, err)
			}
			got, err := fn(newReq(t, "http://tracker.invalid/x"))
			if err != nil {
				t.Fatalf("resolver: %v", err)
			}
			if got == nil {
				t.Fatalf("ProxyFunc(%q): resolver returned nil (direct) for a non-loopback request", c.in)
			}
			if got.String() != c.in {
				t.Fatalf("ProxyFunc(%q): resolver returned %q, want %q", c.in, got.String(), c.in)
			}
		})
	}
}

// TestProxyFunc_EmptyReturnsFallback: empty value => a non-nil resolver (the
// http.ProxyFromEnvironment fallback), never an error.
func TestProxyFunc_EmptyReturnsFallback(t *testing.T) {
	fn, err := ProxyFunc("")
	if err != nil {
		t.Fatalf("ProxyFunc(\"\"): unexpected error %v", err)
	}
	if fn == nil {
		t.Fatal("ProxyFunc(\"\") returned a nil resolver")
	}
}

// TestProxyFunc_RejectsMalformed: a misconfigured value is a hard error (§6.J —
// loud, never a silent fall-through to a direct egress the operator believed was
// proxied). "localhost:1080" is the canonical scheme-forgotten mistake.
func TestProxyFunc_RejectsMalformed(t *testing.T) {
	for _, in := range []string{"://nohost", "http://", "localhost:1080"} {
		if _, err := ProxyFunc(in); err == nil {
			t.Errorf("ProxyFunc(%q): want error, got nil", in)
		}
	}
}

// TestProxyFunc_LoopbackNeverProxied: even with an explicit proxy configured,
// loopback / localhost targets are routed direct — internal sidecars (Jackett,
// FlareSolverr) live at 127.0.0.1 and an external proxy cannot reach them.
func TestProxyFunc_LoopbackNeverProxied(t *testing.T) {
	fn, err := ProxyFunc("http://proxy.example:8080")
	if err != nil {
		t.Fatalf("ProxyFunc: %v", err)
	}
	for _, target := range []string{
		"http://localhost:9117/x",
		"http://127.0.0.1:9117/x",
		"http://[::1]:9117/x",
	} {
		got, err := fn(newReq(t, target))
		if err != nil {
			t.Fatalf("resolver(%q): %v", target, err)
		}
		if got != nil {
			t.Fatalf("loopback target %q must be direct, but resolver returned proxy %v", target, got)
		}
	}
}

// TestConfigureAndNewTransport: Configure switches the process-wide resolver and
// NewTransport().Proxy routes through it (and still exempts loopback).
func TestConfigureAndNewTransport(t *testing.T) {
	t.Cleanup(func() { _ = Configure("") }) // restore default for other tests
	const proxyURL = "http://proxy.example:8080"
	if err := Configure(proxyURL); err != nil {
		t.Fatalf("Configure(%q): %v", proxyURL, err)
	}
	tr := NewTransport()
	if tr.Proxy == nil {
		t.Fatal("NewTransport().Proxy is nil")
	}
	got, err := tr.Proxy(newReq(t, "http://tracker.invalid/x"))
	if err != nil {
		t.Fatalf("transport proxy resolver: %v", err)
	}
	if got == nil || got.String() != proxyURL {
		t.Fatalf("NewTransport did not route through configured proxy; got %v, want %q", got, proxyURL)
	}
	lb, err := tr.Proxy(newReq(t, "http://127.0.0.1:9117/x"))
	if err != nil {
		t.Fatalf("transport proxy resolver (loopback): %v", err)
	}
	if lb != nil {
		t.Fatalf("loopback must be direct via the transport, got %v", lb)
	}
}

// TestConfigure_RejectsMalformed: Configure surfaces the ProxyFunc error so the
// caller (main.go) can fail-fast on a bad LAVA_API_UPSTREAM_PROXY.
func TestConfigure_RejectsMalformed(t *testing.T) {
	t.Cleanup(func() { _ = Configure("") })
	if err := Configure("://bad"); err == nil {
		t.Fatal("Configure(\"://bad\"): want error, got nil")
	}
}
