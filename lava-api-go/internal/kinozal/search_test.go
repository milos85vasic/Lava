package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func loadTestData(name string) []byte {
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		panic(err)
	}
	return b
}

func TestParseSearchPage(t *testing.T) {
	html := loadTestData("search/search_results.html")
	result, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(result.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(result.Results))
	}
	r := result.Results[0]
	if r.Title != "Test Movie 2024" {
		t.Errorf("expected title 'Test Movie 2024', got %s", r.Title)
	}
	if r.ID != "12345" {
		t.Errorf("expected id 12345, got %s", r.ID)
	}
	if r.Size != "1.5 GB" {
		t.Errorf("expected size '1.5 GB', got %s", r.Size)
	}
	if r.Seeders != 12 {
		t.Errorf("expected seeders 12, got %d", r.Seeders)
	}
	if r.Leechers != 3 {
		t.Errorf("expected leechers 3, got %d", r.Leechers)
	}
	if result.TotalPages != 3 {
		t.Errorf("expected total pages 3, got %d", result.TotalPages)
	}
}

func TestClientSearch(t *testing.T) {
	html := loadTestData("search/search_results.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/browse.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("s") != "test" {
			t.Errorf("unexpected query: %s", r.URL.Query().Get("s"))
		}
		w.WriteHeader(http.StatusOK)
		w.Write(html)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "test", 0, "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(result.Results))
	}
}
