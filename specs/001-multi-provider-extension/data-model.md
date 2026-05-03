# Data Model: Multi-Provider Extension

**Feature**: Multi-Provider Extension  
**Date**: 2026-05-02

## 1. Android Room Schema (AppDatabase v8)

### 1.1 New Entities

#### `CredentialEntity`

```kotlin
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String, // UUID
    val label: String,
    val type: String, // "USERNAME_PASSWORD", "TOKEN", "API_KEY"
    val username: String?,
    val encryptedPassword: ByteArray?, // AES-256-GCM encrypted
    val encryptedToken: ByteArray?,
    val encryptedApiKey: ByteArray?,
    val encryptedApiSecret: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?
)
```

**Relationships**: Many-to-many with providers via `CredentialProviderAssociation`.

#### `CredentialProviderAssociation`

```kotlin
@Entity(
    tableName = "credential_provider_associations",
    primaryKeys = ["credentialId", "providerId"]
)
data class CredentialProviderAssociation(
    val credentialId: String,
    val providerId: String
)
```

#### `ProviderConfigEntity`

```kotlin
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val enabledForSearch: Boolean = true,
    val enabledForForums: Boolean = true,
    val anonymousMode: Boolean = false,
    val activeCredentialId: String?,
    val displayOrder: Int = 0,
    val searchTimeoutMs: Int = 10000,
    val updatedAt: Long
)
```

#### `OfflineSearchCacheEntity`

```kotlin
@Entity(tableName = "offline_search_cache")
data class OfflineSearchCacheEntity(
    @PrimaryKey val cacheKey: String, // hash of query + providerIds + filters
    val query: String,
    val providerIds: String, // comma-separated
    val resultsJson: String, // serialized List<UnifiedResult>
    val createdAt: Long,
    val expiresAt: Long // createdAt + 24h
)
```

#### `OfflineTopicCacheEntity`

```kotlin
@Entity(tableName = "offline_topic_cache")
data class OfflineTopicCacheEntity(
    @PrimaryKey val cacheKey: String, // providerId + topicId
    val providerId: String,
    val topicId: String,
    val topicJson: String, // serialized TopicDetail
    val createdAt: Long,
    val expiresAt: Long
)
```

#### `GutenbergCatalogEntity` (in `core/tracker/gutenberg/`)

```kotlin
@Entity(tableName = "gutenberg_catalog")
@Fts4 // Full-text search
 data class GutenbergCatalogEntity(
    @PrimaryKey val bookId: Int,
    val title: String,
    val creator: String,
    val language: String,
    val subject: String,
    val bookshelf: String,
    val downloadUrlEpubImages: String?,
    val downloadUrlEpubNoImages: String?,
    val downloadUrlKindle: String?,
    val downloadUrlHtml: String?,
    val downloadUrlText: String?,
    val lastSyncedAt: Long
)
```

### 1.2 Schema Migration: v7 → v8

```sql
-- 0008_add_multi_provider_tables.kt
CREATE TABLE IF NOT EXISTS credentials (
    id TEXT PRIMARY KEY NOT NULL,
    label TEXT NOT NULL,
    type TEXT NOT NULL,
    username TEXT,
    encrypted_password BLOB,
    encrypted_token BLOB,
    encrypted_api_key BLOB,
    encrypted_api_secret BLOB,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_used_at INTEGER
);

CREATE TABLE IF NOT EXISTS credential_provider_associations (
    credential_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    PRIMARY KEY (credential_id, provider_id),
    FOREIGN KEY (credential_id) REFERENCES credentials(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS provider_configs (
    provider_id TEXT PRIMARY KEY NOT NULL,
    enabled_for_search INTEGER NOT NULL DEFAULT 1,
    enabled_for_forums INTEGER NOT NULL DEFAULT 1,
    anonymous_mode INTEGER NOT NULL DEFAULT 0,
    active_credential_id TEXT,
    display_order INTEGER NOT NULL DEFAULT 0,
    search_timeout_ms INTEGER NOT NULL DEFAULT 10000,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS offline_search_cache (
    cache_key TEXT PRIMARY KEY NOT NULL,
    query TEXT NOT NULL,
    provider_ids TEXT NOT NULL,
    results_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS offline_topic_cache (
    cache_key TEXT PRIMARY KEY NOT NULL,
    provider_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    topic_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_offline_search_expires ON offline_search_cache(expires_at);
CREATE INDEX IF NOT EXISTS idx_offline_topic_expires ON offline_topic_cache(expires_at);
```

## 2. Go API PostgreSQL Schema

### 2.1 New Tables

#### `provider_credentials`

```sql
CREATE TABLE provider_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_realm_hash TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    cred_type TEXT NOT NULL CHECK (cred_type IN ('cookie', 'token', 'apikey', 'password')),
    cred_label TEXT NOT NULL,
    cred_value_encrypted BYTEA NOT NULL, -- AES-256-GCM
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_provider_credentials_realm ON provider_credentials(user_realm_hash);
CREATE INDEX idx_provider_credentials_provider ON provider_credentials(provider_id);
```

#### `provider_configs`

```sql
CREATE TABLE provider_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_realm_hash TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    anonymous_mode BOOLEAN NOT NULL DEFAULT false,
    credentials_id UUID REFERENCES provider_credentials(id) ON DELETE SET NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    custom_mirror_url TEXT,
    request_delay_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_provider_configs_realm ON provider_configs(user_realm_hash);
```

#### `search_provider_selections`

```sql
CREATE TABLE search_provider_selections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_realm_hash TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(user_realm_hash, provider_id)
);
```

#### `forum_provider_selections`

```sql
CREATE TABLE forum_provider_selections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_realm_hash TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(user_realm_hash, provider_id)
);
```

## 3. Domain Models (Kotlin)

### 3.1 Credential Domain Model

```kotlin
// core/credentials/api/Credential.kt
data class Credential(
    val id: String, // UUID
    val label: String,
    val type: CredentialType,
    val associatedProviderIds: Set<String>,
    val username: String? = null,
    val password: String? = null, // plaintext in memory only; encrypted at rest
    val token: String? = null,
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)

enum class CredentialType {
    USERNAME_PASSWORD,
    TOKEN,
    API_KEY,
}
```

### 3.2 ProviderConfig Domain Model

```kotlin
// core/models/ProviderConfig.kt
data class ProviderConfig(
    val providerId: String,
    val enabledForSearch: Boolean = true,
    val enabledForForums: Boolean = true,
    val anonymousMode: Boolean = false,
    val activeCredentialId: String? = null,
    val displayOrder: Int = 0,
    val searchTimeoutMs: Int = 10000,
)
```

### 3.3 Search Result Models

```kotlin
// core/tracker/api/SearchResultBatch.kt
data class SearchResultBatch(
    val providerId: String,
    val results: List<TorrentItem>, // existing type from tracker:api
    val error: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isComplete: Boolean = false,
)

// core/models/UnifiedResult.kt
data class UnifiedResult(
    val id: String, // deduplication key
    val title: String,
    val sourceProviders: List<ProviderOccurrence>,
    val size: String?,
    val date: String?,
    val thumbnailUrl: String?,
    val detailRoute: String,
)

data class ProviderOccurrence(
    val providerId: String,
    val providerDisplayName: String,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val format: String? = null,
    val downloadUrl: String? = null,
    val magnetLink: String? = null,
    val originalResult: TorrentItem, // preserve full original data
)
```

### 3.4 Deduplication Engine Model

```kotlin
// core/tracker/client/DeduplicationEngine.kt
class DeduplicationEngine(
    private val torrentMatcher: TorrentMatcher = TorrentMatcher(),
    private val httpContentMatcher: HttpContentMatcher = HttpContentMatcher(),
) {
    fun deduplicate(batches: List<SearchResultBatch>): List<UnifiedResult> { ... }
}

class TorrentMatcher {
    fun matchKey(item: TorrentItem): String? = item.infoHash ?: "${item.title}|${item.size}"
}

class HttpContentMatcher {
    fun matchKey(item: TorrentItem): String? = item.identifier ?: item.isbn ?: "${item.title}|${item.creator}"
}
```

## 4. Go API Domain Models

### 4.1 Provider Interface

```go
// internal/provider/provider.go
type Provider interface {
    ID() string
    DisplayName() string
    Capabilities() []ProviderCapability
    AuthType() AuthType
    Encoding() string

    Search(ctx context.Context, opts SearchOpts, cred Credentials) (*SearchResult, error)
    Browse(ctx context.Context, categoryID string, page int, cred Credentials) (*BrowseResult, error)
    GetForumTree(ctx context.Context, cred Credentials) (*ForumTree, error)
    GetTopic(ctx context.Context, id string, page int, cred Credentials) (*TopicResult, error)
    GetComments(ctx context.Context, id string, page int, cred Credentials) (*CommentsResult, error)
    AddComment(ctx context.Context, id, message string, cred Credentials) (bool, error)
    GetTorrent(ctx context.Context, id string, cred Credentials) (*TorrentResult, error)
    DownloadFile(ctx context.Context, id string, cred Credentials) (*FileDownload, error)
    GetFavorites(ctx context.Context, cred Credentials) (*FavoritesResult, error)
    AddFavorite(ctx context.Context, id string, cred Credentials) (bool, error)
    RemoveFavorite(ctx context.Context, id string, cred Credentials) (bool, error)
    CheckAuth(ctx context.Context, cred Credentials) (bool, error)
    Login(ctx context.Context, opts LoginOpts) (*LoginResult, error)
    FetchCaptcha(ctx context.Context, path string) (*CaptchaImage, error)
    HealthCheck(ctx context.Context) (bool, error)
}

type Credentials struct {
    Type         string // "none", "cookie", "token", "apikey", "password"
    CookieValue  string
    Token        string
    APIKey       string
    APISecret    string
    Username     string
    Password     string
}
```

### 4.2 Provider Registry

```go
// internal/provider/registry.go
type ProviderRegistry struct {
    providers map[string]Provider
    mu        sync.RWMutex
}

func (r *ProviderRegistry) Register(id string, p Provider) { ... }
func (r *ProviderRegistry) Get(id string) (Provider, bool) { ... }
func (r *ProviderRegistry) List() []Provider { ... }
```

## 5. Validation Rules

### Android

- `CredentialEntity.type` MUST be one of `"USERNAME_PASSWORD"`, `"TOKEN"`, `"API_KEY"`
- At least one encrypted field MUST be non-null based on type
- `ProviderConfigEntity.providerId` MUST match a registered `TrackerDescriptor.id`
- `ProviderConfigEntity.searchTimeoutMs` MUST be between 2000 and 60000
- `OfflineSearchCacheEntity.expiresAt` MUST be `createdAt + 86400000` (24 hours)
- `GutenbergCatalogEntity.bookId` MUST be unique

### Go API

- `provider_credentials.cred_type` MUST be one of `cookie`, `token`, `apikey`, `password`
- `provider_configs.display_order` MUST be non-negative
- `provider_configs.request_delay_ms` if set, MUST be between 0 and 30000
- `search_provider_selections.selected` defaults to `true`
- `forum_provider_selections.enabled` defaults to `true`
