//go:build realtrackers

// Real-network test (§6.J clause 3 / §6.D real-stack): hits the LIVE Tokyo
// Toshokan RSS feed. Gated behind the `realtrackers` build tag so the default
// `go test ./...` makes no outbound calls. Run with:
//
//	go test -tags realtrackers ./internal/provider/curated/tokyotosho/...
package tokyotosho

import (
	"context"
	"regexp"
	"strings"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/provider"
)

// base32Hash matches the BASE32 (32-char, A-Z2-7) info_hash Tokyo Toshokan emits.
var base32Hash = regexp.MustCompile(`^[A-Z2-7]{32}$`)

func TestLive_SearchReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "naruto", 0)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live Tokyo Toshokan returned 0 results for 'naruto' — provider is not user-reachable")
	}
	for _, item := range res.Results {
		if !base32Hash.MatchString(item.InfoHash) {
			t.Errorf("live result has non-base32 info_hash: %q (%s)", item.InfoHash, item.Title)
		}
		if !strings.HasPrefix(item.MagnetLink, "magnet:?xt=urn:btih:") {
			t.Errorf("live result %q has no usable magnet: %q", item.Title, item.MagnetLink)
		}
	}
}

// §6.E honesty: the RSS `terms` parameter MUST genuinely filter (the property
// EZTV / Knaben-search_type lacked). This is THE anti-bluff proof for CapSearch:
// a different query MUST narrow the result set to on-topic rows. Verified live
// 2026-06-13: terms=naruto → 93% of titles contain "naruto"; terms=bleach → 98%
// contain "bleach"; naruto vs bleach result sets are near-disjoint.
func TestLive_QueryActuallyFilters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()

	c := NewClient(DefaultBaseURL)

	naruto, err := c.Search(ctx, "naruto", 0)
	if err != nil {
		t.Fatalf("live Search(naruto): %v", err)
	}
	bleach, err := c.Search(ctx, "bleach", 0)
	if err != nil {
		t.Fatalf("live Search(bleach): %v", err)
	}
	if len(naruto.Results) == 0 || len(bleach.Results) == 0 {
		t.Fatalf("a live query returned 0 results (naruto=%d, bleach=%d)", len(naruto.Results), len(bleach.Results))
	}

	// Proof 1: the bulk of each query's titles contain the query term. A no-op
	// `terms` (global list) would NOT show this — most titles would be unrelated.
	narutoHits := termMatchRate(naruto.Results, "naruto")
	bleachHits := termMatchRate(bleach.Results, "bleach")
	if narutoHits < 0.5 {
		t.Errorf("only %.0f%% of naruto results contain 'naruto' — terms= is not honored (CapSearch would be a bluff)", narutoHits*100)
	}
	if bleachHits < 0.5 {
		t.Errorf("only %.0f%% of bleach results contain 'bleach' — terms= is not honored (CapSearch would be a bluff)", bleachHits*100)
	}

	// Proof 2: the two result sets are near-disjoint by info_hash. A no-op query
	// would return the SAME global list for both → near-total overlap.
	narutoSet := map[string]bool{}
	for _, it := range naruto.Results {
		narutoSet[it.InfoHash] = true
	}
	overlap := 0
	for _, it := range bleach.Results {
		if narutoSet[it.InfoHash] {
			overlap++
		}
	}
	overlapRate := float64(overlap) / float64(len(bleach.Results))
	if overlapRate > 0.5 {
		t.Errorf("naruto/bleach result sets overlap %.0f%% (%d/%d) — terms= is a no-op, CapSearch is a bluff",
			overlapRate*100, overlap, len(bleach.Results))
	}
}

// termMatchRate returns the fraction of items whose title contains term
// (case-insensitive).
func termMatchRate(items []provider.SearchItem, term string) float64 {
	if len(items) == 0 {
		return 0
	}
	hits := 0
	for _, it := range items {
		if strings.Contains(strings.ToLower(it.Title), term) {
			hits++
		}
	}
	return float64(hits) / float64(len(items))
}
