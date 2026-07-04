package v1

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

const torrentTTL = 1 * time.Minute

const torrentRouteTemplate = "/v1/{provider}/torrent/{id}"
const downloadRouteTemplate = "/v1/{provider}/download/{id}"

type TorrentHandler struct {
	cache Cache
}

func NewTorrentHandler(deps *Deps) *TorrentHandler {
	return &TorrentHandler{cache: deps.Cache}
}

func (h *TorrentHandler) GetTorrent(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := strings.TrimPrefix(c.Param("id"), "/")

	key := cacheKey(c, http.MethodGet, torrentRouteTemplate, map[string]string{"id": id}, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetTorrent(c.Request.Context(), id, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, torrentTTL)
	c.Data(http.StatusOK, "application/json", body)
}

func (h *TorrentHandler) GetDownload(c *gin.Context) {
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := strings.TrimPrefix(c.Param("id"), "/")

	result, err := p.DownloadFile(c.Request.Context(), id, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	// Serve the raw bytes with the provider-supplied content type (falling back
	// to a generic binary type when the provider left it blank — e.g. archiveorg
	// fills Filename only) plus a Content-Disposition so the client can name the
	// saved artifact. HTTP-file providers (gutenberg, archiveorg) reach this
	// handler via the CapHTTPDownload-gated /http-download/:id route; torrent
	// providers reach it via /download/:id — both deliver Provider.DownloadFile
	// output, so the header applies uniformly.
	contentType := result.ContentType
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if result.Filename != "" {
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%q", result.Filename))
	}
	c.Data(http.StatusOK, contentType, result.Body)
}
