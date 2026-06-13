//go:build stress

package stress

import (
	"context"
	"errors"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// router_stubs.go provides the Cache + ScraperClient route-resolution stubs the
// production router.Build requires. They mirror the in-tree
// internal/router/router_test.go stubs verbatim (that file's stubs are package-
// private to `package router`, so they cannot be imported here).
//
// The /providers + /health + /ready paths under test in
// providers_router_stress_test.go NEVER touch these — they are catalogue /
// liveness / readiness routes registered before the rutracker handlers. The stubs
// exist only so router.Build's legacy-handler registration has the interfaces it
// needs to assemble the engine. Every scraper method returns an error so any
// accidental legacy-route hit maps to a non-200 (it never happens in these tests).

type stressStubCache struct{}

func (stressStubCache) Get(_ context.Context, _ string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}
func (stressStubCache) Set(_ context.Context, _ string, _ []byte, _ time.Duration) error { return nil }
func (stressStubCache) Invalidate(_ context.Context, _ string) error                     { return nil }

type stressStubScraper struct{}

func (stressStubScraper) GetForum(context.Context, string) (*gen.ForumDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetCategoryPage(context.Context, string, *int, string) (*gen.CategoryPageDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetSearchPage(context.Context, rutracker.SearchOpts, string) (*gen.SearchPageDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetTopic(context.Context, string, *int, string) (*gen.ForumTopicDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetTopicPage(context.Context, string, *int, string) (*gen.TopicPageDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetCommentsPage(context.Context, string, *int, string) (*gen.CommentsPageDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) AddComment(context.Context, string, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stressStubScraper) GetTorrent(context.Context, string, string) (*gen.ForumTopicDtoTorrent, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetTorrentFile(context.Context, string, string) (*rutracker.TorrentFile, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) GetFavorites(context.Context, string) (*gen.FavoritesDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) AddFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stressStubScraper) RemoveFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stressStubScraper) CheckAuthorised(context.Context, string) (bool, error) {
	return false, errors.New("stub")
}
func (stressStubScraper) Login(context.Context, rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	return nil, errors.New("stub")
}
func (stressStubScraper) FetchCaptcha(context.Context, string) (*rutracker.CaptchaImage, error) {
	return nil, errors.New("stub")
}

// Compile-time assertions: if a future method is added to either interface, the
// build breaks here at the seam rather than at request time.
var (
	_ handlers.Cache         = stressStubCache{}
	_ handlers.ScraperClient = stressStubScraper{}
)
