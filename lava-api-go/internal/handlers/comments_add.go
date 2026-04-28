// Package handlers — comments_add.go implements POST /comments/{id}/add,
// the seventh of the 13 rutracker routes and the FIRST handler in this
// package that mutates server-visible state. Per spec §6.1 it is
// therefore the first handler that performs cache invalidation.
//
// Wire shape (per api/openapi.yaml):
//
//   - Path: /comments/{id}/add (id = topic id).
//   - Body: the raw comment text. The Ktor proxy reads it via
//     call.receiveText(); we mirror that with c.GetRawData(). Any
//     Content-Type is accepted as long as the body bytes ARE the
//     message — the OpenAPI declares text/plain, but
//     application/x-www-form-urlencoded clients in the wild also work
//     because the body itself, not a parsed form field, is the message.
//   - Returns: a JSON boolean. True iff rutracker.org's response
//     contains the Russian success sentence "Сообщение было успешно
//     отправлено" (see internal/rutracker/comments_add.go).
//
// Cache invalidation (spec §6.1, Phase 7 task 7.4):
//
//	On any non-error outcome from the scraper — TRUE (accepted) OR
//	FALSE (silently rejected) — invalidate the cached read responses
//	for the same topic id across /topic/{id}, /topic2/{id}, and
//	/comments/{id}. The silent-reject path also invalidates: a
//	silently-rejected post may still have moved server state forward
//	(e.g. a flood-control counter), and serving the moment-stale view
//	is a worse default than a fresh refetch on the next GET.
//
//	Invalidation uses invalidateTopicCacheKeys (topic.go) which keys
//	by (method=GET, route, {id}, empty-query, realm). This matches
//	the cache-write keys the three GET handlers produce when called
//	with no query parameters. Paginated GETs (?page=N) hash to
//	different keys and are NOT invalidated — acceptable at the cost
//	of a brief stale window bounded by topicGroupTTL (5 minutes).
package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
)

// commentsAddRouteTemplate is the OpenAPI path template for this route.
// Documentation-grade: the route does not read from cache, so this
// constant is not used to compute a key. The three keys invalidated on
// success are owned by topic.go and use that file's templates.
const commentsAddRouteTemplate = "/comments/{id}/add"

// CommentsAddHandler owns the single comment-post route.
type CommentsAddHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewCommentsAddHandler is the constructor injected with the shared Deps.
func NewCommentsAddHandler(deps *Deps) *CommentsAddHandler {
	return &CommentsAddHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// AddComment implements POST /comments/{id}/add. Reads the raw request
// body as the comment message and forwards (id, message, cookie) to
// the rutracker scraper. The scraper returns (true, nil) on accepted,
// (false, nil) on silent-reject, and a sentinel error otherwise; the
// handler maps each outcome to the wire shape:
//
//	(true, nil)  → 200 OK + JSON `true`,  invalidate three cache keys
//	(false, nil) → 200 OK + JSON `false`, invalidate three cache keys
//	(_, ErrUnauthorized | ErrForbidden | ErrNotFound | ErrCircuitOpen | …)
//	             → routed via writeUpstreamError (no invalidation —
//	             upstream rejected the write so server state didn't
//	             change; invalidating would force unnecessary refetches)
func (h *CommentsAddHandler) AddComment(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")

	// Read the raw body verbatim. Per OpenAPI the body IS the message
	// regardless of Content-Type; c.GetRawData reads to EOF without
	// any form-decoding, matching Ktor's call.receiveText() behaviour.
	body, err := c.GetRawData()
	if err != nil {
		writeJSON(c, http.StatusBadRequest, gin.H{"error": "read body: " + err.Error()})
		return
	}

	ok, err := h.scraper.AddComment(c.Request.Context(), id, string(body), cookie)
	if err != nil {
		// writeUpstreamError handles the ErrUnauthorized → 401 mapping
		// for both the empty-cookie and missing-form-token branches in
		// rutracker.AddComment.
		writeUpstreamError(c, err)
		return
	}

	// Both ok==true and ok==false invalidate the three sibling read
	// caches for this topic id and realm: a silent-reject can still
	// have moved server state forward, and the next GET must see fresh
	// data either way. See invalidateTopicCacheKeys in topic.go for
	// the realm-scoping trade-off.
	invalidateTopicCacheKeys(c.Request.Context(), h.cache, id, realm)

	writeJSON(c, http.StatusOK, ok)
}
