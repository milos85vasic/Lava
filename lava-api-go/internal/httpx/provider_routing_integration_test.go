package httpx_test

// End-to-end proof that the REAL provider clients (constructed by their real
// NewClient) egress through the configurable outbound proxy when
// LAVA_API_UPSTREAM_PROXY is set, and go direct when it is unset with no
// *_PROXY env. This is the load-bearing anti-bluff test for the proxy wiring:
// it stands up a live local HTTP proxy and asserts the proxy actually saw the
// provider's request — user-visible network behavior, not a mock call count
// (§6.J / §6.AB). The three providers exercised are exactly the Russian
// trackers blocked at the datacenter egress that motivated this feature
// (rutracker, kinozal, nnmclub), each driven through its exported Fetch.

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/httpx"
	"digital.vasic.lava.apigo/internal/kinozal"
	"digital.vasic.lava.apigo/internal/nnmclub"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// providerFetcher fires exactly one real outbound GET through a provider's real
// NewClient + exported Fetch against base, returning the status the client saw.
type providerFetcher struct {
	name string
	fire func(ctx context.Context, base string) (int, error)
}

func providerFetchers() []providerFetcher {
	return []providerFetcher{
		{"rutracker", func(ctx context.Context, base string) (int, error) {
			_, st, err := rutracker.NewClient(base).Fetch(ctx, "/probe", "")
			return st, err
		}},
		{"kinozal", func(ctx context.Context, base string) (int, error) {
			_, st, err := kinozal.NewClient(base).Fetch(ctx, "/probe", "")
			return st, err
		}},
		{"nnmclub", func(ctx context.Context, base string) (int, error) {
			_, st, err := nnmclub.NewClient(base).Fetch(ctx, "/probe", "")
			return st, err
		}},
	}
}

// recordingProxy is a minimal HTTP proxy: a provider request to http://<target>
// arrives here in absolute-URI form; we record the target Host and answer 200.
type recordingProxy struct {
	srv   *httptest.Server
	mu    sync.Mutex
	hosts []string
}

func newRecordingProxy() *recordingProxy {
	p := &recordingProxy{}
	p.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p.mu.Lock()
		p.hosts = append(p.hosts, r.Host)
		p.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("{}"))
	}))
	return p
}

func (p *recordingProxy) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.hosts)
}

func (p *recordingProxy) sawHost(substr string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, h := range p.hosts {
		if strings.Contains(h, substr) {
			return true
		}
	}
	return false
}

func (p *recordingProxy) close() { p.srv.Close() }

// TestProviderClientsTraverseUpstreamProxyWhenConfigured: with
// LAVA_API_UPSTREAM_PROXY set (via httpx.Configure), each Russian-tracker
// client's request MUST traverse the proxy.
func TestProviderClientsTraverseUpstreamProxyWhenConfigured(t *testing.T) {
	proxy := newRecordingProxy()
	defer proxy.close()

	if err := httpx.Configure(proxy.srv.URL); err != nil {
		t.Fatalf("Configure(%q): %v", proxy.srv.URL, err)
	}
	defer func() { _ = httpx.Configure("") }() // restore default

	const target = "http://tracker.invalid" // non-loopback => proxy is used; never resolved (the proxy does)
	for _, f := range providerFetchers() {
		t.Run(f.name, func(t *testing.T) {
			before := proxy.count()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			st, err := f.fire(ctx, target)
			if err != nil {
				t.Fatalf("%s fetch via proxy: %v", f.name, err)
			}
			if st != http.StatusOK {
				t.Fatalf("%s: want HTTP 200 served by the proxy, got %d", f.name, st)
			}
			if proxy.count() <= before {
				t.Fatalf("%s request did NOT traverse the proxy (proxy hit count unchanged at %d)", f.name, before)
			}
			if !proxy.sawHost("tracker.invalid") {
				t.Fatalf("%s: proxy never saw the target host; recorded hosts=%v", f.name, proxy.hosts)
			}
		})
	}
}

// TestProviderClientsGoDirectWhenUnsetAndNoEnv: with no explicit proxy and no
// *_PROXY env, requests go DIRECT — the proxy stays unhit and the direct
// backend is reached.
func TestProviderClientsGoDirectWhenUnsetAndNoEnv(t *testing.T) {
	// Ensure no ambient proxy env leaks into http.ProxyFromEnvironment.
	for _, k := range []string{
		"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
		"http_proxy", "https_proxy", "all_proxy",
	} {
		t.Setenv(k, "")
	}
	if err := httpx.Configure(""); err != nil {
		t.Fatalf("Configure(\"\"): %v", err)
	}

	proxy := newRecordingProxy() // MUST stay unhit
	defer proxy.close()

	var backendHits int32
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&backendHits, 1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("{}"))
	}))
	defer backend.Close()

	for _, f := range providerFetchers() {
		t.Run(f.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			st, err := f.fire(ctx, backend.URL)
			if err != nil {
				t.Fatalf("%s direct fetch: %v", f.name, err)
			}
			if st != http.StatusOK {
				t.Fatalf("%s: want HTTP 200 from direct backend, got %d", f.name, st)
			}
		})
	}

	if got := proxy.count(); got != 0 {
		t.Fatalf("no proxy was configured, but the proxy was hit %d time(s)", got)
	}
	if got := atomic.LoadInt32(&backendHits); got == 0 {
		t.Fatal("direct backend was never reached")
	}
}
