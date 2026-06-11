//go:build realtrackers

// Real-network test (§6.J clause 3 / §6.D real-stack): hits the LIVE apibay API.
// Gated behind the `realtrackers` build tag so the default `go test ./...` makes
// no outbound calls. Run with:
//
//	go test -tags realtrackers ./internal/provider/curated/thepiratebay/...
package thepiratebay

import (
	"context"
	"regexp"
	"testing"
	"time"
)

var hex40 = regexp.MustCompile(`^[0-9a-f]{40}$`)

func TestLive_SearchUbuntuReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	res, err := NewClient(DefaultBaseURL).Search(ctx, "ubuntu", 0)
	if err != nil {
		t.Fatalf("live Search: %v", err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live apibay returned 0 results for 'ubuntu' — provider is not user-reachable")
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
