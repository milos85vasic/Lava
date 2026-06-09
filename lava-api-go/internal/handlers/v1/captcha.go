package v1

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

const captchaRouteTemplate = "/v1/{provider}/captcha/{path}"

// captchaFallbackContentType is served only when the upstream omits a
// Content-Type. It matches the legacy /captcha/{path} handler's fallback
// (handlers/captcha.go). It MUST be a generic image/* type so strict client
// decoders attempt image rendering rather than treating the bytes as an opaque
// download.
const captchaFallbackContentType = "image/jpeg"

type CaptchaHandler struct{}

func NewCaptchaHandler(deps *Deps) *CaptchaHandler {
	return &CaptchaHandler{}
}

func (h *CaptchaHandler) GetCaptcha(c *gin.Context) {
	p := currentProvider(c)
	path := c.Param("path")

	result, err := p.FetchCaptcha(c.Request.Context(), path)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	// LVA-026: serve the bytes under the UPSTREAM Content-Type the provider
	// reported, not a hardcoded image/png. rutracker serves captchas as
	// image/jpeg or image/gif; mislabelling them PNG makes strict client
	// decoders reject the image and the user can never solve the captcha.
	// Fall back to a generic image/* type only when the upstream omitted one.
	contentType := result.ContentType
	if contentType == "" {
		contentType = captchaFallbackContentType
	}
	c.Data(http.StatusOK, contentType, result.Data)
}
