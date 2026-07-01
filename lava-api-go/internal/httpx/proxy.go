// Package httpx provides the shared outbound-HTTP wiring every lava-api-go
// upstream provider client uses to reach the internet.
//
// The single responsibility today is a CONFIGURABLE OUTBOUND PROXY. The Lava
// proxy/API service runs from a fixed egress IP; some upstream trackers
// (rutracker.org, kinozal.tv, nnmclub.to) are blocked at a datacenter egress
// (DNS failure / TLS interception at the network layer). To let the operator
// route around that block with a residential / VPN / SOCKS egress, every
// provider transport routes through the proxy this package resolves.
//
// §6.R (No-Hardcoding): no proxy address is baked in. The proxy is supplied at
// runtime via LAVA_API_UPSTREAM_PROXY (parsed by internal/config) or, when that
// is unset, via the standard HTTP_PROXY / HTTPS_PROXY / ALL_PROXY / NO_PROXY
// environment variables (http.ProxyFromEnvironment). The default resolver
// (before Configure runs, and on the gomobile embed path which has no
// config.Load) is http.ProxyFromEnvironment, so the standard env mechanism
// works everywhere with zero extra wiring.
//
// SOCKS5 note: net/http's Transport natively dials "socks5://" (and
// "socks5h://" via the env path) proxy URLs returned by Transport.Proxy, so no
// golang.org/x/net/proxy dependency is required.
package httpx

import (
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
)

// proxyResolver matches the signature of http.Transport.Proxy.
type proxyResolver = func(*http.Request) (*url.URL, error)

var (
	mu sync.RWMutex
	// resolver is the process-wide outbound-proxy resolver. It defaults to
	// http.ProxyFromEnvironment so the standard *_PROXY env vars are honored
	// even if Configure is never called.
	resolver proxyResolver = http.ProxyFromEnvironment
)

// ProxyFunc builds an http.Transport.Proxy resolver from a proxy URL string
// (the LAVA_API_UPSTREAM_PROXY value).
//
//   - empty string  → http.ProxyFromEnvironment (standard *_PROXY env vars).
//   - non-empty     → http.ProxyURL(parsed). net/http honors http://, https://
//     and socks5:// schemes here.
//
// A non-empty value that does not parse, or that lacks a scheme or host, is a
// hard error — a misconfigured operator env must be loud, never silently
// ignored (§6.J).
func ProxyFunc(proxyURL string) (proxyResolver, error) {
	trimmed := strings.TrimSpace(proxyURL)
	if trimmed == "" {
		return http.ProxyFromEnvironment, nil
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return nil, fmt.Errorf("httpx: invalid LAVA_API_UPSTREAM_PROXY %q: %w", proxyURL, err)
	}
	if u.Scheme == "" || u.Host == "" {
		return nil, fmt.Errorf(
			"httpx: LAVA_API_UPSTREAM_PROXY %q must include a scheme and host "+
				"(http://, https://, or socks5:// — see LAVA_API_UPSTREAM_PROXY in .env.example for the full form)",
			proxyURL,
		)
	}
	// Explicit proxy: route every non-loopback request through u. Loopback /
	// localhost is NEVER proxied — internal sidecars (Jackett, FlareSolverr)
	// are reached at 127.0.0.1 and an external upstream proxy cannot reach the
	// operator's loopback. This mirrors http.ProxyFromEnvironment's built-in
	// localhost exemption (http.ProxyURL alone does NOT honor it).
	return func(req *http.Request) (*url.URL, error) {
		if isLoopbackHost(req.URL.Hostname()) {
			return nil, nil
		}
		return u, nil
	}, nil
}

// isLoopbackHost reports whether host is "localhost" or a loopback IP literal
// (127.0.0.0/8, ::1). Used to keep internal-sidecar traffic off the upstream
// proxy.
func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback()
	}
	return false
}

// Configure sets the process-wide outbound proxy from a proxy URL string.
// Call it ONCE at startup (after config load, before serving). Returns the
// ProxyFunc error verbatim so the caller can fail-fast on a bad value.
func Configure(proxyURL string) error {
	fn, err := ProxyFunc(proxyURL)
	if err != nil {
		return err
	}
	mu.Lock()
	resolver = fn
	mu.Unlock()
	return nil
}

// Proxy is the http.Transport.Proxy function every provider transport installs.
// It defers to the process-wide resolver at REQUEST time, so a provider client
// constructed before Configure runs still picks up the configured proxy.
func Proxy(req *http.Request) (*url.URL, error) {
	mu.RLock()
	fn := resolver
	mu.RUnlock()
	return fn(req)
}

// NewTransport returns a clone of http.DefaultTransport with Proxy wired to
// Proxy. Provider clients that previously relied on http.DefaultTransport
// (i.e. constructed &http.Client{} with no Transport) install this so their
// egress honors the configured outbound proxy. Cloning preserves every default
// timeout / pool setting and only overrides Proxy.
func NewTransport() *http.Transport {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.Proxy = Proxy
	return t
}
