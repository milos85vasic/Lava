// Package handlers — torrent.go implements GET /torrent/{id} and
// GET /download/{id}, the eighth and ninth of the 13 rutracker routes.
// Mirrors the Phase 7 task 7.1 forum-handler shape for the metadata
// route; the binary-download route is deliberately the FIRST handler
// in this package that does NOT cache its response.
//
// Two routes:
//
//   - GET /torrent/{id}  → JSON ForumTopicDtoTorrent (alias TorrentDto).
//     Long TTL (1 hour) per spec §6: torrent metadata is stable for
//     hours.
//   - GET /download/{id} → binary .torrent stream. NEVER cached at the
//     API tier per spec §6: storing potentially-large binary payloads
//     across realms is wasteful, and the upstream Content-Disposition
//     filename and Content-Type headers MUST be preserved verbatim from
//     the upstream response on every fetch.
//
// The /download/{id} handler forwards the upstream Content-Disposition
// header (commonly `attachment; filename="rutracker_<id>.torrent"`)
// and Content-Type (typically `application/x-bittorrent`). When the
// upstream Content-Type is absent, the handler defaults to
// `application/x-bittorrent` so a misbehaving upstream cannot cause
// the API to return an empty Content-Type — the OpenAPI declares the
// 200 response media type as application/octet-stream / binary, and a
// missing or empty Content-Type would violate the contract.
//
// Empty cookie on /download/{id} surfaces as ErrUnauthorized → 401 via
// writeUpstreamError. The check happens inside the scraper
// (rutracker.GetTorrentFile short-circuits on cookie == "") so no
// upstream traffic is generated for anonymous download attempts.
package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

// torrentMetadataTTL pins the cache TTL for GET /torrent/{id} per spec
// §6 ("hours" — torrent metadata changes slowly: tags / size / seeders
// drift much more slowly than topic comments). One hour is the floor of
// that range and matches the Ktor proxy's behaviour.
const torrentMetadataTTL = 1 * time.Hour

// Per-route cache-key route templates. These MUST match the OpenAPI
// path templates in api/openapi.yaml — the cross-backend parity test
// in Phase 10 will fail if they diverge.
const (
	torrentRouteTemplate  = "/torrent/{id}"
	downloadRouteTemplate = "/download/{id}"
)

// TorrentHandler owns the GET /torrent/{id} (JSON metadata) and
// GET /download/{id} (binary stream) routes.
type TorrentHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewTorrentHandler is the constructor injected with the shared Deps.
func NewTorrentHandler(deps *Deps) *TorrentHandler {
	return &TorrentHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// GetTorrent implements GET /torrent/{id}. Returns the JSON
// ForumTopicDtoTorrent (alias TorrentDto). Cached for torrentMetadataTTL
// keyed on (method, route, {id}, query, realm) — same shape as topic.go.
func (h *TorrentHandler) GetTorrent(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")

	pathVars := map[string]string{"id": id}
	key := cache.Key(http.MethodGet, torrentRouteTemplate, pathVars, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	torrent, err := h.scraper.GetTorrent(c.Request.Context(), id, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(torrent)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal torrent: " + err.Error()})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, torrentMetadataTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// GetDownload implements GET /download/{id}. Streams the binary
// .torrent payload. NEVER touches the cache (per spec §6: "never cached
// at the API tier"). The upstream Content-Disposition and Content-Type
// headers are forwarded verbatim to the response; an absent upstream
// Content-Type defaults to `application/x-bittorrent` so the wire
// shape always declares its media type.
//
// Empty cookie → rutracker.GetTorrentFile returns ErrUnauthorized →
// writeUpstreamError → 401. No upstream HTTP traffic is generated for
// anonymous download attempts (the scraper short-circuits the cookie
// check before issuing a fetch).
func (h *TorrentHandler) GetDownload(c *gin.Context) {
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")

	file, err := h.scraper.GetTorrentFile(c.Request.Context(), id, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}

	if file.ContentDisposition != "" {
		c.Header("Content-Disposition", file.ContentDisposition)
	}
	contentType := file.ContentType
	if contentType == "" {
		// Sensible default if the upstream omits Content-Type. The
		// OpenAPI declares the 200 response as application/octet-stream
		// with binary format; application/x-bittorrent is the more
		// specific honest answer for a .torrent payload.
		contentType = "application/x-bittorrent"
	}
	c.Data(http.StatusOK, contentType, file.Bytes)
}
