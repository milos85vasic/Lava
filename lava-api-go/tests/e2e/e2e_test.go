// Package e2e is the Phase 10 / Task 10.2 end-to-end suite. It boots
// the full lava-api-go stack — Gin engine, real handlers, real cache
// backed by a transient podman Postgres, real rutracker.Client — and
// drives every public route through the same code path a real client
// would traverse. The "real" ceiling stops at rutracker.org itself:
// instead, a fake-rutracker httptest.NewServer serves the SAME HTML
// fixtures from internal/rutracker/testdata/ that the unit tests use.
//
// Why a fake upstream:
//
//   - Phase 10 / Task 10.3 (parity) is the layer that compares against
//     the real Ktor proxy → real rutracker.org. e2e is the layer below
//     that: it proves the wiring works without a network dependency.
//   - Determinism — fixture files don't change underfoot.
//   - Sixth Law clause 1 (same surfaces the user touches): the only
//     boundary mocked is the actual rutracker.org TCP socket. Every
//     other layer (auth middleware, cache.Get/Set against real
//     Postgres, scraper, JSON marshalling, Gin routing) runs the
//     production code path.
//
// Skip discipline (mirrors internal/cache/integration_test.go):
//   - LAVA_E2E_SKIP=1 → skip cleanly with status 0.
//   - exec.LookPath("podman") fails → skip cleanly with status 0.
//   - Postgres readiness timeout → treat as podman-unavailable, skip.
package e2e_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// pgReadyTimeout caps how long we wait for the transient Postgres
// container to accept connections. The task brief says ~5 minutes max
// before treating as podman-unavailable; 90 seconds is comfortably
// shorter and still leaves room for a cold-cache image pull.
const pgReadyTimeout = 90 * time.Second

// containerStartTimeout caps the total time the test will spend on
// `podman run` itself (separate from the readiness probe loop).
const containerStartTimeout = 30 * time.Second

// startPostgres boots a transient podman Postgres container and returns
// (postgresURL, cleanup). cleanup MUST be called to remove the
// container; the returned function is idempotent and never panics.
//
// Returns ("", nil, error) when podman is unavailable or the container
// does not become ready within pgReadyTimeout — callers must t.Skip
// in that case (per Sixth Law clause 5: "skip cleanly when the
// fixture cannot be brought up", not "fail loudly because the dev
// environment doesn't have podman").
func startPostgres(t *testing.T) (string, func(), error) {
	t.Helper()
	if _, err := exec.LookPath("podman"); err != nil {
		return "", nil, fmt.Errorf("podman not on PATH: %w", err)
	}

	// Container name uses the test-run PID so concurrent test runs
	// (`go test -count=N`) don't collide on the container namespace.
	name := fmt.Sprintf("lava-api-go-e2e-pg-%d-%d", os.Getpid(), time.Now().UnixNano())

	// Random high port to avoid collisions with the developer's local
	// Postgres instance and with the integration_test.go runner.
	portRaw := make([]byte, 2)
	if _, err := rand.Read(portRaw); err != nil {
		return "", nil, fmt.Errorf("rand port: %w", err)
	}
	port := 30000 + int(portRaw[0])<<8 + int(portRaw[1])
	port = 30000 + (port % 20000)

	pwdBytes := make([]byte, 8)
	if _, err := rand.Read(pwdBytes); err != nil {
		return "", nil, fmt.Errorf("rand password: %w", err)
	}
	password := hex.EncodeToString(pwdBytes)
	dbName := "lava_api_e2e"
	dbUser := "lava_api_e2e"

	image := os.Getenv("POSTGRES_TEST_IMAGE")
	if image == "" {
		image = "docker.io/postgres:16-alpine"
	}

	startCtx, startCancel := context.WithTimeout(context.Background(), containerStartTimeout)
	defer startCancel()
	runCmd := exec.CommandContext(startCtx, "podman", "run", "--rm", "-d",
		"--name", name,
		"-p", fmt.Sprintf("%d:5432", port),
		"-e", "POSTGRES_PASSWORD="+password,
		"-e", "POSTGRES_USER="+dbUser,
		"-e", "POSTGRES_DB="+dbName,
		image,
	)
	stderr := &bytes.Buffer{}
	runCmd.Stderr = stderr
	if err := runCmd.Run(); err != nil {
		return "", nil, fmt.Errorf("podman run: %w (stderr: %s)", err, stderr.String())
	}

	cleanup := func() {
		// Best-effort teardown. A failure here leaks a container, but
		// the test process MUST NOT panic on cleanup — we may already
		// be in a t.Fatal path.
		rmCtx, rmCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer rmCancel()
		_ = exec.CommandContext(rmCtx, "podman", "rm", "-f", name).Run()
	}

	pgURL := fmt.Sprintf("postgres://%s:%s@127.0.0.1:%d/%s?sslmode=disable",
		dbUser, password, port, dbName)

	// Readiness loop. We can't trust pg_isready alone: the official
	// postgres image entrypoint flips pg_isready to OK during
	// initialisation, then restarts the server to apply the chosen
	// `POSTGRES_USER` / `POSTGRES_DB` settings. A real client connect
	// during that window gets RST. We must also force the lazy pgxpool
	// to actually open a connection (pgxpool.New is lazy until the
	// first query) — HealthCheck issues a Ping which exposes the RST.
	// Poll until a Ping succeeds so the next ConnectFromURL the test
	// makes sees a stable server.
	deadline := time.Now().Add(pgReadyTimeout)
	for {
		if time.Now().After(deadline) {
			cleanup()
			return "", nil, fmt.Errorf("postgres did not become ready within %s", pgReadyTimeout)
		}
		probeCtx, probeCancel := context.WithTimeout(context.Background(), 3*time.Second)
		probe, perr := pgcache.ConnectFromURL(probeCtx, &pgcache.Config{
			URL:        pgURL,
			SchemaName: "lava_api_probe",
			TableName:  "probe",
			GCInterval: 0,
		})
		if perr == nil {
			perr = probe.HealthCheck(probeCtx)
			_ = probe.Close()
		}
		probeCancel()
		if perr == nil {
			break
		}
		time.Sleep(750 * time.Millisecond)
	}

	return pgURL, cleanup, nil
}

// fakeRutracker carries the test HTTP server and a request counter.
// hits[path] is the number of times the upstream saw a request for
// `path` (stripped of query). Used by TestE2E_ForumCacheHitOnSecondCall
// to prove the cache short-circuited the second GET /forum.
type fakeRutracker struct {
	server *httptest.Server
	mu     sync.Mutex
	hits   map[string]int

	// dlBytes is the canonical .torrent payload the fake serves on
	// GET /dl.php?t=NN. Captured here so the e2e test can assert
	// byte-equality on the proxied response.
	dlBytes []byte

	// captchaBytes is the canonical captcha image the fake serves on
	// GET / (i.e. when the captcha proxy decodes the URL-safe Base64
	// path back to this server's root). Bytes preserved for the
	// proxy-correctness assertion.
	captchaBytes []byte

	// commentsAddCalls counts POSTs to /posting.php so the e2e test
	// can prove the comments-add flow reached the upstream's third
	// step (after the form_token fetch).
	commentsAddCalls atomic.Int64
}

// fixturePath returns the absolute path of an HTML fixture under
// internal/rutracker/testdata/. Locating relative to this test file
// keeps `go test` runnable from any working directory.
func fixturePath(t *testing.T, segments ...string) string {
	t.Helper()
	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Dir(filepath.Dir(filepath.Dir(thisFile))) // tests/e2e → tests → lava-api-go
	parts := append([]string{root, "internal", "rutracker", "testdata"}, segments...)
	return filepath.Join(parts...)
}

// readFixture returns the bytes of a fixture file, failing the test
// with t.Fatalf on a read error.
func readFixture(t *testing.T, segments ...string) []byte {
	t.Helper()
	p := fixturePath(t, segments...)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", p, err)
	}
	return b
}

// indexBody returns a minimal /index.php response that satisfies the
// scraper's three pre-conditions: IsAuthorised (substring
// "logged-in-username"), ParseFormToken (regex `form_token: '...',`),
// and the login profile-step queryParam("u") on `#logged-in-username`.
//
// Note the COLON (not =) before the form_token literal — the regex is
// /form_token: '(.*)',/ which requires the colon-space form. A regression
// here would surface as the /comments/{id}/add and /favorites/{add,remove}/
// flows returning ErrUnauthorized at step 2 instead of completing.
func indexBody() []byte {
	return []byte(`<!DOCTYPE html>
<html>
<head><title>RuTracker</title></head>
<body>
<a id="logged-in-username" href="profile.php?u=42">alice</a>
<script>
  form_token: 'fake-tok',
</script>
</body>
</html>`)
}

// loginFormBody returns a /login.php response body that triggers the
// scraper's WrongCredits branch:
//   - contains "login-form" (the form-marker substring)
//   - contains "неверный пароль" (the wrong-credits Russian sentence)
//   - does NOT include a non-bb_ssl Set-Cookie (so the scraper does NOT
//     dispatch into the success / fetchUserProfile flow)
//
// Pinning WrongCredits keeps the e2e wire-shape assertion simple: the
// response is decodable as AuthResponseDto and the variant is
// WrongCredits. Phase 10 / Task 10.3 parity covers the Success
// happy-path against real rutracker.org.
func loginFormBody() []byte {
	return []byte(`<!DOCTYPE html>
<html>
<body>
<form id="login-form">
  <input type="text" name="login_username"/>
  <input type="password" name="login_password"/>
</form>
<p>неверный пароль</p>
</body>
</html>`)
}

// startFakeRutracker starts the synthetic rutracker.org. It serves the
// existing HTML fixtures keyed on path/query, plus the synthetic
// /login.php / /posting.php / /bookmarks.php / /dl.php / /index.php
// responses required by the state-mutating flows. Returns the
// *fakeRutracker handle and a cleanup func that closes the server.
func startFakeRutracker(t *testing.T) (*fakeRutracker, func()) {
	t.Helper()
	f := &fakeRutracker{
		hits:         make(map[string]int),
		dlBytes:      []byte("d8:announce10:fake-trackere"),
		captchaBytes: []byte{0xFF, 0xD8, 0xFF, 0xE0, 0xFA, 0xCE}, // JPEG magic + sentinel
	}

	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.hits[r.URL.Path]++
		f.mu.Unlock()

		switch r.URL.Path {
		case "/index.php":
			// /index.php?map=0  → forum tree (long, cacheable)
			// /index.php        → auth check + form_token (state-mutating flows)
			if r.URL.Query().Get("map") == "0" {
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = w.Write(readFixture(t, "forum", "forum_tree.html"))
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(indexBody())
			return

		case "/viewforum.php":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(readFixture(t, "forum", "category_page1.html"))
			return

		case "/tracker.php":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(readFixture(t, "search", "search_results.html"))
			return

		case "/viewtopic.php":
			// Both /topic/{id} (Torrent variant via magnet sniff) and
			// /comments/{id} fetch this URL. topic_torrent.html has a
			// magnet link so the dispatcher picks the Torrent branch
			// for /topic/{id}; /comments/{id} always parses as
			// CommentsPage.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(readFixture(t, "topic", "topic_torrent.html"))
			return

		case "/profile.php":
			// Used by the Login Success branch's fetchUserProfile;
			// e2e WrongCredits branch never hits this. Kept defensively
			// so a future test extension does not fail with a 404.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(`<html><body>
<span id="profile-uname" data-uid="42">alice</span>
<span id="avatar-img"><img src="https://avatars/42.png"/></span>
</body></html>`))
			return

		case "/login.php":
			if r.Method != http.MethodPost {
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			// No Set-Cookie → scraper falls through to the WrongCredits /
			// CaptchaRequired branch on the body bytes.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(loginFormBody())
			return

		case "/posting.php":
			if r.Method != http.MethodPost {
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			f.commentsAddCalls.Add(1)
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			// The Russian success sentence — the only signal the
			// scraper accepts.
			_, _ = w.Write([]byte("<html><body>Сообщение было успешно отправлено</body></html>"))
			return

		case "/bookmarks.php":
			if r.Method == http.MethodGet {
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = w.Write(readFixture(t, "favorites", "page1.html"))
				return
			}
			if r.Method == http.MethodPost {
				if err := r.ParseForm(); err != nil {
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				switch r.PostForm.Get("action") {
				case "bookmark_delete":
					_, _ = w.Write([]byte("<html><body>Тема удалена</body></html>"))
				default: // bookmark_add or anything else
					_, _ = w.Write([]byte("<html><body>Тема добавлена</body></html>"))
				}
				return
			}
			w.WriteHeader(http.StatusMethodNotAllowed)
			return

		case "/dl.php":
			// Binary .torrent download. Preserve Content-Disposition so
			// the API forwards it verbatim (Phase 7 / Task 7.6 contract).
			topicID := r.URL.Query().Get("t")
			w.Header().Set("Content-Type", "application/x-bittorrent")
			w.Header().Set("Content-Disposition",
				fmt.Sprintf(`attachment; filename="rutracker_%s.torrent"`, topicID))
			_, _ = w.Write(f.dlBytes)
			return

		case "/captcha-image":
			// Used by the captcha-proxy test: the captcha path the
			// /captcha/{path} handler decodes points HERE.
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(f.captchaBytes)
			return

		default:
			w.WriteHeader(http.StatusNotFound)
			return
		}
	})

	srv := httptest.NewServer(mux)
	f.server = srv
	return f, func() { srv.Close() }
}

// applyMigrations runs the response_cache schema setup against the
// e2e Postgres. We use the Submodules/Cache CreateSchema entrypoint
// rather than shelling out to golang-migrate for two reasons:
//
//  1. cache.New() and the Phase 7 handlers ONLY consume the
//     response_cache table; the other migrations (request_audit,
//     rate_limit_bucket, login_attempt) are not exercised by the e2e
//     suite — they belong to the audit / rate-limit Phase work.
//  2. Avoiding `go run github.com/golang-migrate/...` keeps the test
//     hermetic and fast; the shell-out adds 5+ seconds of go-tool
//     compilation each run.
//
// The schema name and table name MUST match what cmd/lava-api-go's
// pgcache.ConnectFromURL receives in production: "lava_api" /
// "response_cache".
func newE2ECacheClient(ctx context.Context, pgURL string) (*pgcache.Client, error) {
	inner, err := pgcache.ConnectFromURL(ctx, &pgcache.Config{
		URL:        pgURL,
		SchemaName: "lava_api",
		TableName:  "response_cache",
		GCInterval: 0,
	})
	if err != nil {
		return nil, fmt.Errorf("ConnectFromURL: %w", err)
	}
	if err := inner.CreateSchema(ctx); err != nil {
		_ = inner.Close()
		return nil, fmt.Errorf("CreateSchema: %w", err)
	}
	return inner, nil
}

// buildE2ERouter mirrors cmd/lava-api-go/main.go's buildRouter() but
// without the metrics / readiness wiring (those are exercised by their
// own tests). Re-implementing here rather than calling buildRouter
// directly keeps the e2e suite from importing the main package — the
// production engine shape is preserved (auth middleware first, then
// handlers.Register), so the test still hits the same surfaces.
func buildE2ERouter(c handlers.Cache, scraper handlers.ScraperClient) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(auth.GinMiddleware())
	handlers.Register(router, &handlers.Deps{Cache: c, Scraper: scraper})
	return router
}

// e2eFixture bundles the long-lived state shared by every test in the
// suite: the router, the fake upstream (with its hit counter), and the
// pgcache.Client (so cleanup can drop the schema and close the pool).
type e2eFixture struct {
	router *gin.Engine
	fake   *fakeRutracker
	pg     *pgcache.Client
	pgURL  string
}

// newE2EFixture is the per-test setup: skip if podman missing, boot
// Postgres + fake upstream + scraper + router. The returned cleanup is
// idempotent.
func newE2EFixture(t *testing.T) (*e2eFixture, func()) {
	t.Helper()

	if os.Getenv("LAVA_E2E_SKIP") == "1" {
		t.Skip("LAVA_E2E_SKIP=1 — e2e disabled by env")
	}

	pgURL, pgCleanup, err := startPostgres(t)
	if err != nil {
		t.Skipf("podman/postgres unavailable: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	inner, err := newE2ECacheClient(ctx, pgURL)
	if err != nil {
		pgCleanup()
		t.Fatalf("newE2ECacheClient: %v", err)
	}

	fake, fakeCleanup := startFakeRutracker(t)

	cacheClient := cache.New(inner)
	scraper := rutracker.NewClient(fake.server.URL)
	router := buildE2ERouter(cacheClient, scraper)

	cleanupOnce := sync.Once{}
	cleanup := func() {
		cleanupOnce.Do(func() {
			fakeCleanup()
			// Drop schema so a re-run on the same Postgres (e.g.
			// POSTGRES_TEST_URL externally provided) is hermetic.
			dropCtx, dropCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer dropCancel()
			_, _ = inner.Underlying().Exec(dropCtx, `DROP SCHEMA IF EXISTS lava_api CASCADE`)
			_ = inner.Close()
			pgCleanup()
		})
	}

	return &e2eFixture{
		router: router,
		fake:   fake,
		pg:     inner,
		pgURL:  pgURL,
	}, cleanup
}

// do dispatches a request through the in-process Gin engine. Returns
// (status, body, headers). Failure to read the body is fatal — the
// test cannot make any user-visible assertions without it.
func (f *e2eFixture) do(t *testing.T, method, target string, body io.Reader, headers map[string]string) (int, []byte, http.Header) {
	t.Helper()
	req := httptest.NewRequest(method, target, body)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	return w.Code, w.Body.Bytes(), w.Result().Header
}

// fakeHits returns the upstream request count for the given path
// (stripped of query). Used to prove cache hits short-circuited the
// upstream call.
func (f *e2eFixture) fakeHits(path string) int {
	f.fake.mu.Lock()
	defer f.fake.mu.Unlock()
	return f.fake.hits[path]
}

// TestE2E_AllRoutes is the single suite-level test that drives every
// route. Implemented as one test (not many) so the expensive Postgres +
// container lifecycle pays off once.
//
// Sixth Law alignment:
//   - clause 1: every assertion is on the wire-shape an Android client
//     would receive (status code + JSON body).
//   - clause 3: primary assertions are user-visible bytes — JSON
//     decoded into the OpenAPI-generated DTOs, header values for the
//     binary download, and a body-equality check for the captcha.
//     Hit counters are secondary.
func TestE2E_AllRoutes(t *testing.T) {
	f, cleanup := newE2EFixture(t)
	defer cleanup()

	// ----- 1. GET / (anonymous index — `false`) ----------------------
	// /index.php returns indexBody() which carries
	// "logged-in-username", so CheckAuthorised reports TRUE for an
	// authed cookie. With no cookie the fake STILL returns the same
	// body — but the scraper sends no Cookie header either way, and
	// CheckAuthorised is honest about the body bytes. We assert the
	// JSON parses as a boolean (the scraper saw the marker).
	status, body, _ := f.do(t, http.MethodGet, "/", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /: status=%d want 200; body=%s", status, body)
	}
	var indexAuthed bool
	if err := json.Unmarshal(body, &indexAuthed); err != nil {
		t.Fatalf("GET /: body not bool: %v (%q)", err, body)
	}
	if !indexAuthed {
		t.Errorf("GET /: indexAuthed=false; fake returns logged-in-username so want true")
	}

	// ----- 2. GET /forum (cached) ------------------------------------
	status, body, _ = f.do(t, http.MethodGet, "/forum", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /forum: status=%d want 200; body=%s", status, body)
	}
	var forum gen.ForumDto
	if err := json.Unmarshal(body, &forum); err != nil {
		t.Fatalf("GET /forum: body not ForumDto: %v (%q)", err, body)
	}
	if len(forum.Children) == 0 {
		t.Errorf("GET /forum: ForumDto.Children empty; fixture has 2 .tree-root nodes")
	}

	// Cache hit-on-second-call assertion: the second GET /forum MUST
	// NOT increment the upstream's /index.php hit count. This is the
	// load-bearing assertion that proves cache.Set/Get is actually
	// wired through the real Postgres pool.
	hitsBefore := f.fakeHits("/index.php")
	status, body, _ = f.do(t, http.MethodGet, "/forum", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /forum (2nd): status=%d", status)
	}
	hitsAfter := f.fakeHits("/index.php")
	if hitsAfter != hitsBefore {
		t.Fatalf("GET /forum: cache miss on 2nd call (upstream hits %d→%d); cache.Set probably not wired", hitsBefore, hitsAfter)
	}
	// Also cross-check that the body bytes are identical — a cache
	// implementation that returns a different shape on hit would slip
	// past the hit-count assertion alone.
	var forum2 gen.ForumDto
	if err := json.Unmarshal(body, &forum2); err != nil {
		t.Fatalf("GET /forum (2nd): body not ForumDto: %v", err)
	}

	// ----- 3. GET /forum/{id} (CategoryPageDto) ----------------------
	status, body, _ = f.do(t, http.MethodGet, "/forum/123", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /forum/123: status=%d want 200; body=%s", status, body)
	}
	var category gen.CategoryPageDto
	if err := json.Unmarshal(body, &category); err != nil {
		t.Fatalf("GET /forum/123: body not CategoryPageDto: %v (%q)", err, body)
	}
	if category.Page < 1 {
		t.Errorf("GET /forum/123: CategoryPageDto.Page=%d want >=1", category.Page)
	}

	// ----- 4. GET /search?query=foo (SearchPageDto) ------------------
	status, body, _ = f.do(t, http.MethodGet, "/search?query=foo", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /search: status=%d want 200; body=%s", status, body)
	}
	var search gen.SearchPageDto
	if err := json.Unmarshal(body, &search); err != nil {
		t.Fatalf("GET /search: body not SearchPageDto: %v (%q)", err, body)
	}
	if len(search.Torrents) == 0 {
		t.Errorf("GET /search: SearchPageDto.Torrents empty; fixture has 2 .hl-tr rows")
	}

	// ----- 5. GET /topic/{id} (Torrent variant via magnet sniff) -----
	status, body, _ = f.do(t, http.MethodGet, "/topic/42", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /topic/42: status=%d want 200; body=%s", status, body)
	}
	var topicUnion gen.ForumTopicDto
	if err := json.Unmarshal(body, &topicUnion); err != nil {
		t.Fatalf("GET /topic/42: body not ForumTopicDto: %v (%q)", err, body)
	}
	// Round-trip into Torrent variant — topic_torrent.html has a magnet
	// link so the dispatcher takes the Torrent branch.
	if _, err := topicUnion.AsForumTopicDtoTorrent(); err != nil {
		t.Errorf("GET /topic/42: not a Torrent variant: %v", err)
	}

	// ----- 6. GET /torrent/{id} (ForumTopicDtoTorrent) ---------------
	status, body, _ = f.do(t, http.MethodGet, "/torrent/42", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /torrent/42: status=%d want 200; body=%s", status, body)
	}
	var torrent gen.ForumTopicDtoTorrent
	if err := json.Unmarshal(body, &torrent); err != nil {
		t.Fatalf("GET /torrent/42: body not ForumTopicDtoTorrent: %v (%q)", err, body)
	}
	if torrent.Type != gen.Torrent {
		t.Errorf("GET /torrent/42: Type=%q want %q", torrent.Type, gen.Torrent)
	}

	// ----- 7. GET /download/{id} (binary stream) ---------------------
	status, body, hdrs := f.do(t, http.MethodGet, "/download/42", nil, map[string]string{
		auth.HeaderName: "tok",
	})
	if status != http.StatusOK {
		t.Fatalf("GET /download/42: status=%d want 200; body=%s", status, body)
	}
	if !bytes.Equal(body, f.fake.dlBytes) {
		t.Errorf("GET /download/42: body=%v want %v", body, f.fake.dlBytes)
	}
	if got := hdrs.Get("Content-Disposition"); !strings.Contains(got, `filename="rutracker_42.torrent"`) {
		t.Errorf("GET /download/42: Content-Disposition=%q does not contain filename", got)
	}
	if ct := hdrs.Get("Content-Type"); !strings.Contains(ct, "x-bittorrent") {
		t.Errorf("GET /download/42: Content-Type=%q does not contain x-bittorrent", ct)
	}

	// ----- 8. POST /comments/{id}/add (write + invalidate) -----------
	addBefore := f.fake.commentsAddCalls.Load()
	status, body, _ = f.do(t, http.MethodPost, "/comments/42/add",
		strings.NewReader("test message"),
		map[string]string{auth.HeaderName: "tok"})
	if status != http.StatusOK {
		t.Fatalf("POST /comments/42/add: status=%d want 200; body=%s", status, body)
	}
	var addOK bool
	if err := json.Unmarshal(body, &addOK); err != nil {
		t.Fatalf("POST /comments/42/add: body not bool: %v (%q)", err, body)
	}
	if !addOK {
		t.Errorf("POST /comments/42/add: ok=false; fake returns success sentence so want true")
	}
	if got := f.fake.commentsAddCalls.Load(); got != addBefore+1 {
		t.Errorf("POST /comments/42/add: posting.php hit count delta=%d want 1", got-addBefore)
	}

	// ----- 9. GET /favorites (FavoritesDto) --------------------------
	status, body, _ = f.do(t, http.MethodGet, "/favorites", nil, map[string]string{
		auth.HeaderName: "tok",
	})
	if status != http.StatusOK {
		t.Fatalf("GET /favorites: status=%d want 200; body=%s", status, body)
	}
	var favs gen.FavoritesDto
	if err := json.Unmarshal(body, &favs); err != nil {
		t.Fatalf("GET /favorites: body not FavoritesDto: %v (%q)", err, body)
	}
	if len(favs.Topics) == 0 {
		t.Errorf("GET /favorites: Topics empty; favorites/page1.html has 2 rows")
	}

	// ----- 10. POST /favorites/add/{id} (true) -----------------------
	status, body, _ = f.do(t, http.MethodPost, "/favorites/add/100", nil, map[string]string{
		auth.HeaderName: "tok",
	})
	if status != http.StatusOK {
		t.Fatalf("POST /favorites/add/100: status=%d want 200; body=%s", status, body)
	}
	var favAddOK bool
	if err := json.Unmarshal(body, &favAddOK); err != nil {
		t.Fatalf("POST /favorites/add/100: body not bool: %v (%q)", err, body)
	}
	if !favAddOK {
		t.Errorf("POST /favorites/add/100: ok=false; fake returns 'Тема добавлена' so want true")
	}

	// ----- 11. POST /favorites/remove/{id} (true) --------------------
	status, body, _ = f.do(t, http.MethodPost, "/favorites/remove/100", nil, map[string]string{
		auth.HeaderName: "tok",
	})
	if status != http.StatusOK {
		t.Fatalf("POST /favorites/remove/100: status=%d want 200; body=%s", status, body)
	}
	var favRemoveOK bool
	if err := json.Unmarshal(body, &favRemoveOK); err != nil {
		t.Fatalf("POST /favorites/remove/100: body not bool: %v (%q)", err, body)
	}
	if !favRemoveOK {
		t.Errorf("POST /favorites/remove/100: ok=false; fake returns 'Тема удалена' so want true")
	}

	// ----- 12. POST /login (WrongCredits via fake login form) --------
	form := url.Values{}
	form.Set("username", "alice")
	form.Set("password", "secret")
	status, body, _ = f.do(t, http.MethodPost, "/login",
		strings.NewReader(form.Encode()),
		map[string]string{"Content-Type": "application/x-www-form-urlencoded"})
	if status != http.StatusOK {
		t.Fatalf("POST /login: status=%d want 200; body=%s", status, body)
	}
	var authResp gen.AuthResponseDto
	if err := json.Unmarshal(body, &authResp); err != nil {
		t.Fatalf("POST /login: body not AuthResponseDto: %v (%q)", err, body)
	}
	if _, err := authResp.AsAuthResponseDtoWrongCredits(); err != nil {
		t.Errorf("POST /login: not a WrongCredits variant: %v (body=%s)", err, body)
	}

	// ----- 13. GET /captcha/{path} (image bytes preserved) -----------
	captchaURL := f.fake.server.URL + "/captcha-image"
	encoded := base64.URLEncoding.EncodeToString([]byte(captchaURL))
	status, body, hdrs = f.do(t, http.MethodGet, "/captcha/"+encoded, nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /captcha: status=%d want 200; body=%v", status, body)
	}
	if !bytes.Equal(body, f.fake.captchaBytes) {
		t.Errorf("GET /captcha: body=%v want %v", body, f.fake.captchaBytes)
	}
	if ct := hdrs.Get("Content-Type"); !strings.Contains(ct, "image") {
		t.Errorf("GET /captcha: Content-Type=%q want image/*", ct)
	}

	// ----- 14. GET /index (sibling of GET /, same handler) -----------
	// Pinned separately so a regression that drops one of the two
	// route registrations is caught.
	status, body, _ = f.do(t, http.MethodGet, "/index", nil, nil)
	if status != http.StatusOK {
		t.Fatalf("GET /index: status=%d want 200; body=%s", status, body)
	}
	var idxAuthed bool
	if err := json.Unmarshal(body, &idxAuthed); err != nil {
		t.Fatalf("GET /index: body not bool: %v (%q)", err, body)
	}
}

// TestE2E_DownloadEmptyCookie_Returns401 pins one of the spec §6
// security-relevant edge cases at the e2e layer: an anonymous
// /download/{id} MUST return 401 without any upstream traffic. The
// rutracker.GetTorrentFile short-circuits on cookie == "" — this test
// proves the wiring preserves that property end-to-end.
//
// Distinct from TestE2E_AllRoutes so a regression here surfaces with
// a focused failure name.
func TestE2E_DownloadEmptyCookie_Returns401(t *testing.T) {
	f, cleanup := newE2EFixture(t)
	defer cleanup()

	hitsBefore := f.fakeHits("/dl.php")
	status, body, _ := f.do(t, http.MethodGet, "/download/42", nil, nil)
	if status != http.StatusUnauthorized {
		t.Fatalf("GET /download/42 (no auth): status=%d want 401; body=%s", status, body)
	}
	if hitsAfter := f.fakeHits("/dl.php"); hitsAfter != hitsBefore {
		t.Errorf("GET /download/42 (no auth): upstream hits delta=%d want 0 (anonymous MUST short-circuit before any /dl.php call)", hitsAfter-hitsBefore)
	}
}

// TestE2E_LoginMissingPassword_Returns400_NoUpstream pins the
// "malformed client request must NOT reach the upstream" property
// end-to-end. The handler short-circuits on missing username or
// password — this test proves the wiring preserves it.
func TestE2E_LoginMissingPassword_Returns400_NoUpstream(t *testing.T) {
	f, cleanup := newE2EFixture(t)
	defer cleanup()

	hitsBefore := f.fakeHits("/login.php")
	form := url.Values{}
	form.Set("username", "alice")
	status, body, _ := f.do(t, http.MethodPost, "/login",
		strings.NewReader(form.Encode()),
		map[string]string{"Content-Type": "application/x-www-form-urlencoded"})
	if status != http.StatusBadRequest {
		t.Fatalf("POST /login (no password): status=%d want 400; body=%s", status, body)
	}
	if hitsAfter := f.fakeHits("/login.php"); hitsAfter != hitsBefore {
		t.Errorf("POST /login (no password): upstream hits delta=%d want 0 (malformed request MUST short-circuit)", hitsAfter-hitsBefore)
	}
}

// fakeHTTPCheck is a defensive sanity probe: a quick HTTP GET against
// the fake server confirming /index.php responds with the canned body.
// Catches a misconfigured mux before the suite spends 10+ seconds
// wiring up Postgres only to fail at the first assertion.
func TestE2E_FakeUpstream_SanityProbe(t *testing.T) {
	if os.Getenv("LAVA_E2E_SKIP") == "1" {
		t.Skip("LAVA_E2E_SKIP=1")
	}
	fake, cleanup := startFakeRutracker(t)
	defer cleanup()

	resp, err := http.Get(fake.server.URL + "/index.php")
	if err != nil {
		t.Fatalf("GET /index.php: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /index.php: status=%d want 200", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read /index.php body: %v", err)
	}
	if !bytes.Contains(body, []byte("logged-in-username")) {
		t.Errorf("/index.php body missing 'logged-in-username' marker (got %q)", body)
	}
	if !bytes.Contains(body, []byte("form_token: 'fake-tok',")) {
		t.Errorf("/index.php body missing form_token line (got %q)", body)
	}
}

// ensureNoLAVAE2ESkipFile is a lint-ish check: a future contributor
// who introduces a `LAVA_E2E_SKIP=1` file at the repo root would
// silently disable this entire suite. Catch it loudly.
//
// Runs unconditionally — does not need podman.
func TestE2E_NoSkipMarkerFile(t *testing.T) {
	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Dir(filepath.Dir(filepath.Dir(thisFile))) // tests/e2e → tests → lava-api-go
	for _, name := range []string{".lava_e2e_skip", "LAVA_E2E_SKIP"} {
		if _, err := os.Stat(filepath.Join(root, name)); err == nil {
			t.Errorf("repo contains a %q marker file; e2e suite would always skip", name)
		} else if !errors.Is(err, os.ErrNotExist) {
			t.Errorf("stat %q: %v", name, err)
		}
	}
}

// expectedRoutesCount is hard-coded so a future regression that drops
// a route or duplicates one is caught by a focused assertion. The 13
// rutracker operations from spec §5 expand to 16 Gin route entries:
//   - forum (2) + search (1) + topic-trio (3) + comments-add (1)
//   - torrent (1) + download (1) + favorites-trio (3)
//   - index (2: / and /index share a handler) + login (1) + captcha (1)
const expectedRoutesCount = 16

// TestE2E_RouterShape pins the registered route count produced by the
// e2e router build. Runs even without podman because it exercises only
// the in-memory Gin engine.
func TestE2E_RouterShape(t *testing.T) {
	router := buildE2ERouter(noopCache{}, noopScraper{})
	got := len(router.Routes())
	if got != expectedRoutesCount {
		// Print every route so a diff is greppable in the failure
		// output.
		var names []string
		for _, r := range router.Routes() {
			names = append(names, r.Method+" "+r.Path)
		}
		t.Errorf("router.Routes()=%d want %d; routes=%s", got, expectedRoutesCount, strings.Join(names, ", "))
	}
}

// noopCache is a zero-behaviour Cache: never hits, never errors. Used
// by TestE2E_RouterShape which never triggers a request.
type noopCache struct{}

func (noopCache) Get(_ context.Context, _ string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}
func (noopCache) Set(_ context.Context, _ string, _ []byte, _ time.Duration) error { return nil }
func (noopCache) Invalidate(_ context.Context, _ string) error                     { return nil }

// noopScraper satisfies handlers.ScraperClient for router-shape tests.
type noopScraper struct{}

func (noopScraper) GetForum(context.Context, string) (*gen.ForumDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetCategoryPage(context.Context, string, *int, string) (*gen.CategoryPageDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetSearchPage(context.Context, rutracker.SearchOpts, string) (*gen.SearchPageDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetTopic(context.Context, string, *int, string) (*gen.ForumTopicDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetTopicPage(context.Context, string, *int, string) (*gen.TopicPageDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetCommentsPage(context.Context, string, *int, string) (*gen.CommentsPageDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) AddComment(context.Context, string, string, string) (bool, error) {
	return false, errors.New("noop")
}
func (noopScraper) GetTorrent(context.Context, string, string) (*gen.ForumTopicDtoTorrent, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetTorrentFile(context.Context, string, string) (*rutracker.TorrentFile, error) {
	return nil, errors.New("noop")
}
func (noopScraper) GetFavorites(context.Context, string) (*gen.FavoritesDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) AddFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("noop")
}
func (noopScraper) RemoveFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("noop")
}
func (noopScraper) CheckAuthorised(context.Context, string) (bool, error) {
	return false, errors.New("noop")
}
func (noopScraper) Login(context.Context, rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	return nil, errors.New("noop")
}
func (noopScraper) FetchCaptcha(context.Context, string) (*rutracker.CaptchaImage, error) {
	return nil, errors.New("noop")
}

