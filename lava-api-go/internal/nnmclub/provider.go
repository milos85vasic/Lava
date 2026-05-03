// Package nnmclub — provider.go implements the provider.Provider interface
// for nnmclub.to, wrapping *Client with an adapter.
package nnmclub

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// compile-time assertion
var _ provider.Provider = (*ProviderAdapter)(nil)

// ProviderAdapter wraps *Client to satisfy provider.Provider.
type ProviderAdapter struct {
	client *Client
}

// NewProviderAdapter returns an adapter for the given nnmclub client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return "nnmclub" }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "NNM-Club" }

// Capabilities returns the full capability set for NNM-Club.
func (a *ProviderAdapter) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapBrowse,
		provider.CapForumTree,
		provider.CapTopic,
		provider.CapComments,
		provider.CapFavorites,
		provider.CapTorrentDownload,
		provider.CapMagnetLink,
		provider.CapRSS,
	}
}

// AuthType returns the authentication mechanism.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthFormLogin }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "windows-1251" }

// ─── Core capabilities ───

// Search delegates to GetSearchPage.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	cookie := credToCookie(cred)
	result, err := a.client.GetSearchPage(ctx, opts, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// Browse delegates to GetBrowsePage.
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	cookie := credToCookie(cred)
	if page < 1 {
		page = 1
	}
	result, err := a.client.GetBrowsePage(ctx, categoryID, page, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// GetForumTree extracts the forum tree from the index page.
func (a *ProviderAdapter) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	cookie := credToCookie(cred)
	body, status, err := a.client.Fetch(ctx, "/forum/index.php", cookie)
	if err != nil {
		return nil, mapError(err)
	}
	if status >= 400 {
		return nil, fmt.Errorf("nnmclub: GET /forum/index.php → %d", status)
	}
	tree, err := parseForumTree(body)
	if err != nil {
		return nil, err
	}
	tree.Provider = a.ID()
	return tree, nil
}

// GetTopic delegates to GetTopicPage.
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	cookie := credToCookie(cred)
	if page < 1 {
		page = 1
	}
	result, err := a.client.GetTopicPage(ctx, id, page, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// GetTorrent fetches the binary .torrent file.
func (a *ProviderAdapter) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	cookie := credToCookie(cred)
	path := "/forum/download.php?id=" + id
	body, status, headers, err := a.client.FetchWithHeaders(ctx, path, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	if status == http.StatusNotFound {
		return nil, provider.ErrNotFound
	}
	if status >= 400 {
		return nil, fmt.Errorf("nnmclub: GET %s → %d", path, status)
	}
	return &provider.TorrentResult{
		Provider:    a.ID(),
		ID:          id,
		Filename:    contentDispositionFilename(headers.Get("Content-Disposition")),
		ContentType: headers.Get("Content-Type"),
		Body:        body,
	}, nil
}

// DownloadFile returns the binary .torrent file.
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	cookie := credToCookie(cred)
	path := "/forum/download.php?id=" + id
	body, status, headers, err := a.client.FetchWithHeaders(ctx, path, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	if status == http.StatusNotFound {
		return nil, provider.ErrNotFound
	}
	if status >= 400 {
		return nil, fmt.Errorf("nnmclub: GET %s → %d", path, status)
	}
	return &provider.FileDownload{
		Provider:    a.ID(),
		ID:          id,
		Filename:    contentDispositionFilename(headers.Get("Content-Disposition")),
		ContentType: headers.Get("Content-Type"),
		Body:        body,
	}, nil
}

// ─── Extended capabilities ───

// GetComments parses comments from the topic page.
func (a *ProviderAdapter) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	cookie := credToCookie(cred)
	path := "/forum/viewtopic.php?t=" + id
	if page > 1 {
		path += "&start=" + strconv.Itoa(50*(page-1))
	}
	body, status, err := a.client.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	if status == http.StatusNotFound {
		return nil, provider.ErrNotFound
	}
	if status >= 400 {
		return nil, fmt.Errorf("nnmclub: GET %s → %d", path, status)
	}
	comments, err := parseComments(body, page)
	if err != nil {
		return nil, err
	}
	comments.Provider = a.ID()
	return comments, nil
}

// AddComment is not supported by NNM-Club's read-only scraping surface.
func (a *ProviderAdapter) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// GetFavorites returns the user's bookmarked items. NNM-Club does not expose
// a dedicated favorites list endpoint for anonymous scraping; returns empty.
func (a *ProviderAdapter) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return &provider.FavoritesResult{
		Provider: a.ID(),
		Items:    []provider.SearchItem{},
	}, nil
}

// AddFavorite is not supported by the scraping surface.
func (a *ProviderAdapter) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// RemoveFavorite is not supported by the scraping surface.
func (a *ProviderAdapter) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// ─── Auth ───

// CheckAuth delegates to CheckAuth.
func (a *ProviderAdapter) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.CheckAuth(ctx, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// Login delegates to Login.
func (a *ProviderAdapter) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	result, err := a.client.Login(ctx, opts)
	if err != nil {
		return nil, mapError(err)
	}
	return result, nil
}

// FetchCaptcha is not supported (no captcha flow in NNM-Club login scraping).
func (a *ProviderAdapter) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// ─── Health ───

// HealthCheck performs a lightweight probe against the index page.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	_, _, err := a.client.Fetch(ctx, "/forum/index.php", "")
	if err != nil {
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
	case errors.Is(err, ErrNotFound):
		return provider.ErrNotFound
	case errors.Is(err, ErrUnauthorized):
		return provider.ErrUnauthorized
	case errors.Is(err, ErrCircuitOpen):
		return provider.ErrCircuitOpen
	default:
		return err
	}
}

func contentDispositionFilename(cd string) string {
	const prefix = `filename="`
	i := strings.Index(cd, prefix)
	if i >= 0 {
		start := i + len(prefix)
		end := strings.Index(cd[start:], `"`)
		if end >= 0 {
			return cd[start : start+end]
		}
	}
	return cd
}

// parseForumTree extracts a minimal forum tree from the index page HTML.
func parseForumTree(html []byte) (*provider.ForumTree, error) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(string(html)))
	if err != nil {
		return nil, fmt.Errorf("nnmclub: parse forum tree: %w", err)
	}

	var categories []provider.ForumCategory
	doc.Find("a.forumlink").Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		id := extractQueryParam(href, "f")
		name := strings.TrimSpace(s.Text())
		if id != "" && name != "" {
			categories = append(categories, provider.ForumCategory{
				ID:   id,
				Name: name,
			})
		}
	})

	return &provider.ForumTree{
		Categories: categories,
	}, nil
}

// parseComments extracts comments from a topic page.
func parseComments(html []byte, page int) (*provider.CommentsResult, error) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(string(html)))
	if err != nil {
		return nil, fmt.Errorf("nnmclub: parse comments: %w", err)
	}

	var items []provider.Comment
	doc.Find("table.forumline tr").Each(func(_ int, row *goquery.Selection) {
		author := strings.TrimSpace(row.Find(".name").First().Text())
		if author == "" {
			author = strings.TrimSpace(row.Find("b.postauthor").First().Text())
		}
		body := strings.TrimSpace(row.Find(".postbody").First().Text())
		if body == "" {
			body = strings.TrimSpace(row.Find("div.postbody").First().Text())
		}
		if author != "" && body != "" {
			items = append(items, provider.Comment{
				Author: author,
				Body:   body,
			})
		}
	})

	return &provider.CommentsResult{
		Page:  page,
		Total: len(items),
		Items: items,
	}, nil
}
