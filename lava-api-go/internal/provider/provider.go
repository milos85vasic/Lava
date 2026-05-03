// Package provider defines the generic Provider interface that all content
// sources (torrent trackers and HTTP-based libraries) must satisfy.
//
// This interface generalizes the legacy ScraperClient (which was
// RuTracker-specific) into a provider-agnostic contract. Each provider
// implementation lives in its own package (internal/rutracker,
// internal/nnmclub, etc.) and is registered at startup via
// ProviderRegistry.
//
// Constitutional alignment:
//   - 6.E Capability Honesty: a provider MUST return a non-nil
//     implementation for every capability it declares. The "Not
//     implemented" stub pattern is forbidden.
//   - 6.D Behavioral Coverage: every method here MUST have at least
//     one real-stack test traversing the production code path.
package provider

import (
	"context"
	"errors"
	"time"
)

// Sentinel errors for provider-agnostic error handling.
// Each provider adapter maps its upstream-specific errors to these.
var (
	ErrNotFound     = errors.New("provider: not found")
	ErrForbidden    = errors.New("provider: forbidden")
	ErrUnauthorized = errors.New("provider: unauthorized")
	ErrCircuitOpen  = errors.New("provider: circuit breaker open")
	ErrNoData       = errors.New("provider: no data")
	ErrUnknown      = errors.New("provider: unknown error")
)

// ErrUnsupported is returned when a provider does not declare a
// capability but the caller invokes the corresponding method.
var ErrUnsupported = errors.New("capability not supported by this provider")

// ProviderCapability enumerates the features a provider may expose.
type ProviderCapability string

const (
	CapSearch         ProviderCapability = "SEARCH"
	CapBrowse         ProviderCapability = "BROWSE"
	CapForumTree      ProviderCapability = "FORUM_TREE"
	CapTopic          ProviderCapability = "TOPIC"
	CapComments       ProviderCapability = "COMMENTS"
	CapFavorites      ProviderCapability = "FAVORITES"
	CapTorrentDownload ProviderCapability = "TORRENT_DOWNLOAD"
	CapMagnetLink     ProviderCapability = "MAGNET_LINK"
	CapHTTPDownload   ProviderCapability = "HTTP_DOWNLOAD"
	CapRSS            ProviderCapability = "RSS"
	CapUpload         ProviderCapability = "UPLOAD"
	CapUserProfile    ProviderCapability = "USER_PROFILE"
)

// AuthType describes how a provider authenticates users.
type AuthType string

const (
	AuthNone         AuthType = "NONE"
	AuthFormLogin    AuthType = "FORM_LOGIN"
	AuthCaptchaLogin AuthType = "CAPTCHA_LOGIN"
	AuthOAuth        AuthType = "OAUTH"
	AuthAPIKey       AuthType = "API_KEY"
)

// Credentials carries authentication material from the Android client
// to the upstream provider. The zero value means "anonymous".
type Credentials struct {
	Type        string // "none", "cookie", "token", "apikey", "password"
	CookieValue string
	Token       string
	APIKey      string
	APISecret   string
	Username    string
	Password    string
}

// SearchOpts parameterises a search request.
type SearchOpts struct {
	Query    string
	Page     int
	Sort     string // "created", "seeders", "size", "title"
	Order    string // "desc", "asc"
	Category string // optional category/filter ID
}

// SearchResult is the paginated output of Provider.Search.
type SearchResult struct {
	Provider   string       `json:"provider"`
	Page       int          `json:"page"`
	TotalPages int          `json:"totalPages"`
	Results    []SearchItem `json:"results"`
}

// SearchItem is a single result row.
type SearchItem struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	Size         string `json:"size,omitempty"`
	SizeBytes    int64  `json:"sizeBytes,omitempty"`
	Seeders      int    `json:"seeders,omitempty"`
	Leechers     int    `json:"leechers,omitempty"`
	Date         string `json:"date,omitempty"`
	Category     string `json:"category,omitempty"`
	DownloadURL  string `json:"downloadUrl,omitempty"`
	MagnetLink   string `json:"magnetLink,omitempty"`
	ThumbnailURL string `json:"thumbnailUrl,omitempty"`
	InfoHash     string `json:"infoHash,omitempty"`
	Identifier   string `json:"identifier,omitempty"` // for HTTP providers
	ISBN         string `json:"isbn,omitempty"`
	Creator      string `json:"creator,omitempty"`
	Format       string `json:"format,omitempty"`
}

// BrowseResult is the output of Provider.Browse.
type BrowseResult struct {
	Provider string       `json:"provider"`
	Page     int          `json:"page"`
	Items    []SearchItem `json:"items"`
}

// ForumTree is the hierarchical category structure from Provider.GetForumTree.
type ForumTree struct {
	Provider   string         `json:"provider"`
	Categories []ForumCategory `json:"categories"`
}

// ForumCategory is a node in the forum tree.
type ForumCategory struct {
	ID             string          `json:"id"`
	Name           string          `json:"name"`
	Subcategories  []ForumCategory `json:"subcategories,omitempty"`
}

// TopicResult is the detail view of a single content item.
type TopicResult struct {
	Provider      string       `json:"provider"`
	ID            string       `json:"id"`
	Title         string       `json:"title"`
	OriginalTitle string       `json:"originalTitle,omitempty"`
	Year          int          `json:"year,omitempty"`
	PosterURL     string       `json:"posterUrl,omitempty"`
	Description   string       `json:"description,omitempty"`
	IMDbURL       string       `json:"imdbUrl,omitempty"`
	Files         []TopicFile  `json:"files,omitempty"`
	Comments      int          `json:"comments,omitempty"`
	MagnetLink    string       `json:"magnetLink,omitempty"`
	DownloadURL   string       `json:"downloadUrl,omitempty"`
}

// TopicFile is a file listed inside a topic.
type TopicFile struct {
	Name string `json:"name"`
	Size string `json:"size,omitempty"`
}

// CommentsResult is a page of comments/reviews.
type CommentsResult struct {
	Provider string    `json:"provider"`
	Page     int       `json:"page"`
	Total    int       `json:"total"`
	Items    []Comment `json:"items"`
}

// Comment is a single comment or review.
type Comment struct {
	ID      string `json:"id"`
	Author  string `json:"author"`
	Date    string `json:"date"`
	Body    string `json:"body"`
	Rating  int    `json:"rating,omitempty"`
}

// FavoritesResult is the user's bookmarked/favorited items.
type FavoritesResult struct {
	Provider string       `json:"provider"`
	Items    []SearchItem `json:"items"`
}

// TorrentResult is the binary metadata for a torrent download.
type TorrentResult struct {
	Provider    string `json:"provider"`
	ID          string `json:"id"`
	Filename    string `json:"filename"`
	ContentType string `json:"contentType"`
	Body        []byte `json:"-"`
}

// FileDownload is a generic HTTP download response.
type FileDownload struct {
	Provider    string `json:"provider"`
	ID          string `json:"id"`
	Filename    string `json:"filename"`
	ContentType string `json:"contentType"`
	Body        []byte `json:"-"`
}

// LoginOpts carries the credentials a user supplies during login.
type LoginOpts struct {
	Username     string
	Password     string
	CaptchaCode  string
	CaptchaSID   string
}

// LoginResult is the outcome of a login attempt.
type LoginResult struct {
	Success   bool      `json:"success"`
	AuthToken string    `json:"authToken,omitempty"`
	ExpiresAt time.Time `json:"expiresAt,omitempty"`
}

// CaptchaImage is a captcha challenge for visual display.
type CaptchaImage struct {
	Path string `json:"path"`
	Data []byte `json:"data"`
}

// HealthStatus is the result of a provider health probe.
type HealthStatus struct {
	Healthy      bool `json:"healthy"`
	ResponseTime int  `json:"responseTimeMs,omitempty"`
}

// Provider is the unified interface for all content sources.
type Provider interface {
	// Metadata
	ID() string
	DisplayName() string
	Capabilities() []ProviderCapability
	AuthType() AuthType
	Encoding() string

	// Core capabilities — every provider MUST implement these.
	Search(ctx context.Context, opts SearchOpts, cred Credentials) (*SearchResult, error)
	Browse(ctx context.Context, categoryID string, page int, cred Credentials) (*BrowseResult, error)
	GetForumTree(ctx context.Context, cred Credentials) (*ForumTree, error)
	GetTopic(ctx context.Context, id string, page int, cred Credentials) (*TopicResult, error)
	GetTorrent(ctx context.Context, id string, cred Credentials) (*TorrentResult, error)
	DownloadFile(ctx context.Context, id string, cred Credentials) (*FileDownload, error)

	// Extended capabilities — providers MAY implement these.
	// If unimplemented they MUST return ErrUnsupported.
	GetComments(ctx context.Context, id string, page int, cred Credentials) (*CommentsResult, error)
	AddComment(ctx context.Context, id, message string, cred Credentials) (bool, error)
	GetFavorites(ctx context.Context, cred Credentials) (*FavoritesResult, error)
	AddFavorite(ctx context.Context, id string, cred Credentials) (bool, error)
	RemoveFavorite(ctx context.Context, id string, cred Credentials) (bool, error)

	// Auth
	CheckAuth(ctx context.Context, cred Credentials) (bool, error)
	Login(ctx context.Context, opts LoginOpts) (*LoginResult, error)
	FetchCaptcha(ctx context.Context, path string) (*CaptchaImage, error)

	// Health
	HealthCheck(ctx context.Context) (*HealthStatus, error)
}
