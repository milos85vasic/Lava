// Package jackettprovider exposes each configured Jackett indexer as a
// first-class provider.Provider, so every indexer becomes a discoverable,
// uniformly-routed provider in the /v1/{id}/{op} surface and the
// /v1/providers catalogue (dynamic provider discovery, 2026-06-11 spec §4.1,
// operator decision §3.2 "each indexer = a provider").
//
// A Jackett indexer is app→lava-api-go→Jackett-sidecar→indexer: the device
// never holds the Jackett api_key (§6.H) and never authenticates per-indexer
// (auth is the sidecar's job), so the provider declares AuthType NONE +
// SupportsAnonymous true. Its capability set is exactly what Torznab can serve:
// SEARCH, MAGNET_LINK, TORRENT_DOWNLOAD. Every extended capability honestly
// returns provider.ErrUnsupported (§6.E capability honesty); the v1 middleware
// 501s those routes before they reach the method, but the method refuses too.
package jackettprovider

import (
	"context"
	"strconv"

	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/provider"
)

var _ provider.Provider = (*JackettIndexerProvider)(nil)

// jackettClient is the narrow slice of *jackett.Client the provider depends on.
// Defining it as an interface keeps the provider honest under test (a real
// *jackett.Client pointed at a fake Torznab upstream, or a fake client for the
// delegation-shape unit tests) and decouples this package from the full client.
type jackettClient interface {
	Search(ctx context.Context, indexerID, query string) ([]jackett.Result, error)
	Download(ctx context.Context, downloadURL string) (*jackett.DownloadResult, error)
}

// JackettIndexerProvider implements provider.Provider for one Jackett indexer.
//
// It embeds provider.BaseProvider for the catalogue-metadata defaults, then
// overrides Kind()/SupportsAnonymous() (BaseURLs stays nil — the API fronts the
// indexer entirely server-side, so there is no device-facing mirror URL).
type JackettIndexerProvider struct {
	provider.BaseProvider
	indexerID   string
	displayName string
	client      jackettClient
}

// New builds a provider for the given Jackett indexer. indexerID is the Jackett
// indexer id (becomes the provider id + the /v1/{id} path segment); displayName
// is the human-readable name shown in the client's provider list.
func New(indexerID, displayName string, client jackettClient) *JackettIndexerProvider {
	if displayName == "" {
		displayName = indexerID
	}
	return &JackettIndexerProvider{
		indexerID:   indexerID,
		displayName: displayName,
		client:      client,
	}
}

// --- Metadata --------------------------------------------------------------

// ID returns the indexer id (the canonical provider id).
func (a *JackettIndexerProvider) ID() string { return a.indexerID }

// DisplayName returns the human-readable indexer name.
func (a *JackettIndexerProvider) DisplayName() string { return a.displayName }

// Kind marks this provider as Jackett-discovered (overrides the "native"
// default) so the client can attribute it to the Jackett sidecar.
func (a *JackettIndexerProvider) Kind() string { return "jackett" }

// AuthType is NONE: the device never authenticates per-indexer; the Jackett
// sidecar handles any per-indexer auth server-side.
func (a *JackettIndexerProvider) AuthType() provider.AuthType { return provider.AuthNone }

// SupportsAnonymous overrides the default: a Jackett indexer is usable without
// any device-supplied credentials.
func (a *JackettIndexerProvider) SupportsAnonymous() bool { return true }

// Encoding is UTF-8 (Torznab feeds are UTF-8).
func (a *JackettIndexerProvider) Encoding() string { return "UTF-8" }

// Capabilities is exactly what a Jackett indexer can serve over Torznab.
func (a *JackettIndexerProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapMagnetLink,
		provider.CapTorrentDownload,
	}
}

// --- Core capabilities -----------------------------------------------------

// Search delegates to the Jackett client using THIS provider's indexer id and
// maps the parsed Torznab results into the uniform provider.SearchResult.
func (a *JackettIndexerProvider) Search(ctx context.Context, opts provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	results, err := a.client.Search(ctx, a.indexerID, opts.Query)
	if err != nil {
		return nil, err
	}
	return a.mapResults(results), nil
}

// DownloadFile resolves a Torznab item's download link via the Jackett client
// and surfaces the resolved torrent bytes or magnet URI. The id is the
// download/enclosure URL (or magnet) the client received in a prior Search row.
func (a *JackettIndexerProvider) DownloadFile(ctx context.Context, id string, _ provider.Credentials) (*provider.FileDownload, error) {
	dl, err := a.client.Download(ctx, id)
	if err != nil {
		return nil, err
	}
	out := &provider.FileDownload{
		Provider:    a.indexerID,
		ID:          id,
		ContentType: dl.ContentType,
	}
	if dl.IsMagnet() {
		// Magnet resolution: carry the magnet URI as the body so the client can
		// hand it to a torrent app. ContentType stays empty for magnets.
		out.Body = []byte(dl.Magnet)
		out.ContentType = "text/plain"
		out.Filename = a.indexerID + ".magnet"
		return out, nil
	}
	out.Body = dl.TorrentBytes
	if out.ContentType == "" {
		out.ContentType = jackett.EnclosureTypeTorrent
	}
	out.Filename = a.indexerID + ".torrent"
	return out, nil
}

// GetTorrent resolves the same way DownloadFile does, returning the .torrent
// bytes under the TorrentResult shape (the v1 torrent route uses this).
func (a *JackettIndexerProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	dl, err := a.DownloadFile(ctx, id, cred)
	if err != nil {
		return nil, err
	}
	return &provider.TorrentResult{
		Provider:    dl.Provider,
		ID:          dl.ID,
		Filename:    dl.Filename,
		ContentType: dl.ContentType,
		Body:        dl.Body,
	}, nil
}

// --- Extended capabilities (honest 501 path, §6.E) -------------------------

// Browse is unsupported by a Jackett indexer.
func (a *JackettIndexerProvider) Browse(_ context.Context, _ string, _ int, _ provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}

// GetForumTree is unsupported.
func (a *JackettIndexerProvider) GetForumTree(_ context.Context, _ provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}

// GetTopic is unsupported.
func (a *JackettIndexerProvider) GetTopic(_ context.Context, _ string, _ int, _ provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}

// GetComments is unsupported.
func (a *JackettIndexerProvider) GetComments(_ context.Context, _ string, _ int, _ provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}

// AddComment is unsupported.
func (a *JackettIndexerProvider) AddComment(_ context.Context, _, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// GetFavorites is unsupported.
func (a *JackettIndexerProvider) GetFavorites(_ context.Context, _ provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}

// AddFavorite is unsupported.
func (a *JackettIndexerProvider) AddFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// RemoveFavorite is unsupported.
func (a *JackettIndexerProvider) RemoveFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}

// --- Auth (NONE — no per-indexer auth) -------------------------------------

// CheckAuth always succeeds: a Jackett indexer needs no device credentials.
func (a *JackettIndexerProvider) CheckAuth(_ context.Context, _ provider.Credentials) (bool, error) {
	return true, nil
}

// Login is unsupported (AuthType NONE).
func (a *JackettIndexerProvider) Login(_ context.Context, _ provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}

// FetchCaptcha is unsupported (no captcha for a Jackett indexer).
func (a *JackettIndexerProvider) FetchCaptcha(_ context.Context, _ string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}

// --- Health ----------------------------------------------------------------

// HealthCheck reports healthy. The Jackett sidecar's own health is the API's
// concern (surfaced by the sidecar readiness probe); a per-indexer provider has
// no independent upstream to probe without spending a real Torznab query.
//
// no-telemetry: trivial constant-healthy path; nothing can fail here.
func (a *JackettIndexerProvider) HealthCheck(_ context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// --- mapping ----------------------------------------------------------------

// mapResults converts parsed Torznab results into the uniform SearchResult.
// Jackett is a flat (non-paginated) Torznab query, so Page/TotalPages are 1.
// This mirrors v1.mapJackettResults but stamps the indexer id (not the generic
// "jackett") as the provider, so each indexer's results are attributable.
func (a *JackettIndexerProvider) mapResults(results []jackett.Result) *provider.SearchResult {
	items := make([]provider.SearchItem, 0, len(results))
	for _, r := range results {
		item := provider.SearchItem{
			ID:        firstNonEmpty(r.GUID, r.Title),
			Title:     r.Title,
			SizeBytes: r.Size,
			Size:      humanSize(r.Size),
			Category:  a.indexerID,
			InfoHash:  r.Infohash,
		}
		if r.Seeders >= 0 {
			item.Seeders = r.Seeders
		}
		if r.IsMagnetEnclosure() {
			item.MagnetLink = firstNonEmpty(r.MagnetURL, r.DownloadURL)
		} else {
			item.DownloadURL = r.DownloadURL
			item.MagnetLink = r.MagnetURL
		}
		items = append(items, item)
	}
	return &provider.SearchResult{
		Provider:   a.indexerID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// humanSize renders a byte count as a compact human-readable string. Returns
// "" for a non-positive size so the omitempty JSON tag drops the field.
func humanSize(bytes int64) string {
	if bytes <= 0 {
		return ""
	}
	const unit = 1024
	if bytes < unit {
		return strconv.FormatInt(bytes, 10) + " B"
	}
	div, exp := int64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	value := float64(bytes) / float64(div)
	suffix := []string{"KB", "MB", "GB", "TB", "PB", "EB"}[exp]
	return strconv.FormatFloat(value, 'f', 2, 64) + " " + suffix
}
