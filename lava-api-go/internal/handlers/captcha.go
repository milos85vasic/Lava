// Package handlers — captcha.go implements GET /captcha/{path}, the
// rutracker.org captcha-image proxy. Mirrors the legacy Ktor route
// (proxy/.../routes/Auth.kt:40-48): the URL-safe Base64 path parameter
// encodes the upstream image URL; the handler hands it to
// rutracker.Client.FetchCaptcha which decodes it, fetches the bytes,
// and returns the upstream Content-Type alongside the payload.
//
// Wire shape (per api/openapi.yaml /paths/~1captcha~1{path}):
//
//   - Path: /captcha/{path}, where `path` is a URL-safe Base64-encoded
//     full upstream image URL. The handler MUST forward this to the
//     scraper VERBATIM (NOT URL-decoded by Gin's path-param logic and
//     then re-encoded) — the scraper owns the decode step.
//   - 200 → image bytes verbatim, with the upstream Content-Type
//     forwarded. When the upstream omits Content-Type, the handler
//     defaults to `image/jpeg` so the wire shape always declares a
//     media type (a missing/empty Content-Type would violate the
//     OpenAPI image/* contract).
//
// Cache policy: NO cache. Captcha images are session-bound — the
// upstream `cap_sid` rotates on every render, and serving a cached
// image after the SID has rotated would cause the user to solve a
// captcha that no longer matches the form-side `cap_sid` they would
// submit. Identical reasoning at the Ktor proxy.
//
// Error mapping: FetchCaptcha returns generic errors (not the
// rutracker package's typed sentinels). writeUpstreamError handles
// them via its default arm → 502 Bad Gateway. That matches the
// OpenAPI's 400/non-200 documentation: a missing/invalid path or
// upstream 404 surfaces as 502, which the Android client treats as a
// "captcha not yet available" non-fatal condition (the user retries).
package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// captchaRouteTemplate is documentation-grade — GET /captcha/{path}
// does not read or write the cache.
const captchaRouteTemplate = "/captcha/{path}"

// captchaDefaultContentType is the fallback Content-Type when the
// upstream omits one. Captcha images on rutracker auxiliary domains
// (static.t-ru.org, etc.) are typically JPEG.
const captchaDefaultContentType = "image/jpeg"

// CaptchaHandler owns GET /captcha/{path}.
type CaptchaHandler struct {
	scraper ScraperClient
}

// NewCaptchaHandler is the constructor injected with the shared Deps.
// Cache is intentionally NOT held — see the file-level comment.
func NewCaptchaHandler(deps *Deps) *CaptchaHandler {
	return &CaptchaHandler{scraper: deps.Scraper}
}

// GetCaptcha implements GET /captcha/{path}. Forwards the path
// parameter VERBATIM to the scraper (NOT pre-decoded — the scraper's
// DecodeCaptchaPath does that), streams the resulting bytes, and
// preserves the upstream Content-Type (defaulting to image/jpeg when
// the upstream omits one).
func (h *CaptchaHandler) GetCaptcha(c *gin.Context) {
	path := c.Param("path")

	img, err := h.scraper.FetchCaptcha(c.Request.Context(), path)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}

	contentType := img.ContentType
	if contentType == "" {
		contentType = captchaDefaultContentType
	}
	c.Data(http.StatusOK, contentType, img.Bytes)
}
