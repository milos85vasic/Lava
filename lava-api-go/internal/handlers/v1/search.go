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
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
)

const searchTTL = 1 * time.Minute

const searchRouteTemplate = "/v1/{provider}/search"

// defaultSearchTimeout is the documented fallback for the server-side
// single-provider search deadline used by GetSearch (§6.R: no bare literal
// in the handler body — the deadline value is a named, documented constant
// and the production value is injected via config env LAVA_API_SEARCH_TIMEOUT,
// see internal/config.Config.SearchTimeout). It MUST stay strictly shorter
// than the Android client's OkHttp readTimeout (30s) so the response always
// arrives before the socket read times out; 18s leaves margin for the network
// round-trip on top of the upstream call. NewSearchHandler falls back to this
// constant only when Deps.SearchTimeout is unset (direct/test construction).
const defaultSearchTimeout = 18 * time.Second

type SearchHandler struct {
	cache         Cache
	searchTimeout time.Duration
}

func NewSearchHandler(deps *Deps) *SearchHandler {
	timeout := deps.SearchTimeout
	if timeout <= 0 {
		timeout = defaultSearchTimeout
	}
	return &SearchHandler{cache: deps.Cache, searchTimeout: timeout}
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

	// §6.Z / engine-side timeout fix (LVA-083 H2): bound the total search time
	// so the response always arrives before the Android client's OkHttp
	// readTimeout (30 s). Without this, a provider failover loop can stall for
	// perAttemptTimeout(8s) × N mirrors (YTS now has 5) ≈ 40 s > 30 s, causing a
	// SocketTimeoutException on the device ("no results"). The parent deadline
	// caps the TOTAL regardless of mirror count. The value is config-driven
	// (LAVA_API_SEARCH_TIMEOUT) with defaultSearchTimeout as the documented
	// fallback — never a bare literal here (§6.R).
	searchCtx, searchCancel := context.WithTimeout(c.Request.Context(), h.searchTimeout)
	defer searchCancel()
	result, err := p.Search(searchCtx, opts, creds)
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
	registry           *provider.ProviderRegistry
	perProviderTimeout time.Duration
}

// defaultMultiSearchProviderTimeout is the documented fallback for the
// per-provider deadline applied inside the SSE multi-search fan-out
// (GetMultiSearch). §6.R: no bare literal in the handler body — the deadline is
// a named, documented constant and the production value is injected via config
// env LAVA_API_MULTISEARCH_PROVIDER_TIMEOUT (see config.Config.
// MultiSearchProviderTimeout). It MUST stay strictly shorter than the Android
// client's OkHttp readTimeout (30s); 20s leaves margin for the network
// round-trip on top of the upstream call (§6.AK §6.4 reduced this from the prior
// bare 30s literal). NewMultiSearchHandler falls back to this constant only when
// the injected per-provider timeout is unset (direct/test construction).
const defaultMultiSearchProviderTimeout = 20 * time.Second

// NewMultiSearchHandler builds the SSE multi-search handler. perProviderTimeout
// is the config-driven (LAVA_API_MULTISEARCH_PROVIDER_TIMEOUT) deadline applied
// to each provider's upstream call; when <= 0 it falls back to
// defaultMultiSearchProviderTimeout (§6.R: the deadline flows config → handler,
// never a bare literal in GetMultiSearch).
func NewMultiSearchHandler(reg *provider.ProviderRegistry, perProviderTimeout time.Duration) *MultiSearchHandler {
	if perProviderTimeout <= 0 {
		perProviderTimeout = defaultMultiSearchProviderTimeout
	}
	return &MultiSearchHandler{registry: reg, perProviderTimeout: perProviderTimeout}
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
				// §6.AC: unknown provider id is high-value signal; record before SSE.
				observability.RecordNonFatal(c.Request.Context(), err, observability.NonFatalAttributes{
					observability.AttrFeature:      "search",
					observability.AttrOperation:    "multi_search_get_provider",
					observability.AttrEndpoint:     c.FullPath(),
					observability.AttrTrackerID:    pid,
					observability.AttrErrorClass:   "provider_not_found",
					observability.AttrErrorMessage: err.Error(),
				})
				// The user EXPLICITLY requested this provider id (or it came
				// from the auto-discovery list, which only ever yields
				// registered ids). An unknown id reaching here therefore means
				// the client asked for a provider that does not exist; surface
				// it as a provider_error SSE event and count it as failed
				// rather than silently dropping it (§6.AB: a silently-omitted
				// requested provider is a no-signal bluff — the client gets no
				// error and no results for the id it asked for, and the
				// stream_end aggregate hides the omission). The id is echoed so
				// the consumer can map the failure back to its request.
				failed++
				searched++
				errEvt := providerStreamStatus{
					ProviderID: pid,
					Error:      err.Error(),
				}
				data, _ := json.Marshal(errEvt)
				if err := streamEvent(w, sseEvent{Event: "provider_error", Data: string(data)}); err != nil {
					return false
				}
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

			ctx, cancel := context.WithTimeout(c.Request.Context(), h.perProviderTimeout)
			result, err := p.Search(ctx, opts, parseCredentials(c))
			cancel()

			if err != nil {
				// no-telemetry: SSE multi-provider streaming — each err is
				// wrapped into a `provider_error` SSE event sent to the
				// client (the user-visible signal). The error becomes
				// part of the stream, not background noise. Recording a
				// non-fatal here would double-report; the SSE consumer
				// IS the telemetry surface.
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
