//go:build realtrackers

// Real-network tests (§6.J clause 3 / §6.D real-stack): hit the LIVE YTS API.
// Gated behind the `realtrackers` build tag so the default `go test ./...` makes
// no outbound calls. Run with:
//
//	go test -tags realtrackers -v ./internal/provider/curated/yts/...
//
// Mirror status verified 2026-06-24:
//
//	yts.bz  — 200 OK ~0.24–0.8s (FASTEST — leads DefaultBaseURLs)
//	yts.lt  — 200 OK ~0.8s
//	yts.am  — 200 OK ~0.7–5.8s
//	yts.gg  — stalls from some networks; sub-second from others
//	movies-api.accel.li — stalls from some networks; API's own recommended base
//	yts.mx  — NXDOMAIN (dead 2026-06-24; removed from DefaultBaseURLs)
package yts

import (
	"context"
	"regexp"
	"testing"
	"time"
)

var hex40 = regexp.MustCompile(`^[0-9a-f]{40}$`)

// TestLive_SearchReturnsRealMagnets verifies the production failover list
// returns real movie torrents end-to-end (§6.J user-reachable guarantee).
func TestLive_SearchReturnsRealMagnets(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Use the production mirror failover list — yts.mx is NXDOMAIN-dead as of
	// 2026-06-24; the provider must reach a live mirror (yts.bz leads the list).
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

// TestReproduction_StaleMirrorListFails is the §6.T.1 reproduction test for
// the "YTS no results" bug. It proves:
//
//   - Part A (STALE): a client pointed only at yts.mx (NXDOMAIN) errors —
//     reproducing the user-visible "no results / provider unreachable" symptom.
//   - Part B (FRESH): the updated DefaultBaseURLs (yts.bz first) returns ≥1
//     real movie result in well under the 8s per-attempt budget.
//
// Bluff-Audit: see commit body.
func TestReproduction_StaleMirrorListFails(t *testing.T) {
	// Part A — STALE: yts.mx alone (NXDOMAIN as of 2026-06-24) must fail.
	t.Run("stale_yts_mx_only_fails", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()

		staleClient := NewClientWithMirrors([]string{"https://yts.mx"})
		_, err := staleClient.Search(ctx, "batman", 0)
		if err == nil {
			t.Fatal("REPRODUCTION INVALID: yts.mx (NXDOMAIN) returned success — " +
				"yts.mx may have come back online. Re-verify live endpoint status " +
				"and update DefaultBaseURLs accordingly.")
		}
		t.Logf("REPRODUCED: yts.mx-only Search error (expected): %v", err)
	})

	// Part B — FRESH: the updated list (yts.bz first) must succeed with ≥1 real
	// movie result within the 8s per-attempt budget.
	t.Run("fresh_list_succeeds_fast", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		start := time.Now()
		res, err := NewClientWithMirrors(DefaultBaseURLs).Search(ctx, "batman", 0)
		elapsed := time.Since(start)

		if err != nil {
			t.Fatalf("fresh DefaultBaseURLs Search failed: %v", err)
		}
		if len(res.Results) == 0 {
			t.Fatal("fresh DefaultBaseURLs returned 0 results for 'batman' — provider is not user-reachable")
		}
		// The first working mirror (yts.bz) should answer in well under 8s.
		if elapsed > 8*time.Second {
			t.Errorf("fresh list took %v — exceeds 8s per-attempt budget; check mirror order", elapsed)
		}
		t.Logf("VERIFIED: DefaultBaseURLs returned %d results in %v (first: %q)",
			len(res.Results), elapsed, res.Results[0].Title)
	})
}
