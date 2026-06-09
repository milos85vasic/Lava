package provider

import (
	"fmt"
	"sort"
	"sync"
)

// ProviderRegistry holds the set of active providers keyed by their
// canonical identifier (e.g. "rutracker", "nnmclub").
//
// Thread-safe: Register and Get may be called concurrently.
type ProviderRegistry struct {
	mu sync.RWMutex
	m  map[string]Provider
}

// NewRegistry creates an empty ProviderRegistry.
func NewRegistry() *ProviderRegistry {
	return &ProviderRegistry{
		m: make(map[string]Provider),
	}
}

// Register adds a provider to the registry.
// Panics if a provider with the same ID has already been registered.
func (r *ProviderRegistry) Register(p Provider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := p.ID()
	if _, exists := r.m[id]; exists {
		panic(fmt.Sprintf("provider %q already registered", id))
	}
	r.m[id] = p
}

// Get retrieves a provider by its canonical ID. Returns nil and a
// descriptive error if the provider is not found.
func (r *ProviderRegistry) Get(id string) (Provider, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.m[id]
	if !ok {
		return nil, fmt.Errorf("provider %q not found", id)
	}
	return p, nil
}

// IDs returns the canonical IDs of every registered provider in
// lexicographic order.
//
// The sort is REQUIRED, not a convenience: consumers that surface this set to
// users — notably the multi-search SSE auto-discovery path (handlers/v1
// GetMultiSearch), which streams provider_start / results / provider_done
// events in IDs() order — depend on a deterministic, stable sequence so the
// Android client renders providers in a fixed order across repeated searches
// and the SSE event order is testable. Returning Go's randomized
// map-iteration order here (the pre-LVA-059 behaviour) reshuffled the provider
// list on almost every request. See TestRegistry_IDs_LexicographicAndDeterministic
// and the handler-level TestMultiSearch_AutoDiscoveryStreamsProvidersInDeterministicOrder.
func (r *ProviderRegistry) IDs() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]string, 0, len(r.m))
	for id := range r.m {
		out = append(out, id)
	}
	sort.Strings(out)
	return out
}

// All returns every registered provider.
func (r *ProviderRegistry) All() []Provider {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Provider, 0, len(r.m))
	for _, p := range r.m {
		out = append(out, p)
	}
	return out
}

// Supports returns true if the provider with the given ID is
// registered and declares the requested capability.
func (r *ProviderRegistry) Supports(providerID string, cap ProviderCapability) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.m[providerID]
	if !ok {
		return false
	}
	for _, c := range p.Capabilities() {
		if c == cap {
			return true
		}
	}
	return false
}
