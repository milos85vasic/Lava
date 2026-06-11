package provider_test

import (
	"context"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// minimalProvider is the smallest possible Provider: it embeds BaseProvider
// for the catalogue-metadata defaults and stubs the remaining methods. A real
// provider would never look like this; the point is to prove that embedding
// BaseProvider is sufficient to satisfy the extended interface AND yields the
// documented defaults (Kind=="native", SupportsAnonymous==false, BaseURLs==nil).
type minimalProvider struct {
	provider.BaseProvider
}

func (minimalProvider) ID() string                              { return "minimal" }
func (minimalProvider) DisplayName() string                     { return "Minimal" }
func (minimalProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch}
}
func (minimalProvider) AuthType() provider.AuthType { return provider.AuthNone }
func (minimalProvider) Encoding() string            { return "UTF-8" }
func (minimalProvider) Search(context.Context, provider.SearchOpts, provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{}, nil
}
func (minimalProvider) Browse(context.Context, string, int, provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) GetForumTree(context.Context, provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) GetTopic(context.Context, string, int, provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) GetTorrent(context.Context, string, provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) DownloadFile(context.Context, string, provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) GetComments(context.Context, string, int, provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) AddComment(context.Context, string, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (minimalProvider) GetFavorites(context.Context, provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) AddFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (minimalProvider) RemoveFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (minimalProvider) CheckAuth(context.Context, provider.Credentials) (bool, error) {
	return false, nil
}
func (minimalProvider) Login(context.Context, provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) FetchCaptcha(context.Context, string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (minimalProvider) HealthCheck(context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// TestProvider_CatalogueMetadataDefaults asserts that a provider which embeds
// BaseProvider and overrides nothing exposes the documented catalogue defaults.
// This is the contract the GET /v1/providers handler relies on for every
// native provider that has no mirror/anonymous info of its own.
func TestProvider_CatalogueMetadataDefaults(t *testing.T) {
	var p provider.Provider = minimalProvider{}

	if got := p.Kind(); got != "native" {
		t.Errorf("Kind() = %q, want %q", got, "native")
	}
	if got := p.SupportsAnonymous(); got != false {
		t.Errorf("SupportsAnonymous() = %v, want false", got)
	}
	if got := p.BaseURLs(); got != nil {
		t.Errorf("BaseURLs() = %v, want nil", got)
	}
}

// catalogueOverrider proves a provider can override the BaseProvider defaults
// (the native rutracker/archiveorg/etc. providers do exactly this) and that
// the overrides — not the embedded defaults — are what callers observe.
type catalogueOverrider struct {
	minimalProvider
}

func (catalogueOverrider) Kind() string            { return "jackett" }
func (catalogueOverrider) SupportsAnonymous() bool { return true }
func (catalogueOverrider) BaseURLs() []string      { return []string{"https://example.test"} }

func TestProvider_CatalogueMetadataOverrides(t *testing.T) {
	var p provider.Provider = catalogueOverrider{}

	if got := p.Kind(); got != "jackett" {
		t.Errorf("Kind() = %q, want %q", got, "jackett")
	}
	if !p.SupportsAnonymous() {
		t.Error("SupportsAnonymous() = false, want true")
	}
	if urls := p.BaseURLs(); len(urls) != 1 || urls[0] != "https://example.test" {
		t.Errorf("BaseURLs() = %v, want [https://example.test]", urls)
	}
}
