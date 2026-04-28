// Package handlers — index.go implements GET / and GET /index, the
// thirteenth and final pair of rutracker routes (the OpenAPI spec
// declares them as two operations, but they share an implementation).
//
// Wire shape (per api/openapi.yaml /paths/~1 and /paths/~1index):
//
//   - GET / and GET /index → JSON boolean. The boolean is the result
//     of the upstream `checkAuthorized(token)` probe — true iff the
//     supplied Auth-Token corresponds to a logged-in rutracker session.
//   - Auth-Token is OPTIONAL. When absent or empty, the upstream is
//     queried anonymously and the response is typically `false`. This
//     is the deliberate divergence from POST /comments/{id}/add and
//     POST /favorites/add/{id} — the health probes WORK anonymously
//     where the state-mutating routes return 401 on empty cookie.
//
// Cache policy: NO cache. Spec §6 declares health probes as freshness-
// over-performance: the whole point of /index is to discover that the
// session has been revoked or the upstream went down, and serving a
// cached "logged in" state seconds after a logout would actively
// mislead the Android client into thinking it can issue authenticated
// requests when it cannot. Identical handler reasoning is applied at
// the Ktor proxy.
package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
)

// Per-route templates. Documentation-grade — neither route reads from
// or writes to the cache, so these constants do not feed cache.Key.
// Kept for parity with the cross-backend parity test in Phase 10 and
// to make the OpenAPI ↔ handler mapping greppable.
const (
	rootRouteTemplate  = "/"
	indexRouteTemplate = "/index"
)

// IndexHandler owns GET / and GET /index. Both routes resolve to the
// same handler method (GetIndex) — see Register in handlers.go.
type IndexHandler struct {
	scraper ScraperClient
}

// NewIndexHandler is the constructor injected with the shared Deps.
// Cache is intentionally NOT held: the handler must never read or
// write the cache (see file-level comment for the rationale).
func NewIndexHandler(deps *Deps) *IndexHandler {
	return &IndexHandler{scraper: deps.Scraper}
}

// GetIndex implements both GET / and GET /index. Forwards the upstream
// cookie (which may be empty) to the scraper's CheckAuthorised method
// and returns the JSON boolean it produces.
//
// Errors from CheckAuthorised flow through writeUpstreamError, which
// maps the rutracker package's sentinels (ErrCircuitOpen → 503; the
// 4xx sentinels are not produced by this scraper method but the
// mapping handles them for free) and falls back to 502 for anything
// else (e.g. a /index.php 500 response surfaces as a wrapped error).
func (h *IndexHandler) GetIndex(c *gin.Context) {
	cookie := auth.UpstreamCookie(c.Request)

	ok, err := h.scraper.CheckAuthorised(c.Request.Context(), cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}

	writeJSON(c, http.StatusOK, ok)
}
