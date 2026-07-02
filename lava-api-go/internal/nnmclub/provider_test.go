package nnmclub

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestProviderAdapter_ID(t *testing.T) {
	p := NewProviderAdapter(NewClient("http://localhost"))
	if p.ID() != "nnmclub" {
		t.Errorf("ID: got %q, want \"nnmclub\"", p.ID())
	}
}

func TestProviderAdapter_Capabilities(t *testing.T) {
	p := NewProviderAdapter(NewClient("http://localhost"))
	caps := p.Capabilities()
	if len(caps) == 0 {
		t.Error("expected non-empty capabilities")
	}
}

func TestProviderAdapter_Search(t *testing.T) {
	fixture := loadFixture(t, "search", "search_results.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	p := NewProviderAdapter(NewClient(srv.URL))
	result, err := p.Search(context.Background(), provider.SearchOpts{Query: "linux", Page: 1}, provider.Credentials{})
	if err != nil {
		t.Fatalf("Search error: %v", err)
	}
	if len(result.Results) != 2 {
		t.Errorf("expected 2 results, got %d", len(result.Results))
	}
}

func TestProviderAdapter_Browse(t *testing.T) {
	fixture := loadFixture(t, "browse", "browse_results.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	p := NewProviderAdapter(NewClient(srv.URL))
	result, err := p.Browse(context.Background(), "7", 1, provider.Credentials{})
	if err != nil {
		t.Fatalf("Browse error: %v", err)
	}
	if len(result.Items) != 1 {
		t.Errorf("expected 1 item, got %d", len(result.Items))
	}
}

func TestProviderAdapter_GetTopic(t *testing.T) {
	fixture := loadFixture(t, "topic", "topic_real.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Serve win-1251 like the real nnmclub.to so the full decode pipeline runs.
		w.Header().Set("Content-Type", "text/html; charset=windows-1251")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	p := NewProviderAdapter(NewClient(srv.URL))
	result, err := p.GetTopic(context.Background(), "1001", 1, provider.Credentials{})
	if err != nil {
		t.Fatalf("GetTopic error: %v", err)
	}
	if !strings.Contains(result.Title, "Учебная группа") {
		t.Errorf("Title: got %q, want it to contain \"Учебная группа\"", result.Title)
	}
	if strings.TrimSpace(result.Description) == "" {
		t.Error("Description: expected the real non-empty opening-post body, got EMPTY")
	}
}

func TestProviderAdapter_HealthCheck(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	p := NewProviderAdapter(NewClient(srv.URL))
	status, err := p.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck error: %v", err)
	}
	if !status.Healthy {
		t.Error("expected Healthy=true")
	}
}

func TestProviderAdapter_HealthCheck_Unhealthy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	p := NewProviderAdapter(NewClient(srv.URL))
	status, err := p.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck error: %v", err)
	}
	if status.Healthy {
		t.Error("expected Healthy=false")
	}
}
