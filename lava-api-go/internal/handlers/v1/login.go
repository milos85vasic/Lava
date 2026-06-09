package v1

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

const loginRouteTemplate = "/v1/{provider}/login"

type LoginHandler struct{}

func NewLoginHandler(deps *Deps) *LoginHandler {
	return &LoginHandler{}
}

func (h *LoginHandler) PostLogin(c *gin.Context) {
	p := currentProvider(c)

	var req struct {
		Username    string `json:"username"`
		Password    string `json:"password"`
		CaptchaCode string `json:"captchaCode,omitempty"`
		// CaptchaName is the dynamic form-field NAME rutracker delivered as
		// CaptchaDto.Code (e.g. "cap_code_<sid>") in the CaptchaRequired
		// response. The client echoes it back so the answer is submitted under
		// the correct, rotating field name (LVA-025). Empty for providers with
		// a fixed captcha field name.
		CaptchaName string `json:"captchaName,omitempty"`
		CaptchaSID  string `json:"captchaSid,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeJSON(c, http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}

	result, err := p.Login(c.Request.Context(), provider.LoginOpts{
		Username:    req.Username,
		Password:    req.Password,
		CaptchaCode: req.CaptchaCode,
		CaptchaName: req.CaptchaName,
		CaptchaSID:  req.CaptchaSID,
	})
	if err != nil {
		writeProviderError(c, err)
		return
	}
	writeJSON(c, http.StatusOK, result)
}
