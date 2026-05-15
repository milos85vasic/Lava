// Package rutracker — provider.go implements the provider.Provider interface
// for rutracker.org, wrapping the legacy *rutracker.Client with an adapter
// that maps provider-agnostic types to rutracker-specific ones.
//
// This file is the bridge between the new multi-provider architecture and
// the existing rutracker scraper. It MUST NOT reimplement parsing logic;
// all HTML parsing continues to live in forum.go, search.go, topic.go, etc.
package rutracker

import (
	"context"
	"errors"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/provider"
)

// compile-time assertion
var _ provider.Provider = (*ProviderAdapter)(nil)

// ProviderAdapter wraps *Client to satisfy provider.Provider.
type ProviderAdapter struct {
	client *Client
}

// NewProviderAdapter returns an adapter for the given rutracker client.
func NewProviderAdapter(client *Client) *ProviderAdapter {
	return &ProviderAdapter{client: client}
}

// ID returns the canonical provider identifier.
func (a *ProviderAdapter) ID() string { return "rutracker" }

// DisplayName returns the human-readable name.
func (a *ProviderAdapter) DisplayName() string { return "RuTracker" }

// Capabilities returns the full capability set for rutracker.
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
		provider.CapUpload,
		provider.CapUserProfile,
	}
}

// AuthType returns the authentication mechanism.
func (a *ProviderAdapter) AuthType() provider.AuthType { return provider.AuthCaptchaLogin }

// Encoding returns the upstream character set.
func (a *ProviderAdapter) Encoding() string { return "windows-1251" }

// ─── Core capabilities ───

// Search delegates to GetSearchPage.
func (a *ProviderAdapter) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	cookie := credToCookie(cred)
	rtOpts := toRutrackerSearchOpts(opts)
	page, err := a.client.GetSearchPage(ctx, rtOpts, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromSearchPage(page), nil
}

// Browse delegates to GetCategoryPage.
func (a *ProviderAdapter) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	cookie := credToCookie(cred)
	p := page
	if p < 1 {
		p = 1
	}
	catPage, err := a.client.GetCategoryPage(ctx, categoryID, &p, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromCategoryPage(catPage), nil
}

// GetForumTree delegates to GetForum.
func (a *ProviderAdapter) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	cookie := credToCookie(cred)
	forum, err := a.client.GetForum(ctx, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromForumDto(forum), nil
}

// GetTopic delegates to GetTopicPage.
func (a *ProviderAdapter) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	cookie := credToCookie(cred)
	p := page
	if p < 1 {
		p = 1
	}
	tp, err := a.client.GetTopicPage(ctx, id, &p, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromTopicPage(tp), nil
}

// GetTorrent delegates to GetTorrentFile.
func (a *ProviderAdapter) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	cookie := credToCookie(cred)
	f, err := a.client.GetTorrentFile(ctx, id, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return &provider.TorrentResult{
		Provider:    a.ID(),
		ID:          id,
		Filename:    contentDispositionFilename(f.ContentDisposition),
		ContentType: f.ContentType,
		Body:        f.Bytes,
	}, nil
}

// DownloadFile returns the binary .torrent file.
func (a *ProviderAdapter) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	cookie := credToCookie(cred)
	f, err := a.client.GetTorrentFile(ctx, id, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return &provider.FileDownload{
		Provider:    a.ID(),
		ID:          id,
		Filename:    contentDispositionFilename(f.ContentDisposition),
		ContentType: f.ContentType,
		Body:        f.Bytes,
	}, nil
}

// ─── Extended capabilities ───

// GetComments delegates to GetCommentsPage.
func (a *ProviderAdapter) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	cookie := credToCookie(cred)
	p := page
	if p < 1 {
		p = 1
	}
	cp, err := a.client.GetCommentsPage(ctx, id, &p, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromCommentsPage(cp), nil
}

// AddComment delegates to AddComment.
func (a *ProviderAdapter) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.AddComment(ctx, id, message, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// GetFavorites delegates to GetFavorites.
func (a *ProviderAdapter) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	cookie := credToCookie(cred)
	fav, err := a.client.GetFavorites(ctx, cookie)
	if err != nil {
		return nil, mapError(err)
	}
	return fromFavoritesDto(fav), nil
}

// AddFavorite delegates to AddFavorite.
func (a *ProviderAdapter) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.AddFavorite(ctx, id, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// RemoveFavorite delegates to RemoveFavorite.
func (a *ProviderAdapter) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.RemoveFavorite(ctx, id, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// ─── Auth ───

// CheckAuth delegates to CheckAuthorised.
func (a *ProviderAdapter) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	cookie := credToCookie(cred)
	ok, err := a.client.CheckAuthorised(ctx, cookie)
	if err != nil {
		return false, mapError(err)
	}
	return ok, nil
}

// Login delegates to Login.
func (a *ProviderAdapter) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	lp := LoginParams{
		Username: opts.Username,
		Password: opts.Password,
	}
	if opts.CaptchaSID != "" && opts.CaptchaCode != "" {
		lp.CaptchaSid = &opts.CaptchaSID
		lp.CaptchaCode = &opts.CaptchaCode
		// CaptchaValue is the actual user-typed answer; for rutracker the
		// CaptchaCode IS the field name and CaptchaValue is the answer.
		// Our LoginOpts only has CaptchaCode (the answer), so we use it
		// as the value and leave the dynamic field-name empty — the
		// adapter consumer is expected to supply the full rutracker shape.
		// TODO: revisit when we wire the real multi-provider auth flow.
		val := opts.CaptchaCode
		lp.CaptchaValue = &val
	}
	resp, err := a.client.Login(ctx, lp)
	if err != nil {
		return nil, mapError(err)
	}
	// AuthResponseDto is a discriminated union. We only care about the
	// Success branch for the provider-agnostic result.
	success, err := resp.AsAuthResponseDtoSuccess()
	if err != nil {
		// no-telemetry: discriminated-union narrowing — when the response
		// is NOT the Success variant, the error here means the response
		// matched a different variant (WrongCredits, Captcha, etc).
		// ErrUnauthorized is the correct semantic propagation; the variant
		// detection happens at the upstream caller via mapError.
		return nil, provider.ErrUnauthorized
	}
	return &provider.LoginResult{
		Success:   true,
		AuthToken: success.User.Id,
	}, nil
}

// FetchCaptcha delegates to FetchCaptcha.
func (a *ProviderAdapter) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	ci, err := a.client.FetchCaptcha(ctx, path)
	if err != nil {
		return nil, mapError(err)
	}
	return &provider.CaptchaImage{
		Path: path,
		Data: ci.Bytes,
	}, nil
}

// ─── Health ───

// HealthCheck performs a lightweight probe.
func (a *ProviderAdapter) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	// Use the forum tree endpoint as a lightweight health check.
	_, err := a.client.GetForum(ctx, "")
	if err != nil {
		// no-telemetry: HealthCheck path — Healthy=false IS the
		// telemetry surface (propagates to /health endpoint).
		return &provider.HealthStatus{Healthy: false}, nil
	}
	return &provider.HealthStatus{Healthy: true}, nil
}

// ─── Helpers ───

// credToCookie extracts the cookie string from provider-agnostic credentials.
func credToCookie(cred provider.Credentials) string {
	if cred.Type == "cookie" {
		return cred.CookieValue
	}
	return ""
}

// mapError translates rutracker-specific sentinels to provider-agnostic ones.
func mapError(err error) error {
	switch {
	case errors.Is(err, ErrNotFound):
		return provider.ErrNotFound
	case errors.Is(err, ErrForbidden):
		return provider.ErrForbidden
	case errors.Is(err, ErrUnauthorized):
		return provider.ErrUnauthorized
	case errors.Is(err, ErrCircuitOpen):
		return provider.ErrCircuitOpen
	case errors.Is(err, ErrNoData):
		return provider.ErrNoData
	case errors.Is(err, ErrUnknown):
		return provider.ErrUnknown
	default:
		return err
	}
}

// contentDispositionFilename extracts the filename from a Content-Disposition
// header like `attachment; filename="foo.torrent"`.
func contentDispositionFilename(cd string) string {
	// Simple extraction: look for filename="..."
	const prefix = `filename="`
	i := 0
	for i < len(cd) {
		if i+len(prefix) <= len(cd) && cd[i:i+len(prefix)] == prefix {
			start := i + len(prefix)
			end := start
			for end < len(cd) && cd[end] != '"' {
				end++
			}
			return cd[start:end]
		}
		i++
	}
	return cd
}

// ─── DTO converters ───

func toRutrackerSearchOpts(opts provider.SearchOpts) SearchOpts {
	rt := SearchOpts{}
	if opts.Query != "" {
		q := opts.Query
		rt.Query = &q
	}
	if opts.Page > 0 {
		p := opts.Page
		rt.Page = &p
	}
	if opts.Sort != "" {
		st := gen.SearchSortTypeDto(opts.Sort)
		rt.SortType = &st
	}
	if opts.Order != "" {
		so := gen.SearchSortOrderDto(opts.Order)
		rt.SortOrder = &so
	}
	if opts.Category != "" {
		c := opts.Category
		rt.Categories = &c
	}
	return rt
}

func fromSearchPage(page *gen.SearchPageDto) *provider.SearchResult {
	out := &provider.SearchResult{
		Provider:   "rutracker",
		Page:       int(page.Page),
		TotalPages: int(page.Pages),
		Results:    make([]provider.SearchItem, 0, len(page.Torrents)),
	}
	for _, t := range page.Torrents {
		out.Results = append(out.Results, fromTorrentDto(t))
	}
	return out
}

func fromTorrentDto(t gen.ForumTopicDtoTorrent) provider.SearchItem {
	item := provider.SearchItem{
		ID:    t.Id,
		Title: t.Title,
	}
	if t.Size != nil {
		item.Size = *t.Size
	}
	if t.Seeds != nil {
		item.Seeders = int(*t.Seeds)
	}
	if t.Leeches != nil {
		item.Leechers = int(*t.Leeches)
	}
	if t.MagnetLink != nil {
		item.MagnetLink = *t.MagnetLink
	}
	return item
}

func fromCategoryPage(page *gen.CategoryPageDto) *provider.BrowseResult {
	out := &provider.BrowseResult{
		Provider: "rutracker",
		Page:     int(page.Page),
	}
	if page.Topics != nil {
		out.Items = make([]provider.SearchItem, 0, len(*page.Topics))
		for _, t := range *page.Topics {
			if torrent, err := t.AsForumTopicDtoTorrent(); err == nil {
				out.Items = append(out.Items, fromTorrentDto(torrent))
			}
		}
	}
	return out
}

func fromForumDto(forum *gen.ForumDto) *provider.ForumTree {
	out := &provider.ForumTree{
		Provider:   "rutracker",
		Categories: make([]provider.ForumCategory, 0, len(forum.Children)),
	}
	for _, c := range forum.Children {
		out.Categories = append(out.Categories, fromCategoryDto(c))
	}
	return out
}

func fromCategoryDto(c gen.CategoryDto) provider.ForumCategory {
	cat := provider.ForumCategory{
		Name: c.Name,
	}
	if c.Id != nil {
		cat.ID = *c.Id
	}
	if c.Children != nil {
		cat.Subcategories = make([]provider.ForumCategory, 0, len(*c.Children))
		for _, sc := range *c.Children {
			cat.Subcategories = append(cat.Subcategories, fromCategoryDto(sc))
		}
	}
	return cat
}

func fromTopicPage(tp *gen.TopicPageDto) *provider.TopicResult {
	out := &provider.TopicResult{
		Provider: "rutracker",
		ID:       tp.Id,
		Title:    tp.Title,
	}
	if tp.TorrentData != nil {
		if tp.TorrentData.MagnetLink != nil {
			out.MagnetLink = *tp.TorrentData.MagnetLink
		}
		if tp.TorrentData.Size != nil {
			out.Files = []provider.TopicFile{{Name: "Size", Size: *tp.TorrentData.Size}}
		}
	}
	return out
}

func fromCommentsPage(cp *gen.CommentsPageDto) *provider.CommentsResult {
	out := &provider.CommentsResult{
		Provider: "rutracker",
		Page:     int(cp.Page),
		Total:    int(cp.Pages),
		Items:    make([]provider.Comment, 0, len(cp.Posts)),
	}
	for _, p := range cp.Posts {
		out.Items = append(out.Items, provider.Comment{
			ID:     p.Id,
			Author: p.Author.Name,
			Date:   p.Date,
		})
	}
	return out
}

func fromFavoritesDto(fav *gen.FavoritesDto) *provider.FavoritesResult {
	out := &provider.FavoritesResult{
		Provider: "rutracker",
		Items:    make([]provider.SearchItem, 0, len(fav.Topics)),
	}
	for _, t := range fav.Topics {
		if torrent, err := t.AsForumTopicDtoTorrent(); err == nil {
			out.Items = append(out.Items, fromTorrentDto(torrent))
		}
	}
	return out
}
