package gutenberg

import (
	"context"

	"digital.vasic.lava.apigo/internal/provider"
)

var _ provider.Provider = (*ProviderAdapter)(nil)

// ProviderAdapter wraps *Client to satisfy provider.Provider.
type ProviderAdapter struct {
	client *Client
}

// NewProviderAdapter returns an adapter for the given gutenberg client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return "gutenberg" }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "Project Gutenberg" }

// AuthType returns the authentication mechanism.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthNone }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "UTF-8" }

// Capabilities returns the capability set for Project Gutenberg.
func (a *ProviderAdapter) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapBrowse,
		provider.CapTopic,
		provider.CapHTTPDownload,
	}
}

// Search delegates to Client.Search.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	return a.client.Search(ctx, opts.Query, opts.Page)
}

// Browse delegates to Client.Browse.
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, _ provider.Credentials) (*provider.BrowseResult, error) {
	return a.client.Browse(ctx, categoryID, page)
}

// GetForumTree returns a static subject tree.
func (a *ProviderAdapter) GetForumTree(ctx context.Context, _ provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{
		Provider: "gutenberg",
		Categories: []provider.ForumCategory{
			{ID: "fiction", Name: "Fiction"},
			{ID: "science", Name: "Science"},
			{ID: "history", Name: "History"},
			{ID: "philosophy", Name: "Philosophy"},
			{ID: "poetry", Name: "Poetry"},
			{ID: "drama", Name: "Drama"},
		},
	}, nil
}

// GetTopic delegates to Client.GetBook.
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, _ int, _ provider.Credentials) (*provider.TopicResult, error) {
	return a.client.GetBook(ctx, id)
}

// GetTorrent is unsupported.
func (a *ProviderAdapter) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}

// DownloadFile delegates to Client.DownloadBook.
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, _ provider.Credentials) (*provider.FileDownload, error) {
	return a.client.DownloadBook(ctx, id)
}

// GetComments is unsupported.
func (a *ProviderAdapter) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}

// AddComment is unsupported.
func (a *ProviderAdapter) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// GetFavorites is unsupported.
func (a *ProviderAdapter) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}

// AddFavorite is unsupported.
func (a *ProviderAdapter) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// RemoveFavorite is unsupported.
func (a *ProviderAdapter) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// CheckAuth always returns true (no authentication required).
func (a *ProviderAdapter) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}

// Login is unsupported.
func (a *ProviderAdapter) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}

// FetchCaptcha is unsupported.
func (a *ProviderAdapter) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// HealthCheck probes the books endpoint.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	_, err := a.client.Browse(ctx, "", 0)
	if err != nil {
		return &provider.HealthStatus{Healthy: false}, nil
	}
	return &provider.HealthStatus{Healthy: true}, nil
}
