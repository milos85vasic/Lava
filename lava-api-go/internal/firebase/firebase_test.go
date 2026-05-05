package firebase

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

// TestNew_NoCreds_ReturnsNoop verifies the boot-time fallback per §6.J:
// when no credentials are configured, the client is a no-op (NOT nil)
// and Configured() reports false honestly.
func TestNew_NoCreds_ReturnsNoop(t *testing.T) {
	t.Setenv("GOOGLE_APPLICATION_CREDENTIALS", "")
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	c := New(Config{Logger: logger})
	if c == nil {
		t.Fatal("New must never return nil")
	}
	if c.Configured() {
		t.Fatal("expected Configured()=false with no credentials")
	}

	// LogEvent + RecordNonFatal MUST succeed (they're best-effort)
	// rather than failing the call site.
	if err := c.LogEvent(context.Background(), "lava_test_event", map[string]string{"k": "v"}); err != nil {
		t.Fatalf("LogEvent on noop: unexpected err %v", err)
	}
	if err := c.RecordNonFatal(context.Background(), errors.New("boom"), nil); err != nil {
		t.Fatalf("RecordNonFatal on noop: unexpected err %v", err)
	}
}

// TestNew_MissingFile_ReturnsNoop verifies that a configured-but-absent
// credentials file degrades to no-op (not panic) — matches §6.H operator
// expectation that ops doesn't lose the API to a missing key.
func TestNew_MissingFile_ReturnsNoop(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	c := New(Config{
		CredentialsPath: "/path/that/definitely/does/not/exist.json",
		Logger:          logger,
	})
	if c.Configured() {
		t.Fatal("expected Configured()=false with missing file")
	}
}

// TestNew_RealFile_ReturnsConfigured verifies that a present credentials
// file moves the client into the configured branch. Anti-bluff: the
// configured branch's structured logs ARE the gate's user-visible
// signal until the full Admin-SDK wiring lands.
func TestNew_RealFile_ReturnsConfigured(t *testing.T) {
	dir := t.TempDir()
	credPath := filepath.Join(dir, "key.json")
	if err := os.WriteFile(credPath, []byte(`{"type":"service_account","project_id":"lava-test"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	c := New(Config{CredentialsPath: credPath, ProjectID: "lava-test", Logger: logger})
	if !c.Configured() {
		t.Fatal("expected Configured()=true with present credentials file")
	}

	if err := c.RecordNonFatal(context.Background(), nil, nil); err == nil {
		t.Fatal("RecordNonFatal(nil) must error — caller-bug guard")
	}
}

// TestMustConfigured verifies the boot-time strict mode for production deploys.
func TestMustConfigured(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	noop := New(Config{Logger: logger})
	if err := MustConfigured(noop); err == nil {
		t.Fatal("MustConfigured(noop) must error")
	}

	dir := t.TempDir()
	credPath := filepath.Join(dir, "key.json")
	_ = os.WriteFile(credPath, []byte(`{}`), 0o600)
	real := New(Config{CredentialsPath: credPath, Logger: logger})
	if err := MustConfigured(real); err != nil {
		t.Fatalf("MustConfigured(real): unexpected err %v", err)
	}

	if err := MustConfigured(nil); err == nil {
		t.Fatal("MustConfigured(nil) must error")
	}
}
