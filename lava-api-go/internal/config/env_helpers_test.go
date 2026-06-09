package config

import (
	"testing"
	"time"
)

// envDuration / envInt are the config-default helpers Load() uses to read
// optional tuning knobs (timeouts, pool sizes) from the environment. They
// each have three real branches a misconfiguration would hit:
//   - var set to a valid value  → parsed value wins
//   - var unset (empty)         → documented default wins
//   - var set to garbage        → default wins (silent fallback, unlike
//                                  envBool which warns)
// Only the valid-value branch was previously exercised (envDuration sat at
// 50%, envInt had no direct test), so an operator typo silently falling
// back to the default — or a regression that made garbage parse to zero —
// would have been invisible. These tests assert the returned value, which
// is the value the server actually runs with.

func TestEnvDuration_ValidValueWins(t *testing.T) {
	t.Setenv("TEST_LAVA_DUR", "1500ms")
	got := envDuration("TEST_LAVA_DUR", 5*time.Second)
	if got != 1500*time.Millisecond {
		t.Errorf("envDuration valid: got %v, want 1.5s", got)
	}
}

func TestEnvDuration_UnsetReturnsDefault(t *testing.T) {
	t.Setenv("TEST_LAVA_DUR", "")
	got := envDuration("TEST_LAVA_DUR", 7*time.Second)
	if got != 7*time.Second {
		t.Errorf("envDuration unset: got %v, want 7s default", got)
	}
}

func TestEnvDuration_GarbageReturnsDefault(t *testing.T) {
	// "abc" is not a valid time.Duration; the helper must fall back to the
	// default rather than panic or yield a zero duration (a zero timeout
	// would mean "no timeout" in net/http — a real footgun).
	t.Setenv("TEST_LAVA_DUR", "abc")
	got := envDuration("TEST_LAVA_DUR", 3*time.Second)
	if got != 3*time.Second {
		t.Errorf("envDuration garbage: got %v, want 3s default", got)
	}
}

func TestEnvInt_ValidValueWins(t *testing.T) {
	t.Setenv("TEST_LAVA_INT", "42")
	got := envInt("TEST_LAVA_INT", 10)
	if got != 42 {
		t.Errorf("envInt valid: got %d, want 42", got)
	}
}

func TestEnvInt_UnsetReturnsDefault(t *testing.T) {
	t.Setenv("TEST_LAVA_INT", "")
	got := envInt("TEST_LAVA_INT", 99)
	if got != 99 {
		t.Errorf("envInt unset: got %d, want 99 default", got)
	}
}

func TestEnvInt_GarbageReturnsDefault(t *testing.T) {
	t.Setenv("TEST_LAVA_INT", "not-a-number")
	got := envInt("TEST_LAVA_INT", 11)
	if got != 11 {
		t.Errorf("envInt garbage: got %d, want 11 default", got)
	}
}
