# Pending extraction to vasic-digital submodules

This file tracks Lava-internal components that SHOULD live in a generic
vasic-digital submodule but currently sit inside `lava-api-go` because
the upstream submodule does not yet expose the required surface.

The constitutional rule (`/CLAUDE.md` "Decoupled Reusable Architecture")
is "upstream first, Lava pin second". When that rule cannot be honoured
in a single PR — typically because the upstream needs a new API that is
out-of-scope for the current change — the deferral MUST be recorded here
as a tracked TODO, per `/CLAUDE.md`:

> "Deferring extraction of a borderline piece during a single PR; the
> deferral must be tracked as a TODO with a target sub-project for
> extraction"

Each entry is removed once the upstream change lands and `lava-api-go`
re-pins onto the generic implementation.

---

## 1. slog redacting JSON handler

- **Component.** A custom `slog.Handler` that JSON-encodes records and
  replaces attribute values whose normalised key (lower-case, `_`→`-`)
  matches a denylist with the `[REDACTED]` sentinel. Includes
  `DefaultRedactKeys` (the credential-bearing header denylist),
  `normaliseKey` (case + dash/underscore folding), and a
  `slog.Logger` factory (`NewLogger(LogConfig)`).
- **Source location in lava-api-go.**
  `internal/observability/log.go`. The exported surface used by callers
  is `observability.NewLogger(LogConfig)`,
  `observability.DefaultRedactKeys`, and `observability.LogConfig`.
- **Target submodule and package.**
  `Submodules/Observability/pkg/logging`. Proposed addition:
  ```go
  // NewSlogJSON returns a *slog.Logger that writes JSON-formatted
  // records to out, gates at level, and replaces the value of any
  // attribute whose normalised key is in redactKeys with "[REDACTED]".
  // Key normalisation lower-cases and folds '_' to '-'.
  func NewSlogJSON(out io.Writer, level slog.Level, redactKeys []string) *slog.Logger
  ```
  The package today is logrus-based (`Logger` interface,
  `NewLogrusAdapter`); the slog surface is additive and does not
  conflict with the existing API.
- **Why deferred.** Upstream
  `Submodules/Observability/pkg/logging` does not currently expose a
  slog handler or attribute-key redaction primitive. Extracting
  requires (a) an upstream PR adding `NewSlogJSON`, (b) a
  Submodules/Observability tag, (c) a `lava-api-go` re-pin onto the
  new tag, and (d) deletion of `internal/observability/log.go` in
  favour of a thin wrapper. None of those steps are scoped into the
  current Phase-3 change; doing them in-band would have grown a
  foundation PR into a multi-submodule release.
- **Constitutional rationale.** This is the documented exception per
  `/CLAUDE.md` ("Deferring extraction of a borderline piece during a
  single PR; the deferral must be tracked as a TODO with a target
  sub-project for extraction"). The inline `// Adaptation note:` in
  `log.go` records the same gap at the call site; this tracker file
  promotes it to a discoverable backlog item.
- **Suggested timing.** Phase 13 (pretag verify + mutation + security)
  is a natural cleanup window because that phase already reviews
  security-sensitive code. May land sooner if another vasic-digital
  service adopts slog and pulls the upstream API forward.
