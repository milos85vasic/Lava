# Provider Interface Contract: Multi-Provider Extension

**Version**: v1  
**Applies to**: Go API (`internal/provider/provider.go`) and Android (`core/tracker/api/TrackerClient.kt`)

## Capability Model

### Core Capabilities (Mandatory for All Providers)

Every provider MUST implement and declare these four capabilities:

| Capability | User-Facing Feature | Required Interface Methods |
|------------|---------------------|---------------------------|
| `SEARCH` | Unified Search | `Search()` |
| `BROWSE` | Unified Forums / Categories | `Browse()`, `GetForumTree()` |
| `TOPIC` | Detail Screen | `GetTopic()` |
| `DOWNLOAD` | Content Download | `GetTorrent()`, `DownloadFile()` |

### Extended Capabilities (Optional per Provider)

| Capability | Description | Providers Expected |
|------------|-------------|-------------------|
| `COMMENTS` | Read/add comments | RuTracker, RuTor, NNMClub (partial), Kinozal, Archive.org (reviews partial) |
| `FAVORITES` | Read/add/remove favorites | RuTracker, NNMClub (partial) |
| `MAGNET_LINK` | Magnet link extraction | RuTracker, RuTor, NNMClub, Kinozal (partial) |
| `RSS` | RSS/OPDS feed | RuTor, NNMClub, Gutenberg (OPDS) |
| `UPLOAD` | Content upload | RuTracker (latent) |
| `USER_PROFILE` | User profile viewing | RuTracker (latent) |

### Capability Honesty Enforcement

Per constitutional clause 6.E:
- A declared capability MUST cause `getFeature<T>()` to return a non-null implementation
- The "Not implemented" stub pattern is a constitutional violation
- A unit test MUST enumerate every descriptor, every declared capability, and assert non-null `getFeature()`

## Go API Provider Interface

```go
type Provider interface {
    // Metadata
    ID() string
    DisplayName() string
    Capabilities() []ProviderCapability
    AuthType() AuthType
    Encoding() string

    // Core capabilities
    Search(ctx context.Context, opts SearchOpts, cred Credentials) (*SearchResult, error)
    Browse(ctx context.Context, categoryID string, page int, cred Credentials) (*BrowseResult, error)
    GetForumTree(ctx context.Context, cred Credentials) (*ForumTree, error)
    GetTopic(ctx context.Context, id string, page int, cred Credentials) (*TopicResult, error)
    GetTorrent(ctx context.Context, id string, cred Credentials) (*TorrentResult, error)
    DownloadFile(ctx context.Context, id string, cred Credentials) (*FileDownload, error)

    // Extended capabilities
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
    HealthCheck(ctx context.Context) (bool, error)
}
```

### Error Contracts for Unsupported Methods

If a provider does not declare an extended capability, the corresponding method MUST return `ErrUnsupported`:

```go
var ErrUnsupported = errors.New("capability not supported by this provider")
```

The handler layer translates `ErrUnsupported` to HTTP `501 Not Implemented` with a user-friendly message.

## Android TrackerClient Interface

```kotlin
// Existing from core/tracker/api/TrackerClient.kt
interface TrackerClient {
    val descriptor: TrackerDescriptor
    fun healthCheck(): HealthStatus
    fun <T : Any> getFeature(klass: KClass<T>): T?
}

// Existing feature interfaces
interface Searchable : TrackerFeature { fun search(request: SearchRequest): Flow<SearchResult> }
interface Browsable : TrackerFeature { fun browse(categoryId: String, page: Int): Flow<BrowseResult> }
interface Topic : TrackerFeature { fun getTopic(id: String, page: Int): Flow<TopicDetail> }
interface Downloadable : TrackerFeature { fun download(id: String): Flow<DownloadProgress> }
interface Comments : TrackerFeature { fun getComments(id: String, page: Int): Flow<CommentsResult> }
interface Favorites : TrackerFeature { fun getFavorites(): Flow<FavoritesResult> }
interface Authenticatable : TrackerFeature { fun login(credentials: Credential): Flow<AuthResult> }
```

### Capability-to-Feature Mapping

| Capability | Feature Interface | Android Method |
|------------|-------------------|----------------|
| SEARCH | `Searchable` | `getFeature(Searchable::class)` |
| BROWSE | `Browsable` | `getFeature(Browsable::class)` |
| TOPIC | `Topic` | `getFeature(Topic::class)` |
| DOWNLOAD | `Downloadable` | `getFeature(Downloadable::class)` |
| COMMENTS | `Comments` | `getFeature(Comments::class)` |
| FAVORITES | `Favorites` | `getFeature(Favorites::class)` |
| AUTH_REQUIRED | `Authenticatable` | `getFeature(Authenticatable::class)` |

## Provider Implementation Checklist

For each new provider, verify:

- [ ] Descriptor declares only supported capabilities (no false declarations)
- [ ] `getFeature()` returns non-null for every declared capability
- [ ] `getFeature()` returns null for every non-declared capability
- [ ] Every implemented feature interface method has a real-stack parser test
- [ ] Every parser test has a Bluff-Audit stamp in its commit message
- [ ] HTML/JSON fixtures are date-stamped and refreshed within 60 days
- [ ] Circuit breaker is configured with appropriate failure thresholds
- [ ] Charset decoding is tested with non-ASCII content
- [ ] Auth flow is tested with both success and failure cases
- [ ] Health check endpoint returns appropriate marker string
