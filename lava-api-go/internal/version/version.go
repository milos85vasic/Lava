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
	Name = "2.0.1"

	// Code is the integer release counter. New tags MUST increment.
	Code = 2001
)
