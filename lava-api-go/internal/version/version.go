// Package version exposes the build-time version constants for the lava-api-go
// service. scripts/tag.sh reads and rewrites these constants as part of the
// release tagging flow.
//
// Format invariants:
//   - Name MUST be a strict three-component semver: MAJOR.MINOR.PATCH
//   - Code MUST be a positive integer that monotonically increases per release
package version

const (
	// Name is the service's semver. Tag prefix: Lava-API-Go-<Name>-<Code>.
	// 2.3.33: SEARCH TIMEOUT FIX — bound the per-provider search handler to a
	// total 18s request deadline (handlers/v1/search.go) so the engine ALWAYS
	// responds before the Android client's 30s OkHttp readTimeout (was unbounded:
	// failover×mirrors+retries could exceed 30s → client SocketTimeout → "no
	// results"); + refreshed the stale YTS mirror list (dropped NXDOMAIN yts.mx,
	// lead with live yts.bz + current canonical movies-api.accel.li).
	// 2.3.32: §6.AC comprehensive non-fatal telemetry — RecordNonFatal on every
	// provider error path (writeProviderError default branch) + search.go +
	// the best-effort Firebase-Crashlytics-bridge webhook forwarder
	// (LAVA_API_FIREBASE_CRASHLYTICS_ENABLED + LAVA_API_NONFATAL_WEBHOOK_URL).
	// 2.3.34: F3 fix — Android x86_64 app-seccomp legacy-syscall crash. The
	// embedded build (GOOS=android GOARCH=amd64) uses modernc/libc's musl
	// fast-path which issued raw lstat/stat/open/... syscalls that Android's
	// x86_64 seccomp allowlist blocks (SIGSYS). A modernc/libc fork
	// (third_party/modernc-libc, replace directive) remaps them to the *at
	// forms at the X__syscallN dispatcher. arm64 was never affected.
	Name = "2.3.34"

	// Code is the integer release counter. New tags MUST increment.
	Code = 2334
)

// SourceHash is the 64-hex sha256 of the EXACT lava-api-go source codebase that
// was compiled into the on-device c-shared embed (liblavaapi.so), as computed by
// scripts/compute-api-source-hash.sh. It is EMPTY by default and INJECTED at
// build time via:
//
//	-ldflags "-X 'digital.vasic.lava.apigo/internal/version.SourceHash=<hash>'"
//
// by scripts/build-cshared.sh. The embed surfaces it through mobile.Status()
// (the SourceHash field of the status JSON), so the on-device Kotlin can read
// the hash baked into the RUNNING .so and assert it equals the hash the host APK
// was built against (BuildConfig.LAVA_API_SOURCE_HASH). Equal hashes prove the
// embed contains EXACTLY the current lava-api-go codebase — no drift. An empty
// value means the .so was built without injection (a host-direct `go build`,
// not the gate path); the on-device sync Challenge treats empty as a hard fail
// so an un-injected embed cannot pass the drift gate silently.
//
// §6.R: this is build-time-injected, not a source literal. §6.J: the gate
// (scripts/check-api-app-sync.sh) recomputes the hash and compares to the
// committed manifest, making any drift mechanically loud.
var SourceHash string
