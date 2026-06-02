package mobile

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// stubScraper satisfies handlers.ScraperClient WITHOUT reaching the live
// rutracker.org upstream. This is the parity_test.go pattern — stub the
// external tracker boundary so the embed's real wiring (storage → router →
// handler) is exercised end-to-end against a deterministic upstream. Only the
// methods the tests drive return meaningful values; the rest return a sentinel
// error (never invoked by these tests).
type stubScraper struct {
	checkAuthorised bool
}

func (s stubScraper) CheckAuthorised(context.Context, string) (bool, error) {
	return s.checkAuthorised, nil
}
func (stubScraper) GetForum(context.Context, string) (*gen.ForumDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetCategoryPage(context.Context, string, *int, string) (*gen.CategoryPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetSearchPage(context.Context, rutracker.SearchOpts, string) (*gen.SearchPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTopic(context.Context, string, *int, string) (*gen.ForumTopicDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTopicPage(context.Context, string, *int, string) (*gen.TopicPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetCommentsPage(context.Context, string, *int, string) (*gen.CommentsPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) AddComment(context.Context, string, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) GetTorrent(context.Context, string, string) (*gen.ForumTopicDtoTorrent, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTorrentFile(context.Context, string, string) (*rutracker.TorrentFile, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetFavorites(context.Context, string) (*gen.FavoritesDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) AddFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) RemoveFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) Login(context.Context, rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) FetchCaptcha(context.Context, string) (*rutracker.CaptchaImage, error) {
	return nil, errors.New("stub")
}

// withStubScraper swaps the package scraperFactory to return the given stub +
// an empty registry for the duration of the test, then restores it.
func withStubScraper(t *testing.T, s handlers.ScraperClient) {
	t.Helper()
	prev := scraperFactory
	scraperFactory = func() (handlers.ScraperClient, *provider.ProviderRegistry) {
		return s, provider.NewRegistry()
	}
	t.Cleanup(func() { scraperFactory = prev })
}

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("freePort: %v", err)
	}
	defer func() { _ = ln.Close() }()
	return ln.Addr().(*net.TCPAddr).Port
}

func tempSQLitePath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "lava-mobile-test.db")
}

func configJSON(bind string, port int, dbPath string) string {
	cfg := map[string]any{
		"bindAddr":   bind,
		"port":       port,
		"sqlitePath": dbPath,
	}
	b, _ := json.Marshal(cfg)
	return string(b)
}

// tlsClient builds an https client that trusts ONLY the cert the embed wrote
// next to dbPath. It does NOT use InsecureSkipVerify — this proves real TLS:
// the client verifies the server's leaf cert against the on-disk cert the embed
// persisted, exactly as a LAN peer would after pinning it out-of-band.
func tlsClient(t *testing.T, dbPath string) *http.Client {
	t.Helper()
	certPath := filepath.Join(filepath.Dir(dbPath), certFileName)
	pem, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read embed cert %s: %v", certPath, err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		t.Fatalf("append embed cert to pool failed")
	}
	return &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				RootCAs: pool,
				// Address the server by 127.0.0.1; the cert SANs include
				// loopback so verification succeeds against the real cert.
				ServerName: "127.0.0.1",
			},
		},
	}
}

// waitClosed polls until a TCP dial to addr is refused (server fully stopped).
func waitClosed(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err != nil {
			return
		}
		_ = conn.Close()
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("port %s still accepting connections after Stop()", addr)
}

// TestStartHealthOverTLSStop is the load-bearing anti-bluff test: it starts the
// embed with TLS enabled, makes a REAL https GET (verifying the persisted cert,
// NOT InsecureSkipVerify) against /health, and asserts a real 200 + real JSON
// body, then Stop()s and asserts the port is closed.
func TestStartHealthOverTLSStop(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	addr := fmt.Sprintf("%s:%d", bind, port)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	client := tlsClient(t, dbPath)

	url := fmt.Sprintf("https://%s/health", addr)
	resp, err := client.Get(url)
	if err != nil {
		t.Fatalf("https GET(%s): %v — real TLS verification against persisted cert failed", url, err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("health status = %d, want 200", resp.StatusCode)
	}
	if resp.TLS == nil {
		t.Fatalf("response was not served over TLS (resp.TLS == nil)")
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode health body: %v", err)
	}
	if got := body["status"]; got != "alive" {
		t.Fatalf("health body status = %v, want \"alive\" (real JSON body assertion)", got)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	waitClosed(t, addr)
}

// TestRealProductionRouteOverTLS exercises a REAL production route end-to-end
// through the embed's full router (storage → router.Build → IndexHandler →
// stubbed upstream). GET /index resolves IndexHandler.GetIndex, which calls the
// scraper's CheckAuthorised and writes its JSON boolean. We stub the upstream
// (parity-test pattern) so no live rutracker.org call happens; the assertion is
// on the real JSON body the handler produced, NOT an error page.
func TestRealProductionRouteOverTLS(t *testing.T) {
	withStubScraper(t, stubScraper{checkAuthorised: true})

	port := freePort(t)
	bind := "127.0.0.1"
	addr := fmt.Sprintf("%s:%d", bind, port)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	client := tlsClient(t, dbPath)
	resp, err := client.Get(fmt.Sprintf("https://%s/index", addr))
	if err != nil {
		t.Fatalf("https GET(/index): %v", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/index status = %d, want 200 (real production route)", resp.StatusCode)
	}
	// The handler writes a bare JSON boolean — assert it equals the stubbed
	// upstream result. A 502/error page would NOT decode into a bool true here.
	var ok bool
	if err := json.NewDecoder(resp.Body).Decode(&ok); err != nil {
		t.Fatalf("/index body not a JSON bool: %v", err)
	}
	if !ok {
		t.Fatalf("/index body = %v, want true (the stubbed CheckAuthorised result)", ok)
	}
}

// TestStatusReportsHTTPSAndBindAddr asserts Status() reports the scheme https
// and the configured bind addr + port + backend.
func TestStatusReportsHTTPSAndBindAddr(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	var st map[string]any
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status() not valid JSON: %v", err)
	}
	if st["state"] != "running" {
		t.Fatalf("Status state = %v, want running", st["state"])
	}
	if st["scheme"] != "https" {
		t.Fatalf("Status scheme = %v, want https", st["scheme"])
	}
	if st["bindAddr"] != bind {
		t.Fatalf("Status bindAddr = %v, want %s", st["bindAddr"], bind)
	}
	if st["backend"] != "sqlite" {
		t.Fatalf("Status backend = %v, want sqlite", st["backend"])
	}
	if int(st["port"].(float64)) != port {
		t.Fatalf("Status port = %v, want %d", st["port"], port)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status() after stop not valid JSON: %v", err)
	}
	if st["state"] != "stopped" {
		t.Fatalf("Status state after Stop = %v, want stopped", st["state"])
	}
}

// TestBindAddrValidation asserts malformed bindAddr is rejected AND that
// 0.0.0.0 (the network-exposure default) is ACCEPTED — this is the explicit
// resolution of the automated security-review finding (network exposure is the
// intended design; loopback-only would defeat the feature).
func TestBindAddrValidation(t *testing.T) {
	// Malformed → rejected.
	err := Start(configJSON("not-an-ip", freePort(t), tempSQLitePath(t)))
	if err == nil {
		_ = Stop()
		t.Fatal("Start with malformed bindAddr returned nil, want error")
	}

	// 0.0.0.0 (wildcard) → ACCEPTED. Binding the wildcard is the intended
	// network-exposure design; the server must start successfully.
	dbPath := tempSQLitePath(t)
	port := freePort(t)
	if err := Start(configJSON("0.0.0.0", port, dbPath)); err != nil {
		t.Fatalf("Start with bindAddr 0.0.0.0 returned %v, want nil (network exposure is the intended design)", err)
	}
	t.Cleanup(func() { _ = Stop() })

	var st map[string]any
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status JSON: %v", err)
	}
	if st["bindAddr"] != "0.0.0.0" {
		t.Fatalf("Status bindAddr = %v, want 0.0.0.0", st["bindAddr"])
	}
	// Reach it over the wildcard via the loopback address; the cert SANs cover
	// loopback so real TLS verification succeeds.
	client := tlsClient(t, dbPath)
	resp, err := client.Get(fmt.Sprintf("https://127.0.0.1:%d/health", port))
	if err != nil {
		t.Fatalf("https GET against 0.0.0.0-bound server via loopback: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/health status = %d on 0.0.0.0-bound server, want 200", resp.StatusCode)
	}
}

// TestCertPersistsAcrossRestart asserts the self-signed cert is REUSED across a
// Stop→Start cycle (same cert bytes on disk, same cert served). A regenerated
// cert each boot would break any LAN peer that pinned the leaf out-of-band.
func TestCertPersistsAcrossRestart(t *testing.T) {
	bind := "127.0.0.1"
	dbPath := tempSQLitePath(t)
	certPath := filepath.Join(filepath.Dir(dbPath), certFileName)

	if err := Start(configJSON(bind, freePort(t), dbPath)); err != nil {
		t.Fatalf("Start #1: %v", err)
	}
	first, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read cert after start #1: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("Stop #1: %v", err)
	}

	if err := Start(configJSON(bind, freePort(t), dbPath)); err != nil {
		t.Fatalf("Start #2: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })
	second, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("read cert after start #2: %v", err)
	}

	if string(first) != string(second) {
		t.Fatalf("cert changed across restart — want REUSE of persisted cert, got regeneration")
	}
}

// TestStartWhileRunning asserts a second Start while one is running errors.
func TestStartWhileRunning(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	err := Start(configJSON(bind, freePort(t), tempSQLitePath(t)))
	if err == nil {
		t.Fatal("Start-while-running returned nil, want error")
	}
}

// TestStopWhenNotRunning documents the chosen idempotency contract: Stop when
// nothing is running returns an error (not a silent nil).
func TestStopWhenNotRunning(t *testing.T) {
	_ = Stop()
	if err := Stop(); err == nil {
		t.Fatal("Stop when not running returned nil, want error")
	}
}

// TestRequestCountIncrements asserts the counting handler records real traffic
// over TLS — Status().requestCount rises after real https calls.
func TestRequestCountIncrements(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	addr := fmt.Sprintf("%s:%d", bind, port)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	client := tlsClient(t, dbPath)
	for i := 0; i < 3; i++ {
		resp, err := client.Get(fmt.Sprintf("https://%s/health", addr))
		if err != nil {
			t.Fatalf("https GET: %v", err)
		}
		_ = resp.Body.Close()
	}

	var st map[string]any
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status JSON: %v", err)
	}
	if rc := int(st["requestCount"].(float64)); rc < 3 {
		t.Fatalf("requestCount = %d, want >= 3 (real traffic counted)", rc)
	}
}

func TestMain(m *testing.M) {
	code := m.Run()
	_ = Stop()
	os.Exit(code)
}
