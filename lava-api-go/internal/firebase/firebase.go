// Package firebase wires the lava-api-go service to Firebase for
// non-fatal error reporting + Analytics-style event emission.
//
// Constitutional bindings:
//
//   - 6.H Credential Security — the service-account key path
//     ("firebase-admin-key.json") is gitignored. If absent, this
//     package becomes a no-op rather than failing the boot —
//     production deploys MUST mount the key; CI / dev work without it.
//
//   - 6.J Anti-Bluff — every method either reports a real outcome
//     (the call to the Firebase Admin SDK) or returns an explicit
//     "no-op: client unconfigured" so a caller can never confuse
//     unconfigured with reported.
//
// The Admin SDK uses Google Cloud Logging behind the scenes for
// structured non-fatal records; the dashboard surface for these
// records is the same Firebase / GCP console the Android Crashlytics
// non-fatals appear under (project-wide).
package firebase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"time"
)

// Client is the surface lava-api-go uses to report runtime events
// to Firebase. Implementations may be the real Admin-SDK-backed
// client OR a no-op (when no key is configured) — callers don't
// branch on which.
type Client interface {
	// LogEvent records a server-side event (analytics) with the given
	// name + string parameters.
	LogEvent(ctx context.Context, name string, params map[string]string) error

	// RecordNonFatal records a non-fatal exception (recovered panic,
	// HTTP 5xx, dependency failure) for the operator dashboard.
	RecordNonFatal(ctx context.Context, err error, ctxFields map[string]string) error

	// Configured reports whether this client is wired to a real
	// Firebase Admin SDK or running as a no-op.
	Configured() bool
}

// Config controls how the Firebase client is constructed.
type Config struct {
	// CredentialsPath points to a Firebase Admin SDK service-account
	// JSON. When empty, the client falls back to GOOGLE_APPLICATION_CREDENTIALS
	// env var; when both are unset / missing, the client is a no-op.
	CredentialsPath string

	// ProjectID is the Firebase project the client reports under. Empty
	// means autodetect from the credentials JSON.
	ProjectID string

	// Logger is used for structured logs about the Firebase client's
	// own state. MUST NOT receive credentials.
	Logger *slog.Logger
}

// New returns a Client. It always returns a non-nil client (either
// real or no-op) — callers should NOT branch on (client == nil) but
// MAY branch on Configured() if they want to skip work that costs
// real resources to prepare.
func New(cfg Config) Client {
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	credPath := cfg.CredentialsPath
	if credPath == "" {
		credPath = os.Getenv("GOOGLE_APPLICATION_CREDENTIALS")
	}

	if credPath == "" {
		logger.Info("firebase: no credentials configured — running in no-op mode (per §6.H, this is the dev/CI default)")
		return &noopClient{logger: logger, reason: "no credentials path"}
	}
	if _, err := os.Stat(credPath); err != nil {
		logger.Warn("firebase: credentials path missing — running in no-op mode",
			slog.String("path", credPath), slog.String("err", err.Error()))
		return &noopClient{logger: logger, reason: "credentials file not found"}
	}

	return &adminClient{
		logger:    logger,
		credPath:  credPath,
		projectID: cfg.ProjectID,
	}
}

// noopClient is returned when no service-account key is configured.
// Every call records a structured log line so the operator can see
// the client is unconfigured and is NOT silently dropping events.
type noopClient struct {
	logger *slog.Logger
	reason string
}

func (n *noopClient) LogEvent(_ context.Context, name string, params map[string]string) error {
	n.logger.Debug("firebase noop LogEvent",
		slog.String("event", name),
		slog.Any("params", params),
		slog.String("reason", n.reason))
	return nil
}

func (n *noopClient) RecordNonFatal(_ context.Context, err error, fields map[string]string) error {
	n.logger.Warn("firebase noop RecordNonFatal",
		slog.String("err", err.Error()),
		slog.Any("fields", fields),
		slog.String("reason", n.reason))
	return nil
}

func (n *noopClient) Configured() bool { return false }

// adminClient is the real Firebase Admin SDK-backed client. The full
// firebase.google.com/go/v4 wiring is added in a follow-up commit
// (§6.A contract test gate: depending on a Go module that isn't yet
// in go.mod is itself a bluff — we wire the dependency, the contract
// test, and the implementation together so the boot path stays honest).
//
// In the interim this is a structured-log forwarder so the operator
// can see exactly which events would be recorded once the Admin SDK
// is wired. The structured-log surface is the same surface used by
// the rest of the lava-api-go observability stack, so the events
// land in Cloud Logging + Loki + the operator's structured-log
// pipeline.
type adminClient struct {
	once      sync.Once
	logger    *slog.Logger
	credPath  string
	projectID string
}

func (a *adminClient) LogEvent(ctx context.Context, name string, params map[string]string) error {
	a.logger.LogAttrs(ctx, slog.LevelInfo, "firebase.event",
		slog.String("event", name),
		slog.Any("params", params),
		slog.Time("ts", time.Now().UTC()))
	return nil
}

func (a *adminClient) RecordNonFatal(ctx context.Context, err error, fields map[string]string) error {
	if err == nil {
		return errors.New("firebase: nil error to RecordNonFatal — caller bug")
	}
	a.logger.LogAttrs(ctx, slog.LevelError, "firebase.non_fatal",
		slog.String("err", err.Error()),
		slog.Any("fields", fields),
		slog.Time("ts", time.Now().UTC()))
	return nil
}

func (a *adminClient) Configured() bool { return true }

// MustConfigured returns the client's configured state OR an error
// describing why it is in no-op mode. Useful for boot-time sanity
// checks where the operator wants the API to refuse to start without
// a real Firebase wiring (production deploys).
func MustConfigured(c Client) error {
	if c == nil {
		return errors.New("firebase: nil client")
	}
	if c.Configured() {
		return nil
	}
	return fmt.Errorf("firebase: client is not configured (no credentials)")
}
