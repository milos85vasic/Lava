package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func newTestAdapter(srv *httptest.Server) *ProviderAdapter {
	return NewProviderAdapter(NewClient(srv.URL))
}

func TestProviderAdapter_Metadata(t *testing.T) {
	a := newTestAdapter(httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})))

	if a.ID() != "archiveorg" {
		t.Errorf("ID=%q want archiveorg", a.ID())
	}
	if a.DisplayName() != "Internet Archive" {
		t.Errorf("DisplayName=%q want 'Internet Archive'", a.DisplayName())
	}
	if a.AuthType() != provider.AuthNone {
		t.Errorf("AuthType=%q want NONE", a.AuthType())
	}
	if a.Encoding() != "UTF-8" {
		t.Errorf("Encoding=%q want UTF-8", a.Encoding())
	}

	caps := a.Capabilities()
	wantCaps := map[provider.ProviderCapability]bool{
		provider.CapSearch:       false,
		provider.CapBrowse:       false,
		provider.CapForumTree:    false,
		provider.CapTopic:        false,
		provider.CapHTTPDownload: false,
	}
	for _, c := range caps {
		if _, ok := wantCaps[c]; !ok {
			t.Errorf("unexpected capability %q", c)
		}
		wantCaps[c] = true
	}
	for c, found := range wantCaps {
		if !found {
			t.Errorf("missing capability %q", c)
		}
	}
}

func TestProviderAdapter_Search(t *testing.T) {
	jsonBody := `{"response":{"numFound":1,"start":0,"docs":[{"identifier":"id-1","title":"Title"}]}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	result, err := a.Search(context.Background(), provider.SearchOpts{Query: "test", Page: 1}, provider.Credentials{})
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if result.Provider != "archiveorg" {
		t.Errorf("Provider=%q want archiveorg", result.Provider)
	}
	if len(result.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(result.Results))
	}
	if result.Results[0].ID != "id-1" {
		t.Errorf("Results[0].ID=%q want id-1", result.Results[0].ID)
	}
}

func TestProviderAdapter_Browse(t *testing.T) {
	jsonBody := `{"response":{"numFound":1,"start":0,"docs":[{"identifier":"id-2","title":"Browse Title"}]}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	result, err := a.Browse(context.Background(), "movies", 1, provider.Credentials{})
	if err != nil {
		t.Fatalf("Browse: %v", err)
	}
	if result.Provider != "archiveorg" {
		t.Errorf("Provider=%q want archiveorg", result.Provider)
	}
	if len(result.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(result.Items))
	}
}

func TestProviderAdapter_GetForumTree(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	tree, err := a.GetForumTree(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("GetForumTree: %v", err)
	}
	if tree.Provider != "archiveorg" {
		t.Errorf("Provider=%q want archiveorg", tree.Provider)
	}
	if len(tree.Categories) != 5 {
		t.Fatalf("expected 5 categories, got %d", len(tree.Categories))
	}
	expected := []struct{ id, name string }{
		{"movies", "Movies"},
		{"audio", "Audio"},
		{"texts", "Texts"},
		{"software", "Software"},
		{"image", "Image"},
	}
	for i, exp := range expected {
		if tree.Categories[i].ID != exp.id {
			t.Errorf("categories[%d].ID=%q want %q", i, tree.Categories[i].ID, exp.id)
		}
		if tree.Categories[i].Name != exp.name {
			t.Errorf("categories[%d].Name=%q want %q", i, tree.Categories[i].Name, exp.name)
		}
	}
}

func TestProviderAdapter_GetTopic(t *testing.T) {
	jsonBody := `{"metadata":{"title":"Topic Title","creator":"Alice"},"files":[{"name":"f.txt"}]}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	result, err := a.GetTopic(context.Background(), "topic-1", 0, provider.Credentials{})
	if err != nil {
		t.Fatalf("GetTopic: %v", err)
	}
	if result.ID != "topic-1" {
		t.Errorf("ID=%q want topic-1", result.ID)
	}
	if result.Title != "Topic Title" {
		t.Errorf("Title=%q want 'Topic Title'", result.Title)
	}
	if len(result.Files) != 1 {
		t.Fatalf("expected 1 file, got %d", len(result.Files))
	}
}

func TestProviderAdapter_GetTorrent_Unsupported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	_, err := a.GetTorrent(context.Background(), "x", provider.Credentials{})
	if err != provider.ErrUnsupported {
		t.Fatalf("expected ErrUnsupported, got %v", err)
	}
}

func TestProviderAdapter_DownloadFile(t *testing.T) {
	wantBody := []byte("download content")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/download/item-1/file.txt" {
			t.Errorf("path=%q want /download/item-1/file.txt", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(wantBody)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	result, err := a.DownloadFile(context.Background(), "item-1/file.txt", provider.Credentials{})
	if err != nil {
		t.Fatalf("DownloadFile: %v", err)
	}
	if result.Filename != "file.txt" {
		t.Errorf("Filename=%q want file.txt", result.Filename)
	}
	if string(result.Body) != string(wantBody) {
		t.Errorf("Body mismatch")
	}
}

func TestProviderAdapter_DownloadFile_InvalidID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	_, err := a.DownloadFile(context.Background(), "noseparator", provider.Credentials{})
	if err == nil {
		t.Fatal("expected error for invalid id, got nil")
	}
}

func TestProviderAdapter_CheckAuth_AlwaysTrue(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	ok, err := a.CheckAuth(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("CheckAuth: %v", err)
	}
	if !ok {
		t.Error("CheckAuth should return true for no-auth provider")
	}
}

func TestProviderAdapter_Login_Unsupported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	_, err := a.Login(context.Background(), provider.LoginOpts{})
	if err != provider.ErrUnsupported {
		t.Fatalf("expected ErrUnsupported, got %v", err)
	}
}

func TestProviderAdapter_HealthCheck_Healthy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			t.Errorf("method=%q want HEAD", r.Method)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	status, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck: %v", err)
	}
	if !status.Healthy {
		t.Error("expected Healthy=true")
	}
	if status.ResponseTime < 0 {
		t.Error("expected non-negative ResponseTime")
	}
}

func TestProviderAdapter_HealthCheck_Unhealthy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	a := newTestAdapter(srv)
	status, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck: %v", err)
	}
	if status.Healthy {
		t.Error("expected Healthy=false for 500")
	}
}
