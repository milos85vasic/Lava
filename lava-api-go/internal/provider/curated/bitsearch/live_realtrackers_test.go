//go:build realtrackers

// Real-network test (§6.J clause 3 / §6.D real-stack): hits the LIVE BitSearch
// API. Gated behind the `realtrackers` build tag so the default `go test ./...`
// makes no outbound calls. Run with:
//
//	go test -tags realtrackers ./internal/provider/curated/bitsearch/...
package bitsearch

import (
	"context"
	"regexp"
	"strings"
	"testing"
	"time"
)

var hex40 = regexp.MustCompile(`^[0-9a-f]{40}$`)

func TestLive_SearchUbuntuReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "ubuntu", 1)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live BitSearch returned 0 results for 'ubuntu' — provider is not user-reachable")
	}
	for _, item := range res.Results {
		if !hex40.MatchString(item.InfoHash) {
			t.Errorf("live result has non-40-hex info_hash: %q (%s)", item.InfoHash, item.Title)
		}
		if item.MagnetLink == "" {
			t.Errorf("live result %q has no magnet", item.Title)
		}
	}
}

// §6.E honesty: the free-text `q` term must genuinely filter — the EXACT property
// EZTV's API LACKS (its keywords param is a no-op). We assert the query term
// appears in the titles it returns, so a query-ignoring backend would fail here
// and CapSearch could never be a silent bluff.
func TestLive_QueryActuallyFilters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "debian", 1)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live BitSearch returned 0 results for 'debian'")
	}
	hits := 0
	for _, item := range res.Results {
		if strings.Contains(strings.ToLower(item.Title), "debian") {
			hits++
		}
	}
	if hits == 0 {
		t.Errorf("no 'debian' result contained the query term — the q filter is not honored (CapSearch would be a bluff)")
	}
}
