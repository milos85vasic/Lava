// Package middleware provides Gin middleware that extracts the requested
// provider ID from the URL path and attaches it to the request context
// for downstream handlers.
//
// Expected route pattern: /v1/:provider/...  (e.g. /v1/rutracker/search)
//
// Constitutional alignment:
//   - 6.E Capability Honesty: the dispatcher verifies the provider
//     declares the capability for the endpoint being invoked and
//     returns HTTP 501 (Not Implemented) with a clear body when not.
package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

const (
	// ProviderParam is the Gin route parameter name used for provider ID.
	ProviderParam = "provider"
	// ProviderContextKey is the key used to store the Provider in
	// gin.Context's key-value map.
	ProviderContextKey = "__provider__"
)

// ProviderMiddleware creates a Gin handler that resolves the
// :provider parameter against reg, stores the Provider in context,
// and validates that the provider supports the given capability for
// the current endpoint.
//
// If the provider is unknown, it aborts with 404.
// If the capability is not declared, it aborts with 501.
func ProviderMiddleware(reg *provider.ProviderRegistry, cap provider.ProviderCapability) gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.Param(ProviderParam)
		p, err := reg.Get(id)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusNotFound, gin.H{
				"error":   "unknown_provider",
				"message": err.Error(),
			})
			return
		}

		if !reg.Supports(id, cap) {
			c.AbortWithStatusJSON(http.StatusNotImplemented, gin.H{
				"error":      "unsupported_capability",
				"provider":   id,
				"capability": cap,
				"message":    "This provider does not implement the requested capability.",
			})
			return
		}

		c.Set(ProviderContextKey, p)
		c.Next()
	}
}

// Current extracts the Provider from the Gin context.
// Panics if the middleware was not run (programmer error).
func Current(c *gin.Context) provider.Provider {
	val, ok := c.Get(ProviderContextKey)
	if !ok {
		panic("provider middleware not executed for this request")
	}
	return val.(provider.Provider)
}

// CurrentID extracts the provider canonical ID from the Gin context.
func CurrentID(c *gin.Context) string {
	return c.Param(ProviderParam)
}

// CapabilityMiddleware returns a handler that only checks capability
// support without loading the full provider. Useful for lightweight
// health or metadata endpoints.
func CapabilityMiddleware(reg *provider.ProviderRegistry, cap provider.ProviderCapability) gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.Param(ProviderParam)
		if _, err := reg.Get(id); err != nil {
			c.AbortWithStatusJSON(http.StatusNotFound, gin.H{
				"error":   "unknown_provider",
				"message": err.Error(),
			})
			return
		}
		if !reg.Supports(id, cap) {
			c.AbortWithStatusJSON(http.StatusNotImplemented, gin.H{
				"error":      "unsupported_capability",
				"provider":   id,
				"capability": cap,
			})
			return
		}
		c.Next()
	}
}
