package observability

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
)

// ReadinessProbe is invoked by /ready. It returns nil when the service
// can serve traffic, or an error describing why not. Concrete probes
// are wired in cmd/lava-api-go/main.go (DB ping, breaker state, etc).
type ReadinessProbe func(ctx context.Context) error

// LivenessHandler always returns 200 if the process is up. Liveness
// probes MUST NOT depend on downstream state — a temporarily
// unavailable database is a readiness signal, not a liveness one,
// and accidentally returning 503 on liveness causes orchestrators to
// kill an otherwise-healthy pod (CrashLoopBackOff cascades).
func LivenessHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "alive"})
	}
}

// ReadinessHandler returns 200 if probe(ctx) returns nil; 503
// otherwise. A nil probe is treated as "always ready" — useful for
// tests and the trivial bootstrap path.
func ReadinessHandler(probe ReadinessProbe) gin.HandlerFunc {
	return func(c *gin.Context) {
		if probe == nil {
			c.JSON(http.StatusOK, gin.H{"status": "ready"})
			return
		}
		if err := probe(c.Request.Context()); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"status": "not ready",
				"error":  err.Error(),
			})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready"})
	}
}
