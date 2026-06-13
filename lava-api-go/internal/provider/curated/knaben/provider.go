package knaben

import (
	"context"

	"digital.vasic.lava.apigo/internal/provider"
)

var _ provider.Provider = (*ProviderAdapter)(nil)

// providerID is the canonical, stable id surfaced in GET /providers and used in
// the per-provider /v1/{id}/{op} routes.
const providerID = "knaben"

// canonicalSite is shown in the catalogue's baseUrls for informational display.
const canonicalSite = "https://knaben.org"

// ProviderAdapter wraps *Client to satisfy provider.Provider. It overrides the
// BaseProvider catalogue defaults that differ for a public, anonymous,
// magnet-only aggregator: SupportsAnonymous=true and BaseURLs=[canonicalSite]
// (Kind stays "native" — compiled-in, not a remote jackett indexer).
type ProviderAdapter struct {
	provider.BaseProvider
	client *Client
}

// NewProviderAdapter returns an adapter for the given client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// New is the convenience constructor used at registration: a production client
// against the default Knaben base.
func New() *ProviderAdapter { return NewProviderAdapter(NewClient(DefaultBaseURL)) }

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return providerID }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "Knaben" }

// AuthType returns NONE — Knaben search is anonymous.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthNone }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "UTF-8" }

// SupportsAnonymous reports true: search + magnet work without credentials.
func (a *ProviderAdapter) SupportsAnonymous() bool { return true }

// BaseURLs lists the canonical site for informational display in the catalogue.
func (a *ProviderAdapter) BaseURLs() []string { return []string{canonicalSite} }

// Capabilities: Knaben yields search rows with magnet links (no .torrent files),
// so this provider honestly declares SEARCH + MAGNET_LINK only (§6.E).
func (a *ProviderAdapter) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapMagnetLink,
	}
}

// Search delegates to Client.Search.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	return a.client.Search(ctx, opts.Query, opts.Page)
}

// Browse is unsupported (Knaben has no category-browse endpoint).
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, _ provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}

// GetForumTree is unsupported.
func (a *ProviderAdapter) GetForumTree(ctx context.Context, _ provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}

// GetTopic is unsupported (search rows already carry the magnet).
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, _ int, _ provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}

// GetTorrent is unsupported — Knaben serves magnet links, not .torrent files.
func (a *ProviderAdapter) GetTorrent(ctx context.Context, id string, _ provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}

// DownloadFile is unsupported (magnet-only).
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, _ provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}

// GetComments is unsupported.
func (a *ProviderAdapter) GetComments(ctx context.Context, id string, page int, _ provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}

// AddComment is unsupported.
func (a *ProviderAdapter) AddComment(ctx context.Context, id, message string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// GetFavorites is unsupported.
func (a *ProviderAdapter) GetFavorites(ctx context.Context, _ provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}

// AddFavorite is unsupported.
func (a *ProviderAdapter) AddFavorite(ctx context.Context, id string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// RemoveFavorite is unsupported.
func (a *ProviderAdapter) RemoveFavorite(ctx context.Context, id string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// CheckAuth always returns true — anonymous provider.
func (a *ProviderAdapter) CheckAuth(ctx context.Context, _ provider.Credentials) (bool, error) {
	return true, nil
}

// Login is a no-op success — there is no account to sign into.
func (a *ProviderAdapter) Login(ctx context.Context, _ provider.LoginOpts) (*provider.LoginResult, error) {
	return &provider.LoginResult{Success: true}, nil
}

// FetchCaptcha is unsupported.
func (a *ProviderAdapter) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// HealthCheck probes Knaben with a fixed query.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	if err := a.client.Health(ctx); err != nil {
		// no-telemetry: HealthCheck IS the telemetry surface — Healthy=false
		// propagates to /health + the readiness probe; recording a non-fatal
		// here would double-report every probe failure.
		return &provider.HealthStatus{Healthy: false}, nil
	}
	return &provider.HealthStatus{Healthy: true}, nil
}
