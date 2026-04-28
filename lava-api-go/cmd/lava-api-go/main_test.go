package main

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// stubCache satisfies handlers.Cache without any persistence. The
// router-construction tests never invoke a route handler, so the
// stub's methods only need to compile.
type stubCache struct{}

func (stubCache) Get(_ context.Context, _ string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}
func (stubCache) Set(_ context.Context, _ string, _ []byte, _ time.Duration) error { return nil }
func (stubCache) Invalidate(_ context.Context, _ string) error                     { return nil }

// stubScraper satisfies handlers.ScraperClient. Same rationale as
// stubCache: never actually invoked from these tests.
type stubScraper struct{}

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
func (stubScraper) CheckAuthorised(context.Context, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) Login(context.Context, rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) FetchCaptcha(context.Context, string) (*rutracker.CaptchaImage, error) {
	return nil, errors.New("stub")
}

// TestMain_BuildsAndPrintsHelp is the Sixth-Law-compliant smoke test: it
// builds the binary in a temp dir, runs `--help`, and asserts the output
// contains the documented flag descriptions. Primary assertion is on the
// user-visible stdout (clause 3) — a wrong help text or a build failure
// fails this test loudly.
func TestMain_BuildsAndPrintsHelp(t *testing.T) {
	tmp := t.TempDir()
	bin := filepath.Join(tmp, "lava-api-go")

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	build := exec.CommandContext(ctx, "go", "build", "-o", bin, ".")
	build.Stderr = &bytes.Buffer{}
	if err := build.Run(); err != nil {
		t.Fatalf("go build: %v\nstderr: %s", err, build.Stderr.(*bytes.Buffer).String())
	}

	out, err := exec.CommandContext(ctx, bin, "--help").CombinedOutput()
	if err != nil {
		t.Fatalf("--help: %v\noutput: %s", err, out)
	}
	got := string(out)
	for _, want := range []string{
		"lava-api-go",
		"--help",
		"LAVA_API_PG_URL",
		"LAVA_API_LISTEN",
		"LAVA_API_TLS_CERT",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("--help output missing %q\nfull output:\n%s", want, got)
		}
	}
}

// TestMain_BuildRouterRegistersAllRoutes is the Sixth-Law-compliant
// falsifiability target for Phase 9. The 13 rutracker routes registered
// by handlers.Register PLUS /health PLUS /ready means the engine MUST
// expose at least 15 routes. If a future refactor of buildRouter (or
// of handlers.Register) drops the registration call, this test fails
// with a concrete count.
//
// Falsifiability rehearsal (clause 2) used this test as the load-bearing
// signal — see commit body.
func TestMain_BuildRouterRegistersAllRoutes(t *testing.T) {
	metrics := observability.NewMetrics(prometheus.NewRegistry())

	router := buildRouter(routerDeps{
		Cache:     stubCache{},
		Scraper:   stubScraper{},
		Metrics:   metrics,
		Readiness: nil,
	})

	const want = 15
	got := len(router.Routes())
	if got < want {
		t.Fatalf("buildRouter registered %d routes; want >= %d (13 handler routes + /health + /ready)", got, want)
	}

	// Sanity-check a couple of well-known routes are present so a
	// future regression that registers many routes but not the right
	// ones is also caught.
	mustHave := map[string]string{
		"GET":  "/forum",
		"POST": "/login",
	}
	have := map[string]map[string]bool{}
	for _, r := range router.Routes() {
		if have[r.Method] == nil {
			have[r.Method] = map[string]bool{}
		}
		have[r.Method][r.Path] = true
	}
	for method, path := range mustHave {
		if !have[method][path] {
			t.Errorf("route %s %s is missing from buildRouter output", method, path)
		}
	}
}
