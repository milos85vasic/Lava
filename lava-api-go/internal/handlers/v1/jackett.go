// Jackett sidecar search handler.
//
// The operator's decision (worklog): the Jackett sidecar is fronted by
// lava-api-go; the Android app NEVER talks to Jackett directly. This handler
// is the only door between the app and the sidecar. It accepts a search query
// + indexer id, calls jackett.Client.Search over Torznab, maps the parsed
// Torznab results into the SAME provider.SearchResult DTO every other search
// surface returns, and serves it as JSON.
//
// Constitutional alignment:
//   - §6.R: the Jackett base URL + api_key are NEVER literals here; they are
//     injected via config (read from env / the gitignored host volume). The
//     route is registered ONLY when the sidecar is configured + enabled, so
//     it is a no-op by default.
//   - §6.H: the api_key is a server-side secret carried inside the
//     jackett.Client; it never appears in the response shipped to the device.
//   - §6.AC: every error path records a non-fatal telemetry event.
package v1

import (
	"context"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
)

// jackettProviderID is the canonical provider id stamped onto the
// SearchResult the app receives, so the client can attribute results to the
// Jackett sidecar surface (distinct from the native rutracker/nnmclub/etc.
// providers).
const jackettProviderID = "jackett"

// jackettSearcher is the minimal slice of *jackett.Client the handler needs.
// Defining it as an interface keeps the handler honest under test: the test
// wires a REAL *jackett.Client pointed at a fake Torznab upstream (an httptest
// server serving the fixture XML), exercising the production Torznab request
// build + parse path end-to-end — only the network boundary is faked.
type jackettSearcher interface {
	Search(ctx context.Context, indexerID, query string) ([]jackett.Result, error)
}

// JackettHandler serves /jackett/search by proxying to the Jackett sidecar.
type JackettHandler struct {
	client         jackettSearcher
	defaultIndexer string
}

// NewJackettHandler builds the handler. defaultIndexer is used when a request
// omits the `indexer` query parameter.
func NewJackettHandler(client jackettSearcher, defaultIndexer string) *JackettHandler {
	if defaultIndexer == "" {
		defaultIndexer = jackett.IndexerAll
	}
	return &JackettHandler{client: client, defaultIndexer: defaultIndexer}
}

// GetSearch handles GET /jackett/search?q=<query>&indexer=<id>.
//
// Request  → query params: q (required), indexer (optional, defaults to the
//
//	configured default indexer / "all").
//
// Response → 200 application/json provider.SearchResult, or 400 (missing q) /
//
//	502 (sidecar error).
func (h *JackettHandler) GetSearch(c *gin.Context) {
	query := c.Query("q")
	if query == "" {
		observability.RecordWarning(c.Request.Context(), "jackett search missing query parameter", observability.NonFatalAttributes{
			observability.AttrFeature:   "jackett",
			observability.AttrOperation: "search",
			observability.AttrEndpoint:  "/jackett/search",
		})
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter 'q' is required"})
		return
	}

	indexer := c.Query("indexer")
	if indexer == "" {
		indexer = h.defaultIndexer
	}

	results, err := h.client.Search(c.Request.Context(), indexer, query)
	if err != nil {
		observability.RecordNonFatal(c.Request.Context(), err, observability.NonFatalAttributes{
			observability.AttrFeature:   "jackett",
			observability.AttrOperation: "search",
			observability.AttrEndpoint:  "/jackett/search",
			observability.AttrTrackerID: indexer,
		})
		c.JSON(http.StatusBadGateway, gin.H{"error": "jackett sidecar search failed"})
		return
	}

	out := mapJackettResults(indexer, results)
	writeJSON(c, http.StatusOK, out)
}

// mapJackettResults converts the parsed Torznab results into the
// provider.SearchResult DTO the Android client already consumes for every
// other search surface. Jackett is a flat (non-paginated) Torznab query, so
// Page/TotalPages are both 1.
func mapJackettResults(indexer string, results []jackett.Result) *provider.SearchResult {
	items := make([]provider.SearchItem, 0, len(results))
	for _, r := range results {
		item := provider.SearchItem{
			// GUID is the stable Torznab item identifier; fall back to the
			// title only if a feed omits the guid (rare, spec-non-conforming).
			ID:        firstNonEmpty(r.GUID, r.Title),
			Title:     r.Title,
			SizeBytes: r.Size,
			Size:      humanSize(r.Size),
			Category:  indexer,
			InfoHash:  r.Infohash,
		}
		// Seeders is -1 in the Result when unknown; only surface a real count.
		if r.Seeders >= 0 {
			item.Seeders = r.Seeders
		}
		// Map the download surface: a magnet enclosure populates MagnetLink;
		// a .torrent enclosure populates DownloadURL (the Jackett /dl/ proxy
		// link the app fetches through lava-api-go).
		if r.IsMagnetEnclosure() {
			item.MagnetLink = firstNonEmpty(r.MagnetURL, r.DownloadURL)
		} else {
			item.DownloadURL = r.DownloadURL
			// Some feeds carry a redundant magneturl attr alongside a .torrent
			// enclosure; surface it too so the client can prefer magnet.
			item.MagnetLink = r.MagnetURL
		}
		items = append(items, item)
	}
	return &provider.SearchResult{
		Provider:   jackettProviderID,
		Page:       1,
		TotalPages: 1,
		Results:    items,
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// humanSize renders a byte count as a compact human-readable string. Returns
// "" for a non-positive size so the omitempty JSON tag drops the field.
func humanSize(bytes int64) string {
	if bytes <= 0 {
		return ""
	}
	const unit = 1024
	if bytes < unit {
		return strconv.FormatInt(bytes, 10) + " B"
	}
	div, exp := int64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	value := float64(bytes) / float64(div)
	suffix := []string{"KB", "MB", "GB", "TB", "PB", "EB"}[exp]
	return strconv.FormatFloat(value, 'f', 2, 64) + " " + suffix
}
