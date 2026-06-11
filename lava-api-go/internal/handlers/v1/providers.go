package v1

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// providerDTO is the wire shape of one entry in the GET /providers catalogue.
// It mirrors the Android client's ProviderDescriptorDto so the client can
// populate its provider list + per-provider auth UI dynamically (dynamic
// provider discovery, spec 2026-06-11). Capability + auth strings are the
// provider package's own string-typed enum values (e.g. "SEARCH", "NONE"),
// which the client maps tolerantly into TrackerCapability/AuthType.
type providerDTO struct {
	ID                string   `json:"id"`
	DisplayName       string   `json:"displayName"`
	Kind              string   `json:"kind"`
	Indexer           string   `json:"indexer,omitempty"`
	Capabilities      []string `json:"capabilities"`
	AuthType          string   `json:"authType"`
	Encoding          string   `json:"encoding"`
	BaseURLs          []string `json:"baseUrls"`
	SupportsAnonymous bool     `json:"supportsAnonymous"`
}

type providersResponse struct {
	Providers []providerDTO `json:"providers"`
}

// ProvidersHandler serves GET /providers — the provider catalogue. It is
// mounted at the engine root (NOT under /v1/:provider, which would collide
// with the :provider wildcard in gin's radix tree).
type ProvidersHandler struct {
	reg *provider.ProviderRegistry
}

// NewProvidersHandler builds the catalogue handler over the given registry.
func NewProvidersHandler(reg *provider.ProviderRegistry) *ProvidersHandler {
	return &ProvidersHandler{reg: reg}
}

// GetProviders returns every registered provider's catalogue metadata so the
// client can render the provider list + auth mechanism without hardcoding.
func (h *ProvidersHandler) GetProviders(c *gin.Context) {
	if h.reg == nil {
		c.JSON(http.StatusOK, providersResponse{Providers: []providerDTO{}})
		return
	}
	all := h.reg.All()
	out := providersResponse{Providers: make([]providerDTO, 0, len(all))}
	for _, p := range all {
		caps := p.Capabilities()
		capStrs := make([]string, 0, len(caps))
		for _, cp := range caps {
			capStrs = append(capStrs, string(cp))
		}
		base := p.BaseURLs()
		if base == nil {
			base = []string{}
		}
		dto := providerDTO{
			ID:                p.ID(),
			DisplayName:       p.DisplayName(),
			Kind:              p.Kind(),
			Capabilities:      capStrs,
			AuthType:          string(p.AuthType()),
			Encoding:          p.Encoding(),
			BaseURLs:          base,
			SupportsAnonymous: p.SupportsAnonymous(),
		}
		if dto.Kind == "jackett" {
			dto.Indexer = p.ID()
		}
		out.Providers = append(out.Providers, dto)
	}
	c.JSON(http.StatusOK, out)
}
