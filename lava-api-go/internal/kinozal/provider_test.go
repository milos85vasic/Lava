package kinozal

import (
	"context"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestProviderAdapterCapabilities(t *testing.T) {
	p := NewProviderAdapter(NewClient("http://localhost"))
	caps := p.Capabilities()
	if len(caps) == 0 {
		t.Fatal("expected capabilities")
	}
	if p.ID() != "kinozal" {
		t.Errorf("unexpected id: %s", p.ID())
	}
	if p.DisplayName() != "Kinozal.tv" {
		t.Errorf("unexpected name: %s", p.DisplayName())
	}
	if p.AuthType() != provider.AuthFormLogin {
		t.Errorf("unexpected auth type: %s", p.AuthType())
	}
	if p.Encoding() != "windows-1251" {
		t.Errorf("unexpected encoding: %s", p.Encoding())
	}
}

func TestProviderAdapterHealthCheck(t *testing.T) {
	p := NewProviderAdapter(NewClient("http://localhost"))
	hs, err := p.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// localhost:80 is unlikely to be up, so expect unhealthy.
	if hs.Healthy {
		t.Log("health check reported healthy against localhost — unexpected but not a failure in isolation")
	}
}
