// Package contract — API↔embed source-hash sync contract test.
//
// This test guards the mechanism that guarantees the on-device Lava API embed
// (liblavaapi.so, packaged by :core:apiengine) contains EXACTLY the current
// lava-api-go source codebase — no drift (§11.4.69 / §6.J). The single source of
// truth is scripts/compute-api-source-hash.sh; the committed manifest is
// core/apiengine/src/main/resources/api-source.hash; the gate is
// scripts/check-api-app-sync.sh.
//
// It asserts three properties:
//
//	(a) STABILITY — compute-api-source-hash.sh produces the SAME bare 64-hex
//	    sha256 across two invocations (a non-deterministic hash would make the
//	    gate flaky and therefore useless).
//	(b) MANIFEST-MATCH — the committed manifest exists and equals the live hash
//	    (the build-cshared.sh + commit discipline kept them in lockstep; a stale
//	    manifest means the on-device embed is drifting).
//	(c) FALSIFIABILITY — mutating a tracked, embed-linked .go file's CONTENT
//	    changes the hash (proving the hash actually covers the source it claims
//	    to). The mutation is reverted before the test returns.
//
// Falsifiability protocol (Sixth Law clause 2 / lava-api-go CLAUDE.md):
//
//	Test:     TestSourceHash_FalsifiabilityContentEditChangesHash
//	Mutation: append a comment line to internal/version/version.go (a tracked,
//	          non-test, embed-linked .go file).
//	Observed: live hash != base hash (the assertion that fires on a stable hash
//	          if the mutation were NOT covered).
//	Reverted: yes — the original bytes are restored via deferred write.
package contract

import (
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var hexHash64 = regexp.MustCompile(`^[0-9a-f]{64}$`)

// computeSourceHash invokes scripts/compute-api-source-hash.sh and returns its
// trimmed stdout (the bare 64-hex hash). It fails the test on any error.
func computeSourceHash(t *testing.T) string {
	t.Helper()
	root := repoRoot(t)
	script := filepath.Join(root, "scripts", "compute-api-source-hash.sh")
	if _, err := os.Stat(script); err != nil {
		t.Fatalf("hash script missing at %s: %v", script, err)
	}
	cmd := exec.Command("bash", script)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("compute-api-source-hash.sh failed: %v\noutput:\n%s", err, out)
	}
	return strings.TrimSpace(string(out))
}

// TestSourceHash_StableAndWellFormed asserts property (a): the hash is a bare
// 64-hex sha256 and is identical across two invocations.
func TestSourceHash_StableAndWellFormed(t *testing.T) {
	h1 := computeSourceHash(t)
	if !hexHash64.MatchString(h1) {
		t.Fatalf("hash %q is not a bare 64-hex sha256", h1)
	}
	h2 := computeSourceHash(t)
	if h1 != h2 {
		t.Fatalf("hash not stable across runs: %q != %q", h1, h2)
	}
}

// TestSourceHash_ManifestMatchesLive asserts property (b): the committed
// manifest exists and equals the live computed hash. A mismatch means the
// on-device embed is STALE relative to the current lava-api-go source — the
// exact drift this whole mechanism exists to catch.
func TestSourceHash_ManifestMatchesLive(t *testing.T) {
	root := repoRoot(t)
	manifest := filepath.Join(root, "core", "apiengine", "src", "main", "resources", "api-source.hash")
	raw, err := os.ReadFile(manifest)
	if err != nil {
		t.Fatalf("committed manifest missing/unreadable at %s: %v\n"+
			"seed it via lava-api-go/scripts/build-cshared.sh (or "+
			"scripts/compute-api-source-hash.sh > the manifest)", manifest, err)
	}
	committed := strings.TrimSpace(string(raw))
	live := computeSourceHash(t)
	if committed != live {
		t.Fatalf("API↔embed sync manifest STALE:\n  committed=%s\n  live=%s\n"+
			"the on-device embed would not match the current lava-api-go source; "+
			"rebuild via build-cshared.sh and commit the refreshed manifest",
			committed, live)
	}
}

// TestSourceHash_FalsifiabilityContentEditChangesHash asserts property (c): a
// content edit to a tracked, embed-linked .go file changes the hash. This proves
// the hash genuinely covers the source it claims to — without it, the manifest
// gate would be a bluff (it would pass even after the embed source changed).
//
// The mutation targets internal/version/version.go (compiled into the .so) and
// is reverted via a deferred original-bytes restore, so the working tree is
// unchanged after the test.
func TestSourceHash_FalsifiabilityContentEditChangesHash(t *testing.T) {
	root := repoRoot(t)
	target := filepath.Join(root, "lava-api-go", "internal", "version", "version.go")

	original, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read target %s: %v", target, err)
	}
	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("stat target %s: %v", target, err)
	}

	base := computeSourceHash(t)

	mutated := append(append([]byte{}, original...), []byte("\n// falsifiability-probe\n")...)
	if err := os.WriteFile(target, mutated, info.Mode().Perm()); err != nil {
		t.Fatalf("write mutated target: %v", err)
	}
	// Always restore, even if an assertion below fails.
	defer func() {
		if err := os.WriteFile(target, original, info.Mode().Perm()); err != nil {
			t.Fatalf("CRITICAL: failed to revert %s — restore manually: %v", target, err)
		}
	}()

	mutHash := computeSourceHash(t)
	if mutHash == base {
		t.Fatalf("hash UNCHANGED after a content edit to %s — the hash does NOT "+
			"cover the embed source (bluff): base=%s mutated=%s", target, base, mutHash)
	}

	// Sanity: after the deferred revert runs, the hash returns to base. We assert
	// this inline by reverting now and recomputing (the defer becomes a no-op
	// rewrite of identical bytes).
	if err := os.WriteFile(target, original, info.Mode().Perm()); err != nil {
		t.Fatalf("inline revert failed: %v", err)
	}
	revHash := computeSourceHash(t)
	if revHash != base {
		t.Fatalf("hash did not return to base after revert: base=%s reverted=%s", base, revHash)
	}
}
