//go:build realtrackers

// Real-network test (§6.J clause 3 / §6.D real-stack): hits the LIVE Nyaa RSS
// feed. Gated behind the `realtrackers` build tag so the default `go test ./...`
// makes no outbound calls. Run with:
//
//	go test -tags realtrackers ./internal/provider/curated/nyaa/...
package nyaa

import (
	"context"
	"regexp"
	"strings"
	"testing"
	"time"
)

var hex40 = regexp.MustCompile(`^[0-9a-f]{40}$`)

func TestLive_SearchReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "naruto", 0)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live Nyaa returned 0 results for 'naruto' — provider is not user-reachable")
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

// §6.E honesty: the RSS `q` term must genuinely filter (the property EZTV lacked).
func TestLive_QueryActuallyFilters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "bleach", 0)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live Nyaa returned 0 results for 'bleach'")
	}
	hits := 0
	for _, item := range res.Results {
		if strings.Contains(strings.ToLower(item.Title), "bleach") {
			hits++
		}
	}
	if hits == 0 {
		t.Errorf("no 'bleach' result contained the query term — the q filter is not honored (CapSearch would be a bluff)")
	}
}
