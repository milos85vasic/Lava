package nnmclub

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func loadFixture(t *testing.T, scope, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", scope, name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

func TestParseSearchPage_HappyPath(t *testing.T) {
	html := loadFixture(t, "search", "search_results.html")
	out, err := ParseSearchPage(html, 1)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseSearchPage returned nil")
	}
	if out.Page != 1 {
		t.Errorf("Page: got %d, want 1", out.Page)
	}
	if out.TotalPages != 2 {
		t.Errorf("TotalPages: got %d, want 2", out.TotalPages)
	}
	if got := len(out.Results); got != 2 {
		t.Fatalf("expected 2 results, got %d", got)
	}

	r0 := out.Results[0]
	if r0.ID != "1001" {
		t.Errorf("results[0].ID: got %q, want \"1001\"", r0.ID)
	}
	if r0.Title != "Ubuntu 24.04 LTS" {
		t.Errorf("results[0].Title: got %q, want \"Ubuntu 24.04 LTS\"", r0.Title)
	}
	if r0.Seeders != 12 {
		t.Errorf("results[0].Seeders: got %d, want 12", r0.Seeders)
	}
	if r0.Leechers != 3 {
		t.Errorf("results[0].Leechers: got %d, want 3", r0.Leechers)
	}
	if r0.Size != "4.5 GB" {
		t.Errorf("results[0].Size: got %q, want \"4.5 GB\"", r0.Size)
	}

	r1 := out.Results[1]
	if r1.ID != "1002" {
		t.Errorf("results[1].ID: got %q, want \"1002\"", r1.ID)
	}
}

func TestParseSearchPage_Empty(t *testing.T) {
	html := loadFixture(t, "search", "search_empty.html")
	out, err := ParseSearchPage(html, 1)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if got := len(out.Results); got != 0 {
		t.Errorf("expected 0 results, got %d", got)
	}
	if out.TotalPages != 1 {
		t.Errorf("TotalPages: got %d, want 1", out.TotalPages)
	}
}

func TestGetSearchPage_BuildsUpstreamURL(t *testing.T) {
	fixture := loadFixture(t, "search", "search_results.html")
	var captured *http.Request
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured = r
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.GetSearchPage(context.Background(), provider.SearchOpts{
		Query: "linux",
		Page:  2,
	}, "")
	if err != nil {
		t.Fatalf("GetSearchPage error: %v", err)
	}
	if captured == nil {
		t.Fatal("captured request is nil")
	}
	if captured.URL.Path != "/forum/tracker.php" {
		t.Errorf("Path: got %q, want \"/forum/tracker.php\"", captured.URL.Path)
	}
	q := captured.URL.Query()
	if q.Get("nm") != "linux" {
		t.Errorf("nm: got %q, want \"linux\"", q.Get("nm"))
	}
	if q.Get("start") != "50" {
		t.Errorf("start: got %q, want \"50\"", q.Get("start"))
	}
}
