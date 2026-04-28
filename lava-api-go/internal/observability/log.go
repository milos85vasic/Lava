// Package observability glues the vasic-digital observability + security
// modules into a single setup callable from cmd/lava-api-go.
//
// Decoupled Reusable rationale: this package does not contain any
// Lava-domain business logic. It composes the upstream submodules
// (Submodules/Observability, Submodules/Security/pkg/pii) into the
// concrete logger / metrics / tracer / health surfaces that
// cmd/lava-api-go consumes.
package observability

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
)

// LogConfig configures the structured logger.
type LogConfig struct {
	Output     io.Writer  // default os.Stdout
	Level      slog.Level // default slog.LevelInfo (the zero value)
	RedactKeys []string   // attribute names whose values are redacted before write
}

// DefaultRedactKeys is the redaction denylist applied to slog
// attribute names for lava-api-go. Per spec §9 the list MUST cover
// every credential-bearing header that the auth pass-through and
// Submodules/Auth code paths might log. Keys are matched
// case-insensitively, and the canonical form (Auth-Token vs auth_token)
// is irrelevant — both are redacted.
//
// Renaming or removing any of these keys is a security-impacting
// change and MUST be reviewed against spec §9.
var DefaultRedactKeys = []string{
	"Auth-Token", "auth_token", "auth-token",
	"Cookie", "cookie",
	"Set-Cookie", "set_cookie", "set-cookie",
	"Authorization", "authorization",
	"X-Auth", "x_auth", "x-auth",
}

const redactedSentinel = "[REDACTED]"

// NewLogger returns a slog.Logger that writes JSON-formatted records
// to cfg.Output. Any attribute whose key matches one of cfg.RedactKeys
// (case-insensitive after normalising '-' and '_' to the same class)
// has its value replaced by [REDACTED] before serialisation.
//
// Adaptation note: the in-tree plan template called for
// `obslog.NewJSON(out, level, obslog.WithRedactor(...))` from
// `digital.vasic.observability/pkg/logging`. The actual upstream
// surface is logrus-based (Logger interface, NewLogrusAdapter), and
// does not expose attribute-key redaction. To satisfy the contract
// stated in the plan ("returns a slog.Logger that writes
// JSON-formatted records and redacts the keys in DefaultRedactKeys")
// we implement a minimal redacting slog.Handler ourselves, on top of
// stdlib log/slog. Submodules/Security/pkg/pii is reserved for
// VALUE-based PII redaction (emails, phones, etc.) at the outermost
// boundaries — it does not provide attribute-key denylisting.
func NewLogger(cfg LogConfig) *slog.Logger {
	if cfg.Output == nil {
		cfg.Output = os.Stdout
	}
	if len(cfg.RedactKeys) == 0 {
		cfg.RedactKeys = DefaultRedactKeys
	}
	return slog.New(newRedactingHandler(cfg.Output, cfg.Level, cfg.RedactKeys))
}

// redactingHandler is a slog.Handler that emits JSON with attribute
// values replaced by [REDACTED] for every key in keys.
type redactingHandler struct {
	mu    *sync.Mutex
	out   io.Writer
	level slog.Level
	keys  map[string]struct{}
	attrs []slog.Attr
	group string
}

func newRedactingHandler(out io.Writer, level slog.Level, keys []string) *redactingHandler {
	set := make(map[string]struct{}, len(keys))
	for _, k := range keys {
		set[normaliseKey(k)] = struct{}{}
	}
	return &redactingHandler{
		mu:    &sync.Mutex{},
		out:   out,
		level: level,
		keys:  set,
	}
}

// normaliseKey lower-cases the key and folds '_' to '-' so that
// "Auth-Token", "auth_token" and "AUTH-TOKEN" all match the same
// denylist entry.
func normaliseKey(k string) string {
	return strings.ReplaceAll(strings.ToLower(k), "_", "-")
}

func (h *redactingHandler) Enabled(_ context.Context, l slog.Level) bool {
	return l >= h.level
}

func (h *redactingHandler) Handle(_ context.Context, r slog.Record) error {
	rec := map[string]any{
		"time":  r.Time.UTC().Format("2006-01-02T15:04:05.000Z07:00"),
		"level": r.Level.String(),
		"msg":   r.Message,
	}
	for _, a := range h.attrs {
		h.putAttr(rec, a)
	}
	r.Attrs(func(a slog.Attr) bool {
		h.putAttr(rec, a)
		return true
	})
	buf, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	buf = append(buf, '\n')
	h.mu.Lock()
	defer h.mu.Unlock()
	_, err = h.out.Write(buf)
	return err
}

func (h *redactingHandler) putAttr(rec map[string]any, a slog.Attr) {
	if _, deny := h.keys[normaliseKey(a.Key)]; deny {
		rec[a.Key] = redactedSentinel
		return
	}
	rec[a.Key] = a.Value.Any()
}

func (h *redactingHandler) WithAttrs(as []slog.Attr) slog.Handler {
	clone := *h
	clone.attrs = append([]slog.Attr(nil), h.attrs...)
	clone.attrs = append(clone.attrs, as...)
	return &clone
}

func (h *redactingHandler) WithGroup(name string) slog.Handler {
	clone := *h
	clone.group = name
	return &clone
}
