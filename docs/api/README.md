# Lava REST API Reference

The Lava JSON API is served by the **lava-api-go** service (Go / Gin), the
sole API implementation since the legacy Kotlin/Ktor proxy was removed
(`build_and_release.sh:16`, `docker-compose.yml:2`). It proxies and structures
content from `rutracker.org` (and, on the `/v1/{provider}/...` surface,
additional providers) and applies response caching, audit logging, and per-IP
rate limiting at the API tier.

This page is derived from:

- `lava-api-go/api/openapi.yaml` — the spec-first wire contract (`info.version: 2.0.0`),
  source of truth for the **legacy / unprefixed** route group and every DTO schema.
- `lava-api-go/internal/router/router.go` — the single production Gin engine
  construction (middleware chain + route registration).
- `lava-api-go/internal/handlers/handlers.go` — the unprefixed handler registration.
- `lava-api-go/internal/handlers/v1/handlers.go` — the `/v1/{provider}/...` route group.
- `lava-api-go/internal/auth/middleware.go` — the `Lava-Auth` HMAC gate.

> **Scope note.** The OpenAPI document at `api/openapi.yaml` describes only the
> **unprefixed** route group and was authored against the legacy Ktor proxy's
> wire shape (the two backends were required to be byte-equivalent; see
> `tests/parity/`). The router additionally mounts a **`/v1/{provider}/...`**
> provider-agnostic group (`router.go:102-105`) and the **`/health` + `/ready`**
> probes (`router.go:69-70`) that are **not** in `openapi.yaml`. Both surfaces are
> documented below; where a fact is not backed by a source file it is marked
> `UNCONFIRMED:`.

---

## 1. Servers and transport

From `api/openapi.yaml` `servers:` and `docker-compose.yml`:

| URL | Transport | Notes |
| --- | --- | --- |
| `https://localhost:8443` | HTTP/3 (QUIC) + HTTP/2-over-TLS | Default Go-service listener (`LAVA_API_LISTEN: ":8443"`, `docker-compose.yml:66`). The container exposes `8443/udp` (QUIC) and `8443/tcp` (`Dockerfile:52`). |

mDNS service type for the Go service is `_lava-api._tcp` (`api/openapi.yaml`
`info.description`; advertised via `discovery.Announce(...)`, `cmd/lava-api-go/main.go:189`).

A separate metrics listener binds `:9091` (`LAVA_API_METRICS_LISTEN`,
`docker-compose.yml:73`) — Prometheus scrape endpoint, not part of this API.

---

## 2. Authentication

There are **two distinct authentication concepts** in this API. Do not
conflate them.

### 2.1 `Lava-Auth` HMAC client-attestation header (gates the whole API)

Source: `lava-api-go/internal/auth/middleware.go`, wired in `router.go:82-85`.

Every request to the **business** routes (everything except `/health` and
`/ready`) must carry a client-attestation header. The header **name is not
hardcoded** — it is read from `cfg.AuthFieldName` (`LAVA_AUTH_FIELD_NAME`,
`docker-compose.yml:77`), per the §6.R no-hardcoding mandate.

The header **value** is:

```
base64( UUID-blob )           # the raw bytes the client sends on the wire
```

The server decodes the base64 (`middleware.go:61`), then computes
`hex( HMAC-SHA256( blob, LAVA_AUTH_HMAC_SECRET ) )` (`hashUUIDBlob`,
`middleware.go:118-122`) and matches the result against the configured
**active** and **retired** client allowlists (`LAVA_AUTH_ACTIVE_CLIENTS` /
`LAVA_AUTH_RETIRED_CLIENTS`, `docker-compose.yml:81-82`). The lookup is
constant-time (`constantTimeMapLookup`, `middleware.go:101`).

Outcomes (`middleware.go:55-94`):

| Condition | HTTP status | Body |
| --- | --- | --- |
| Header absent (`hdr == ""`) | `401 Unauthorized` | `{"error":"unauthorized"}` |
| Header present but not valid base64 / empty blob | `401 Unauthorized` (backoff counter advances) | `{"error":"unauthorized"}` |
| Hash in **active** allowlist | passes to handler | — (sets `client_name`, resets backoff) |
| Hash in **retired** allowlist | `426 Upgrade Required` (no backoff advance) | `{"error":"client_version_unsupported","client_name":...,"min_supported_version_name":...,"min_supported_version_code":...}` |
| Unknown hash | `401 Unauthorized` (backoff counter advances) | `{"error":"unauthorized"}` |

A backoff/ladder middleware (`auth.NewBackoffMiddleware`, `router.go:83`) runs
**in front** of the auth middleware; IPs that accumulate failures are
short-circuited with `429` + `Retry-After` before the auth middleware can
advance the counter again (`router.go:78-84`).

The minimum supported client version returned in the `426` body comes from
`LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME` / `_CODE`
(`docker-compose.yml:78-79`, defaults `1.2.6` / `1026`).

### 2.2 `Auth-Token` upstream session header (per-request rutracker cookie)

Source: `api/openapi.yaml` `components.parameters.AuthToken`.

`Auth-Token` is an **optional** request header carrying the opaque
rutracker session identifier, forwarded as a cookie to the upstream tracker.
Absent/empty is permitted; routes that genuinely require an authenticated
upstream session (favorites, comment posting, non-public downloads) will
return an upstream error or empty result when it is missing.

> `Auth-Token` is the *upstream* credential; `Lava-Auth` is the *API-tier*
> client attestation. A request needs `Lava-Auth` to reach a handler at all,
> and `Auth-Token` only to satisfy rutracker for user-scoped routes.

---

## 3. Endpoint catalogue — unprefixed routes

Registered in `internal/handlers/handlers.go:105-139`; documented in
`api/openapi.yaml` `paths:`. All routes accept the optional `Auth-Token`
header and require `Lava-Auth` (§2.1). Status codes below are from the
OpenAPI `responses:` blocks plus the handler error mapper
(`writeUpstreamError`, `handlers.go:177-196`).

The upstream-error mapping (`handlers.go:184-195`) is:
`ErrNotFound → 404`, `ErrForbidden → 403`, `ErrUnauthorized → 401`,
`ErrCircuitOpen → 503`, anything else → `502`. Error response bodies are
`{}` for parity with the Ktor proxy's `StatusPages` plugin (`handlers.go:178-183`).

| Method | Path | operationId | Purpose | Success body | Notable statuses |
| --- | --- | --- | --- | --- | --- |
| GET | `/` | `getRoot` | Health probe — returns `checkAuthorized(token)` | JSON boolean | 200, 500 |
| GET | `/index` | `getIndex` | Alias of `/` | JSON boolean | 200, 500 |
| POST | `/login` | `postLogin` | Log in to rutracker | `AuthResponseDto` | 200, 400 |
| GET | `/forum` | `getForum` | Top-level forum tree | `ForumDto` | 200, 500 |
| GET | `/forum/{id}` | `getCategoryPage` | Paginated category page | `CategoryPageDto` | 200, 404 |
| GET | `/search` | `getSearch` | Search torrents | `SearchPageDto` | 200, 400 |
| GET | `/topic/{id}` | `getTopic` | Topic page (polymorphic) | `ForumTopicDto` | 200, 404 |
| GET | `/topic2/{id}` | `getTopicPage` | Topic page (modern shape) | `TopicPageDto` | 200, 404 |
| GET | `/comments/{id}` | `getCommentsPage` | Paginated comments | `CommentsPageDto` | 200, 404 |
| POST | `/comments/{id}/add` | `postComment` | Submit a comment | JSON boolean | 200 |
| GET | `/torrent/{id}` | `getTorrent` | Torrent metadata | `TorrentDto` | 200, 404 |
| GET | `/download/{id}` | `getDownload` | Download `.torrent` (binary) | `application/octet-stream` | 200, 404 |
| GET | `/captcha/{path}` | `getCaptcha` | Fetch captcha image | image bytes | 200, 400 |
| GET | `/favorites` | `getFavorites` | User's favorited topics | `FavoritesDto` | 200 |
| POST | `/favorites/add/{id}` | `postFavoritesAdd` | Add to favorites | JSON boolean | 200 |
| POST | `/favorites/remove/{id}` | `postFavoritesRemove` | Remove from favorites | JSON boolean | 200 |

### Per-endpoint parameters

(From `api/openapi.yaml` `paths:`.)

- **`GET /` , `GET /index`** — header `Auth-Token` (optional). Returns a JSON
  boolean from `NetworkApi.checkAuthorized(token)`; absent/empty token
  typically yields `false`.
- **`POST /login`** — `application/x-www-form-urlencoded` body. Required:
  `username`, `password`. Optional captcha fields: `cap_sid`, `cap_code`,
  `cap_val`. Never cached. Response is a polymorphic `AuthResponseDto`
  (discriminator `type`: `Success` | `WrongCredits` | `CaptchaRequired` |
  `ServiceUnavailable`). `400` when required form params are missing.
- **`GET /forum`** — header `Auth-Token` (optional, not consumed by the
  legacy Ktor handler).
- **`GET /forum/{id}`** — path `id` (rutracker category id); query `page`
  (1-based int32, omitted → first page). `404` for unknown id.
- **`GET /search`** — query (all optional): `query`, `categories`
  (comma-separated ids), `author`, `authorId`, `sort` (`SearchSortTypeDto`:
  `Date`/`Title`/`Downloaded`/`Seeds`/`Leeches`/`Size`), `order`
  (`SearchSortOrderDto`: `Ascending`/`Descending`), `period`
  (`SearchPeriodDto`: `AllTime`/`Today`/`LastThreeDays`/`LastWeek`/
  `LastTwoWeeks`/`LastMonth`), `page` (1-based int32). `400` on invalid enum
  constant. Enum values must match the Kotlin enum NAMES exactly.
- **`GET /topic/{id}` , `GET /topic2/{id}` , `GET /comments/{id}`** — path
  `id`; query `page` (1-based int32). `404` for unknown id. `/topic` returns
  the polymorphic `ForumTopicDto`; `/topic2` returns the simpler
  `TopicPageDto`; `/comments` returns `CommentsPageDto`.
- **`POST /comments/{id}/add`** — path `id`; body is raw comment text
  (`text/plain`, read via `call.receiveText()`). `Auth-Token` required
  (anonymous posting rejected by rutracker). Returns JSON boolean.
- **`GET /torrent/{id}`** — path `id`. `404` unknown.
- **`GET /download/{id}`** — path `id`; `Auth-Token` required for non-public
  torrents. Streams the `.torrent` binary; sets `Content-Disposition`
  (e.g. `attachment; filename="rutracker_<id>.torrent"`) and `Content-Type`
  (typically `application/x-bittorrent`). Never cached. `404` unknown/refused.
- **`GET /captcha/{path}`** — path `path` is the URL-safe Base64 encoding of
  the upstream captcha image URL; decoded server-side and fetched verbatim.
  `400` if not valid URL-safe Base64.
- **`GET /favorites`** — `Auth-Token` required (without it rutracker returns
  an empty bookmarks page).
- **`POST /favorites/add/{id}` , `POST /favorites/remove/{id}`** — path `id`;
  `Auth-Token` required. Returns JSON boolean. Idempotent in practice.

---

## 4. Endpoint catalogue — `/v1/{provider}/...` provider-agnostic routes

Registered in `internal/handlers/v1/handlers.go:44-75` under the Gin group
`/v1/:provider` (`router.go:102`). These mirror the unprefixed routes but are
parameterised by a provider id resolved from the path (provider middleware,
`internal/middleware/provider.go` per the package doc at
`handlers/v1/handlers.go:5-6`). Registered providers (`cmd/lava-api-go/main.go:132-137`):
`rutracker`, `nnmclub`, `kinozal`, `archiveorg` (Internet Archive), `gutenberg`.

> `UNCONFIRMED:` these `/v1/{provider}/...` routes are not described in
> `api/openapi.yaml`; the request/response shapes are not re-asserted here
> beyond the route templates below. Read the per-handler files for the wire
> shape of each.

| Method | Path | Route-template constant | Source |
| --- | --- | --- | --- |
| GET | `/v1/{provider}/search` | — | `handlers/v1/search.go` |
| GET | `/v1/{provider}/browse/{id}` | `/v1/{provider}/browse/{id}` | `handlers/v1/browse.go:17` |
| GET | `/v1/{provider}/forum` | `/v1/{provider}/forum` | `handlers/v1/forum.go:16` |
| GET | `/v1/{provider}/topic/{id}` | — | `handlers/v1/topic.go` |
| GET | `/v1/{provider}/torrent/{id}` | — | `handlers/v1/torrent.go` |
| GET | `/v1/{provider}/download/{id}` | — | `handlers/v1/torrent.go` |
| GET | `/v1/{provider}/comments/{id}` | `/v1/{provider}/comments/{id}` | `handlers/v1/comments.go:17` |
| POST | `/v1/{provider}/comments/{id}/add` | `/v1/{provider}/comments/{id}/add` | `handlers/v1/comments.go:18` |
| GET | `/v1/{provider}/favorites` | `/v1/{provider}/favorites` | `handlers/v1/favorites.go:16` |
| POST | `/v1/{provider}/favorites/add/{id}` | — | `handlers/v1/favorites.go` |
| POST | `/v1/{provider}/favorites/remove/{id}` | — | `handlers/v1/favorites.go` |
| POST | `/v1/{provider}/login` | — | `handlers/v1/login.go` |
| GET | `/v1/{provider}/captcha/{path}` | `/v1/{provider}/captcha/{path}` | `handlers/v1/captcha.go:9` |

Provider error sentinels map to HTTP statuses via `writeProviderError`
(`handlers/v1/handlers.go:121-128+`): `ErrNotFound → 404`,
`ErrForbidden → 403`, `ErrUnauthorized → 401` (read the file for the full set).

---

## 5. Liveness / readiness probes (not gated by `Lava-Auth`)

Registered **before** the auth middleware (`router.go:69-70`) so orchestrator
probes (the podman `HEALTHCHECK` via the `healthprobe` binary, and the
distribute scripts' health wait) can reach them without a `Lava-Auth` header.

| Method | Path | Handler | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | `observability.LivenessHandler()` | Liveness — process is up |
| GET | `/ready` | `observability.ReadinessHandler(...)` | Readiness — storage probe (`observability.ReadinessProbe(storeReady)`, `main.go:170`) |

---

## 6. Schemas (DTOs)

All DTO schemas are defined in `api/openapi.yaml` `components.schemas`.
Sealed-class hierarchies are encoded by kotlinx.serialization with the default
discriminator field `type`, whose value is the `@SerialName` of the concrete
subclass; the spec mirrors this with `oneOf` + `discriminator`. Key
polymorphic families:

- **`AuthResponseDto`** — `Success` (carries `UserDto`) | `WrongCredits`
  (optional `CaptchaDto`) | `CaptchaRequired` (optional `CaptchaDto`) |
  `ServiceUnavailable` (carries `reason`; see note below).
- **`ForumTopicDto`** — `Topic` | `Torrent` | `CommentsPage`.
- **`PostElementDto`** — 19 variants (`Text`, `Box`, `Align`, `Size`,
  `Color`, `Bold`, `Italic`, `Underscore`, `Crossed`, `Quote`, `Code`,
  `Spoiler`, `Image`, `ImageAligned`, `Link`, `List`, `Hr`, `Br`, `PostBr`)
  — the rich-text post-content tree.
- **`ColorValue`** — `Hex` | `Name`.

> **`AuthResponseDtoServiceUnavailable`** is an Android-client variant. Per the
> spec's own note (`openapi.yaml:166-181`), the lava-api-go Go server does
> **not** currently emit it — its rutracker scraper returns structured
> `Success | WrongCredits | CaptchaRequired` only. The variant exists in the
> spec so the Android Kotlin SDK wire shape stays forward-compatible.

Enumerations: `TorrentStatusDto`, `SearchPeriodDto`, `SearchSortOrderDto`,
`SearchSortTypeDto`. Object DTOs: `UserDto`, `CaptchaDto`, `CategoryDto`,
`SectionDto`, `ForumDto`, `CategoryPageDto`, `AuthorDto`, `PostDto`,
`TorrentDescriptionDto`, `TorrentDataDto`, `TopicPageDto`,
`TopicPageCommentsDto`, `SearchPageDto`, `FavoritesDto`, `ProfileDto`,
`Error`. See `api/openapi.yaml` for the field-level shape of each.

The canonical, browsable form of these schemas is the OpenAPI document itself;
the `dev-docs` compose profile serves it via Swagger UI at
`http://127.0.0.1:8081` (`docker-compose.yml:156-165`).

---

## 7. Caching, auditing, rate limiting

- **Response caching** — handlers compute a deterministic cache key against the
  OpenAPI route-template form (e.g. `/forum/{id}`, not the Gin `:id` form;
  `handlers.go:30-34`) and read-through / write-through a Postgres-backed cache
  (`lava_api.response_cache`; see [`docs/db/schema.md`](../db/schema.md)).
  `/login`, `/download/{id}`, and captcha are never cached (per `openapi.yaml`).
- **Request audit** — `lava_api.request_audit` records method/path/IP/
  auth-realm-hash/upstream-status/cache-outcome per request.
- **Rate limiting** — per-IP backoff ladder in front of auth (`router.go:83`);
  route-class token buckets persisted in `lava_api.rate_limit_bucket`.

---

## 8. Cross-references

- OpenAPI source of truth: [`lava-api-go/api/openapi.yaml`](../../lava-api-go/api/openapi.yaml)
- Router construction: [`lava-api-go/internal/router/router.go`](../../lava-api-go/internal/router/router.go)
- Unprefixed handlers: [`lava-api-go/internal/handlers/`](../../lava-api-go/internal/handlers/)
- Provider routes: [`lava-api-go/internal/handlers/v1/`](../../lava-api-go/internal/handlers/v1/)
- Auth middleware: [`lava-api-go/internal/auth/middleware.go`](../../lava-api-go/internal/auth/middleware.go)
- Database schema: [`docs/db/schema.md`](../db/schema.md)
- Deployment: [`docs/deployment/README.md`](../deployment/README.md)
