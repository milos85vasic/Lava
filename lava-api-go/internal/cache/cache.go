// Package cache adapts submodules/cache/pkg/postgres for lava-api-go's
// concrete needs: cache key construction (per spec §6) and outcome
// classification (hit | miss | bypass | invalidate) for metrics.
package cache

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"strings"
	"time"

	pgcache "digital.vasic.cache/pkg/postgres"
)

// Key constructs the canonical cache key from a request's identity,
// per spec §6's normalisation rule.
func Key(method, routeTemplate string, pathVars map[string]string, query map[string][]string, authRealmHash string) string {
	pathKeys := make([]string, 0, len(pathVars))
	for k := range pathVars {
		pathKeys = append(pathKeys, k)
	}
	sort.Strings(pathKeys)
	var pathParts []string
	for _, k := range pathKeys {
		pathParts = append(pathParts, k+"="+pathVars[k])
	}
	queryKeys := make([]string, 0, len(query))
	for k := range query {
		queryKeys = append(queryKeys, strings.ToLower(k))
	}
	sort.Strings(queryKeys)
	var queryParts []string
	for _, k := range queryKeys {
		vs := query[k]
		if len(vs) == 0 {
			continue
		}
		// Sort values for stability where order doesn't matter (the wire shape
		// dictates this; revisit if a route is order-sensitive).
		sortedVs := append([]string(nil), vs...)
		sort.Strings(sortedVs)
		queryParts = append(queryParts, k+"="+strings.Join(sortedVs, ","))
	}
	if authRealmHash == "" {
		authRealmHash = "anon"
	}
	raw := strings.Join([]string{
		method,
		routeTemplate,
		strings.Join(pathParts, ";"),
		strings.Join(queryParts, "&"),
		authRealmHash,
	}, "\n")
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

// Outcome classifies a cache lookup result.
type Outcome string

const (
	OutcomeHit        Outcome = "hit"
	OutcomeMiss       Outcome = "miss"
	OutcomeBypass     Outcome = "bypass"
	OutcomeInvalidate Outcome = "invalidate"
)

// Client is the lava-api-go cache facade. It owns a submodules/cache pgcache.Client
// and exposes only the operations handlers need.
type Client struct {
	inner *pgcache.Client
}

// New wraps an existing pgcache.Client.
func New(inner *pgcache.Client) *Client { return &Client{inner: inner} }

// Get retrieves a value by key. Returns ([]byte, OutcomeHit, nil) on hit,
// (nil, OutcomeMiss, nil) on miss, and (nil, OutcomeBypass, err) on
// underlying error so callers can fall through to upstream.
func (c *Client) Get(ctx context.Context, key string) ([]byte, Outcome, error) {
	v, err := c.inner.Get(ctx, key)
	if err != nil {
		return nil, OutcomeBypass, err
	}
	if v == nil {
		return nil, OutcomeMiss, nil
	}
	return v, OutcomeHit, nil
}

// Set stores a value with the given TTL.
func (c *Client) Set(ctx context.Context, key string, value []byte, ttl time.Duration) error {
	return c.inner.Set(ctx, key, value, ttl)
}

// Invalidate removes a key from the cache.
func (c *Client) Invalidate(ctx context.Context, key string) error {
	return c.inner.Delete(ctx, key)
}
