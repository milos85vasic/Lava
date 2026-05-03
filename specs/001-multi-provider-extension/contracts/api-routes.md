# API Route Contract: Multi-Provider Extension

**Version**: v1  
**Base URL**: `https://<host>:8443/v1`  
**Transport**: HTTP/3 (QUIC) primary, HTTP/2-over-TLS fallback

## Route Structure

All routes are prefixed with `/v1/{provider}` where `{provider}` is one of: `rutracker`, `rutor`, `nnmclub`, `kinozal`, `archiveorg`, `gutenberg`.

### Authentication

**Header**: `Auth-Token: <token>`

**Format**:
- New format: `provider_id:credential_type:credential_value`
- Examples:
  - `rutracker:cookie:bb_session=abc123`
  - `archiveorg:apikey:IA-S3-Access-Key=xyz`
  - `gutenberg:none:`
- Backward compatibility: Bare tokens (no colons) are treated as RuTracker cookie credentials.

### Routes

#### Search

```
GET /v1/{provider}/search?q={query}&page={page}&sort={sort}&order={order}&category={category}
```

**Query Parameters**:
- `q` (string, required): Search query
- `page` (int, default: 0): Page number
- `sort` (string, enum: `created`, `seeders`, `size`, `title`): Sort field
- `order` (string, enum: `desc`, `asc`): Sort direction
- `category` (string, optional): Category/filter ID

**Response** (200 OK):
```json
{
  "provider": "nnmclub",
  "page": 0,
  "totalPages": 10,
  "results": [
    {
      "id": "12345",
      "title": "Ubuntu 22.04 Desktop",
      "size": "3.5 GB",
      "sizeBytes": 3758096384,
      "seeders": 150,
      "leechers": 12,
      "date": "2026-04-15",
      "category": "Software",
      "downloadUrl": "https://nnmclub.to/forum/download.php?id=12345",
      "magnetLink": "magnet:?xt=urn:btih:..."
    }
  ]
}
```

**Error Responses**:
- `401 Unauthorized`: Invalid or missing credentials for providers that require auth
- `404 Not Found`: Provider not found in registry
- `429 Too Many Requests`: Rate limit exceeded (includes `Retry-After` header)
- `503 Service Unavailable`: Provider mirrors all unhealthy

#### Browse / Forum Tree

```
GET /v1/{provider}/forum
```

**Response** (200 OK):
```json
{
  "provider": "rutracker",
  "categories": [
    {
      "id": "1",
      "name": "Movies",
      "subcategories": [
        { "id": "101", "name": "Action" },
        { "id": "102", "name": "Comedy" }
      ]
    }
  ]
}
```

#### Topic Detail

```
GET /v1/{provider}/topic/{id}?page={page}
```

**Response** (200 OK):
```json
{
  "provider": "kinozal",
  "id": "45678",
  "title": "Inception (2010)",
  "originalTitle": "Inception",
  "year": 2010,
  "posterUrl": "https://...",
  "description": "A thief who steals corporate secrets...",
  "imdbUrl": "https://imdb.com/title/tt1375666",
  "files": [
    { "name": "Inception.2010.1080p.mkv", "size": "15.2 GB" }
  ],
  "comments": 45,
  "magnetLink": "magnet:?xt=urn:btih:...",
  "downloadUrl": "https://dl.kinozal.tv/download.php?id=45678"
}
```

#### Download

```
GET /v1/{provider}/download/{id}
```

**Response**: Binary file stream with `Content-Disposition: attachment` header.

#### Login

```
POST /v1/{provider}/login
Content-Type: application/x-www-form-urlencoded
```

**Body**:
- For form login: `username={user}&password={pass}`
- For captcha login: `username={user}&password={pass}&captcha_code={code}&captcha_sid={sid}`

**Response** (200 OK):
```json
{
  "success": true,
  "authToken": "rutracker:cookie:bb_session=abc123",
  "expiresAt": "2026-06-01T00:00:00Z"
}
```

#### Health Check

```
GET /v1/{provider}/health
```

**Response** (200 OK):
```json
{
  "provider": "archiveorg",
  "healthy": true,
  "responseTimeMs": 245
}
```

### Provider Not Found

If `{provider}` is not registered:

**Response** (404 Not Found):
```json
{
  "error": "Provider not found",
  "availableProviders": ["rutracker", "rutor", "nnmclub", "kinozal", "archiveorg", "gutenberg"]
}
```
