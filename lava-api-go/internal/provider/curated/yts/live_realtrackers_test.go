//go:build realtrackers

// Real-network test (§6.J clause 3 / §6.D real-stack): hits the LIVE YTS API.
// Gated behind the `realtrackers` build tag so the default `go test ./...` makes
// no outbound calls. Run with:
//
//	go test -tags realtrackers ./internal/provider/curated/yts/...
package yts

import (
	"context"
	"regexp"
	"testing"
	"time"
)

var hex40 = regexp.MustCompile(`^[0-9a-f]{40}$`)

func TestLive_SearchReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Use the production mirror failover list — a single hardcoded yts.mx would
	// be NXDOMAIN-dead as of 2026-06-13; the provider must reach a live mirror.
	// Query a real movie TITLE (YTS query_term searches titles; "1080p" matches
	// no movie and legitimately returns 0).
	res, err := NewClientWithMirrors(DefaultBaseURLs).Search(ctx, "interstellar", 0)
	if err != nil {
		t.Fatalf("live Search across mirrors %v: %v", DefaultBaseURLs, err)
	}
	if len(res.Results) == 0 {
		t.Fatal("live YTS returned 0 results for 'interstellar' across all mirrors — provider is not user-reachable")
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
