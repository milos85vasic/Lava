package v1

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/provider"
)

const searchTTL = 1 * time.Minute

const searchRouteTemplate = "/v1/{provider}/search"

type SearchHandler struct {
	cache Cache
}

func NewSearchHandler(deps *Deps) *SearchHandler {
	return &SearchHandler{cache: deps.Cache}
}

func (h *SearchHandler) GetSearch(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)

	opts := provider.SearchOpts{}
	if v := c.Query("query"); v != "" {
		opts.Query = v
	}
	if v := c.Query("sort"); v != "" {
		opts.Sort = v
	}
	if v := c.Query("order"); v != "" {
		opts.Order = v
	}
	if v := c.Query("category"); v != "" {
		opts.Category = v
	}
	if pageStr := c.Query("page"); pageStr != "" {
		if page, err := strconv.Atoi(pageStr); err == nil {
			opts.Page = page
		}
	}

	key := cacheKey(c, http.MethodGet, searchRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.Search(c.Request.Context(), opts, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, searchTTL)
	c.Data(http.StatusOK, "application/json", body)
}

type sseEvent struct {
	Event string
	Data  string
}

func streamEvent(w io.Writer, evt sseEvent) error {
	if evt.Event != "" {
		if _, err := fmt.Fprintf(w, "event: %s\n", evt.Event); err != nil {
			return err
		}
	}
	if _, err := fmt.Fprintf(w, "data: %s\n\n", evt.Data); err != nil {
		return err
	}
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	return nil
}

type MultiSearchHandler struct {
	registry *provider.ProviderRegistry
}

func NewMultiSearchHandler(reg *provider.ProviderRegistry) *MultiSearchHandler {
	return &MultiSearchHandler{registry: reg}
}

type providerStreamStatus struct {
	ProviderID  string `json:"provider_id"`
	DisplayName string `json:"display_name"`
	ResultCount int    `json:"result_count"`
	Page        int    `json:"page"`
	TotalPages  int    `json:"total_pages"`
	Error       string `json:"error,omitempty"`
}

func (h *MultiSearchHandler) GetMultiSearch(c *gin.Context) {
	query := c.Query("q")
	if query == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter 'q' is required"})
		return
	}

	providerIDs := parseProviderList(c.Query("providers"))
	if len(providerIDs) == 0 {
		for _, id := range h.registry.IDs() {
			if h.registry.Supports(id, provider.CapSearch) {
				providerIDs = append(providerIDs, id)
			}
		}
	}
	if len(providerIDs) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "no search-capable providers available"})
		return
	}

	opts := provider.SearchOpts{Query: query}
	if v := c.Query("sort"); v != "" {
		opts.Sort = v
	}
	if v := c.Query("order"); v != "" {
		opts.Order = v
	}
	if v := c.Query("category"); v != "" {
		opts.Category = v
	}

	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	c.Header("X-Accel-Buffering", "no")

	totalProviders := len(providerIDs)
	searched := 0
	failed := 0
	totalResults := 0

	c.Stream(func(w io.Writer) bool {
		for _, pid := range providerIDs {
			p, err := h.registry.Get(pid)
			if err != nil {
				continue
			}

			startEvt := providerStreamStatus{
				ProviderID:  pid,
				DisplayName: p.DisplayName(),
			}
			data, _ := json.Marshal(startEvt)
			if err := streamEvent(w, sseEvent{Event: "provider_start", Data: string(data)}); err != nil {
				return false
			}

			ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
			result, err := p.Search(ctx, opts, parseCredentials(c))
			cancel()

			if err != nil {
				failed++
				searched++
				errEvt := providerStreamStatus{
					ProviderID:  pid,
					DisplayName: p.DisplayName(),
					Error:       err.Error(),
				}
				data, _ := json.Marshal(errEvt)
				if err := streamEvent(w, sseEvent{Event: "provider_error", Data: string(data)}); err != nil {
					return false
				}
				continue
			}

			pageData := map[string]interface{}{
				"provider_id":  pid,
				"display_name": p.DisplayName(),
				"items":        result.Results,
				"page":         result.Page,
				"total_pages":  result.TotalPages,
			}
			pageJSON, _ := json.Marshal(pageData)
			if err := streamEvent(w, sseEvent{Event: "results", Data: string(pageJSON)}); err != nil {
				return false
			}

			searched++
			totalResults += len(result.Results)

			doneEvt := providerStreamStatus{
				ProviderID:  pid,
				DisplayName: p.DisplayName(),
				ResultCount: len(result.Results),
				Page:        result.Page,
				TotalPages:  result.TotalPages,
			}
			data, _ = json.Marshal(doneEvt)
			if err := streamEvent(w, sseEvent{Event: "provider_done", Data: string(data)}); err != nil {
				return false
			}
		}

		endData := map[string]interface{}{
			"providers_searched": searched,
			"providers_failed":   failed,
			"total_results":      totalResults,
			"total_providers":    totalProviders,
		}
		endJSON, _ := json.Marshal(endData)
		if err := streamEvent(w, sseEvent{Event: "stream_end", Data: string(endJSON)}); err != nil {
			return false
		}
		return false
	})
}

func parseProviderList(raw string) []string {
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			result = append(result, p)
		}
	}
	return result
}
