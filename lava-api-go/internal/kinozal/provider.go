package kinozal

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"digital.vasic.lava.apigo/internal/provider"
)

var _ provider.Provider = (*ProviderAdapter)(nil)

// ProviderAdapter wraps *Client to satisfy provider.Provider.
type ProviderAdapter struct {
	client *Client
}

// NewProviderAdapter returns an adapter for the given kinozal client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return "kinozal" }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "Kinozal.tv" }

// Capabilities returns the capability set for kinozal.
func (a *ProviderAdapter) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapBrowse,
		provider.CapTopic,
		provider.CapComments,
		provider.CapTorrentDownload,
		provider.CapMagnetLink,
	}
}

// AuthType returns the authentication mechanism.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthFormLogin }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "windows-1251" }

// ─── Core capabilities ───

// Search delegates to the kinozal search endpoint.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	cookie := credToCookie(cred)
	result, err := a.client.Search(ctx, opts.Query, opts.Page, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// Browse delegates to the kinozal browse endpoint.
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	cookie := credToCookie(cred)
	result, err := a.client.Browse(ctx, categoryID, page, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// GetForumTree returns an empty tree — kinozal has no nested forum structure.
func (a *ProviderAdapter) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{
		Provider:   "kinozal",
		Categories: []provider.ForumCategory{},
	}, nil
}

// GetTopic delegates to the kinozal details endpoint.
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	cookie := credToCookie(cred)
	result, err := a.client.GetTopic(ctx, id, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// GetTorrent returns the binary .torrent file.
func (a *ProviderAdapter) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	fd, err := a.DownloadFile(ctx, id, cred)
	if err != nil {
		return nil, err
	}
	return &provider.TorrentResult{
		Provider:    fd.Provider,
		ID:          fd.ID,
		Filename:    fd.Filename,
		ContentType: fd.ContentType,
		Body:        fd.Body,
	}, nil
}

// DownloadFile returns the binary .torrent file.
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	cookie := credToCookie(cred)
	path := "/download.php?id=" + id
	body, status, headers, err := a.client.FetchWithHeaders(ctx, path, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	if status >= 400 {
		return nil, fmt.Errorf("kinozal: GET %s → %d", path, status)
	}
	filename := contentDispositionFilename(headers.Get("Content-Disposition"))
	if filename == "" {
		filename = id + ".torrent"
	}
	ct := headers.Get("Content-Type")
	if ct == "" {
		ct = "application/x-bittorrent"
	}
	return &provider.FileDownload{
		Provider:    a.ID(),
		ID:          id,
		Filename:    filename,
		ContentType: ct,
		Body:        body,
	}, nil
}

// ─── Extended capabilities ───

// GetComments returns an empty result — kinozal does not expose a separate
// paginated comments endpoint in this iteration.
func (a *ProviderAdapter) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return &provider.CommentsResult{
		Provider: "kinozal",
		Page:     page,
		Total:    0,
		Items:    []provider.Comment{},
	}, nil
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

// ─── Auth ───

// CheckAuth delegates to the client.
func (a *ProviderAdapter) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.CheckAuth(ctx, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// Login delegates to the client.
func (a *ProviderAdapter) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	result, err := a.client.Login(ctx, opts.Username, opts.Password)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// FetchCaptcha is unsupported.
func (a *ProviderAdapter) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// ─── Health ───

// HealthCheck performs a lightweight probe of the home page.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	_, _, err := a.client.Fetch(ctx, "/", "")
	if err != nil {
		// no-telemetry: HealthCheck path — Healthy=false IS the
		// telemetry surface (propagates to /health endpoint).
		return &provider.HealthStatus{Healthy: false}, nil
	}
	return &provider.HealthStatus{Healthy: true}, nil
}

// ─── Helpers ───

func credToCookie(cred provider.Credentials) string {
	if cred.Type == "cookie" {
		return cred.CookieValue
	}
	return ""
}

func mapError(err error) error {
	switch {
	case errors.Is(err, ErrCircuitOpen):
		return provider.ErrCircuitOpen
	case errors.Is(err, ErrUnauthorized):
		return provider.ErrUnauthorized
	case errors.Is(err, ErrNoData):
		return provider.ErrNoData
	default:
		return err
	}
}

func contentDispositionFilename(cd string) string {
	const prefix = `filename="`
	i := strings.Index(cd, prefix)
	if i >= 0 {
		start := i + len(prefix)
		end := start
		for end < len(cd) && cd[end] != '"' {
			end++
		}
		return cd[start:end]
	}
	return cd
}
