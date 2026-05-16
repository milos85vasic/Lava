// Package validator — internal I/O indirection.
//
// writeFileFn is a package-level variable bound to os.WriteFile so the
// production code path uses the real filesystem AND tests can override
// it (e.g., to assert callsite arguments without touching the host fs).
// Per the §6.J anti-bluff posture: production tests DO use the real fs
// (t.TempDir) and only the deliberate-mutation rehearsal in the unit
// test temporarily overrides this var.
package validator

import "os"

// writeFileFn is the file-writer used by emitTicket. Variable (not
// constant function) so tests can swap it for mutation-rehearsal
// purposes per §6.J clause 2.
var writeFileFn = os.WriteFile
