package archiveorg

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// compile-time assertion
var _ provider.Provider = (*ProviderAdapter)(nil)

// ProviderAdapter wraps *Client to satisfy provider.Provider.
type ProviderAdapter struct {
	client *Client
}

// NewProviderAdapter returns an adapter for the given archive.org client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return "archiveorg" }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "Internet Archive" }

// Capabilities returns the capability set for Internet Archive.
func (a *ProviderAdapter) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapBrowse,
		provider.CapForumTree,
		provider.CapTopic,
		provider.CapHTTPDownload,
	}
}

// AuthType returns the authentication mechanism.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthNone }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "UTF-8" }

// ─── Core capabilities ───

// Search delegates to Client.Search.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	sr, err := a.client.Search(ctx, opts.Query, opts.Page)
	if err != nil {
		return nil, mapError(err)
	}
	results := make([]provider.SearchItem, 0, len(sr.Items))
	for _, it := range sr.Items {
		results = append(results, provider.SearchItem{
			ID:         it.ID,
			Title:      it.Title,
			SizeBytes:  it.SizeBytes,
			Category:   it.MediaType,
			Date:       it.Year,
			Creator:    it.Creator,
			Identifier: it.ID,
		})
	}
	return &provider.SearchResult{
		Provider:   a.ID(),
		Page:       sr.Page,
		TotalPages: sr.TotalPages,
		Results:    results,
	}, nil
}

// Browse delegates to Client.Browse.
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, _ provider.Credentials) (*provider.BrowseResult, error) {
	sr, err := a.client.Browse(ctx, categoryID, page)
	if err != nil {
		return nil, mapError(err)
	}
	items := make([]provider.SearchItem, 0, len(sr.Items))
	for _, it := range sr.Items {
		items = append(items, provider.SearchItem{
			ID:         it.ID,
			Title:      it.Title,
			SizeBytes:  it.SizeBytes,
			Category:   it.MediaType,
			Date:       it.Year,
			Creator:    it.Creator,
			Identifier: it.ID,
		})
	}
	return &provider.BrowseResult{
		Provider: a.ID(),
		Page:     sr.Page,
		Items:    items,
	}, nil
}

// GetForumTree returns a static tree of top-level archive.org collections.
func (a *ProviderAdapter) GetForumTree(_ context.Context, _ provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{
		Provider: a.ID(),
		Categories: []provider.ForumCategory{
			{ID: "movies", Name: "Movies"},
			{ID: "audio", Name: "Audio"},
			{ID: "texts", Name: "Texts"},
			{ID: "software", Name: "Software"},
			{ID: "image", Name: "Image"},
		},
	}, nil
}

// GetTopic delegates to Client.Topic.
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, _ int, _ provider.Credentials) (*provider.TopicResult, error) {
	tr, err := a.client.Topic(ctx, id)
	if err != nil {
		return nil, mapError(err)
	}
	files := make([]provider.TopicFile, 0, len(tr.Files))
	for _, f := range tr.Files {
		files = append(files, provider.TopicFile{
			Name: f.Name,
			Size: f.Size,
		})
	}
	return &provider.TopicResult{
		Provider:    a.ID(),
		ID:          tr.ID,
		Title:       tr.Title,
		Description: tr.Description,
		Files:       files,
	}, nil
}

// GetTorrent returns ErrUnsupported — Internet Archive has no torrent metadata.
func (a *ProviderAdapter) GetTorrent(_ context.Context, _ string, _ provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}

// DownloadFile delegates to Client.Download. The id parameter is expected
// to be in the form "{identifier}/{filename}" so the caller can specify
// which file inside the item to retrieve.
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, _ provider.Credentials) (*provider.FileDownload, error) {
	// Parse "identifier/filename" composite id.
	parts := strings.SplitN(id, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return nil, fmt.Errorf("archiveorg: download id must be 'identifier/filename', got %q", id)
	}
	identifier, filename := parts[0], parts[1]
	fd, err := a.client.Download(ctx, identifier, filename)
	if err != nil {
		return nil, mapError(err)
	}
	return &provider.FileDownload{
		Provider: a.ID(),
		ID:       id,
		Filename: fd.Filename,
		Body:     fd.Body,
	}, nil
}

// ─── Extended capabilities ───

// GetComments returns ErrUnsupported.
func (a *ProviderAdapter) GetComments(_ context.Context, _ string, _ int, _ provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}

// AddComment returns ErrUnsupported.
func (a *ProviderAdapter) AddComment(_ context.Context, _, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// GetFavorites returns ErrUnsupported.
func (a *ProviderAdapter) GetFavorites(_ context.Context, _ provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}

// AddFavorite returns ErrUnsupported.
func (a *ProviderAdapter) AddFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// RemoveFavorite returns ErrUnsupported.
func (a *ProviderAdapter) RemoveFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// ─── Auth ───

// CheckAuth always returns true — Internet Archive requires no authentication.
func (a *ProviderAdapter) CheckAuth(_ context.Context, _ provider.Credentials) (bool, error) {
	return true, nil
}

// Login always returns ErrUnsupported.
func (a *ProviderAdapter) Login(_ context.Context, _ provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}

// FetchCaptcha returns ErrUnsupported.
func (a *ProviderAdapter) FetchCaptcha(_ context.Context, _ string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// ─── Health ───

// HealthCheck performs a lightweight probe against the archive.org home page.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	start := time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, a.client.baseURL, nil)
	if err != nil {
		return &provider.HealthStatus{Healthy: false}, nil
	}
	resp, err := a.client.client.Do(req)
	if err != nil {
		return &provider.HealthStatus{Healthy: false}, nil
	}
	resp.Body.Close()
	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		return &provider.HealthStatus{Healthy: true, ResponseTime: int(time.Since(start).Milliseconds())}, nil
	}
	return &provider.HealthStatus{Healthy: false}, nil
}

// mapError translates archive.org-specific errors to provider-agnostic ones.
func mapError(err error) error {
	if err == nil {
		return nil
	}
	// For now, all archive.org errors map to ErrUnknown except explicit
	// HTTP-status-derived errors that could be added later.
	return provider.ErrUnknown
}
