package v1

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

const captchaRouteTemplate = "/v1/{provider}/captcha/{path}"

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
	c.Data(http.StatusOK, "image/png", result.Data)
}
