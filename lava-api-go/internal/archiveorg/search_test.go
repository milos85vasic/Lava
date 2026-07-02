package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestSearch_HappyPath drives the REAL archiveorg search parser against a
// verbatim captured response (§6.D fixture honesty). testdata/ubuntu_search.json
// is a byte-for-byte capture of the live archive.org advancedsearch.php JSON API
// (q=ubuntu, output=json, rows=5, page=1, sort[]=downloads desc) taken 2026-07-02
// — NOT a fabricated "Test Item One" document. The capture exercises real
// upstream shapes the parser must survive: a numeric year (2007 as a JSON
// integer, not a string), a null year, a null creator, and a >2^31 item_size —
// all of which flow through the production searchDoc/flexString decode paths.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — Bluff-Audit stamp:
//   Mutation: in search.go, change searchDoc.Identifier's tag json:"identifier"
//             to json:"nonexistent" (wrong JSON path).
//   Observed: TestSearch_HappyPath →
//             `items[0].ID="" want ArchiveIt-Collection-641`.
//   Reverted: yes.
func TestSearch_HappyPath(t *testing.T) {
	body, err := os.ReadFile(filepath.Join("testdata", "ubuntu_search.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/advancedsearch.php" {
			t.Errorf("path=%q want /advancedsearch.php", r.URL.Path)
		}
		q := r.URL.Query()
		if q.Get("q") != "ubuntu" {
			t.Errorf("q=%q want ubuntu", q.Get("q"))
		}
		if q.Get("output") != "json" {
			t.Errorf("output=%q want json", q.Get("output"))
		}
		if q.Get("page") != "1" {
			t.Errorf("page=%q want 1", q.Get("page"))
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "ubuntu", 1)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if result.Page != 1 {
		t.Errorf("Page=%d want 1", result.Page)
	}
	// Real numFound=15599 → ceil(15599/50)=312 pages.
	if result.TotalPages != 312 {
		t.Errorf("TotalPages=%d want 312 (real numFound=15599)", result.TotalPages)
	}
	// The captured response carries 5 real docs (rows=5).
	if len(result.Items) != 5 {
		t.Fatalf("expected 5 items, got %d", len(result.Items))
	}

	// Row 0: real string fields + a NUMERIC year (2007) that must decode to the
	// string "2007" via flexString's number branch (LVA-020 real-shape guard).
	it0 := result.Items[0]
	if it0.ID != "ArchiveIt-Collection-641" {
		t.Errorf("items[0].ID=%q want ArchiveIt-Collection-641", it0.ID)
	}
	if it0.Title != "Free and Open Source Software" {
		t.Errorf("items[0].Title=%q want 'Free and Open Source Software'", it0.Title)
	}
	if it0.Creator != "John Gilmore" {
		t.Errorf("items[0].Creator=%q want 'John Gilmore'", it0.Creator)
	}
	if it0.Downloads != 4840082 {
		t.Errorf("items[0].Downloads=%d want 4840082", it0.Downloads)
	}
	if it0.SizeBytes != 2555 {
		t.Errorf("items[0].SizeBytes=%d want 2555", it0.SizeBytes)
	}
	if it0.MediaType != "collection" {
		t.Errorf("items[0].MediaType=%q want collection", it0.MediaType)
	}
	if it0.Year != "2007" {
		t.Errorf("items[0].Year=%q want 2007 (numeric year → flexString)", it0.Year)
	}

	// Row 1: real >2^31 item_size (must survive int64) + a JSON null year that
	// flexString maps to "".
	it1 := result.Items[1]
	if it1.ID != "tiny-11-NTDEV" {
		t.Errorf("items[1].ID=%q want tiny-11-NTDEV", it1.ID)
	}
	if it1.Title != "Tiny11" {
		t.Errorf("items[1].Title=%q want Tiny11", it1.Title)
	}
	if it1.SizeBytes != 19947578996 {
		t.Errorf("items[1].SizeBytes=%d want 19947578996 (int64 > 2^31)", it1.SizeBytes)
	}
	if it1.Year != "" {
		t.Errorf("items[1].Year=%q want empty (JSON null year → flexString \"\")", it1.Year)
	}

	// Row 2: real JSON null creator → flexString "".
	it2 := result.Items[2]
	if it2.ID != "1636PokemonFireRedUSquirrels" {
		t.Errorf("items[2].ID=%q want 1636PokemonFireRedUSquirrels", it2.ID)
	}
	if it2.Creator != "" {
		t.Errorf("items[2].Creator=%q want empty (JSON null creator → flexString \"\")", it2.Creator)
	}
}

func TestSearch_EmptyResult(t *testing.T) {
	jsonBody := `{"response":{"numFound":0,"start":0,"docs":[]}}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "nonsense", 1)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if len(result.Items) != 0 {
		t.Errorf("expected 0 items, got %d", len(result.Items))
	}
	if result.TotalPages != 1 {
		t.Errorf("TotalPages=%d want 1", result.TotalPages)
	}
}

func TestSearch_PageDefaultsToOne(t *testing.T) {
	var capturedPage string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPage = r.URL.Query().Get("page")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"response":{"numFound":0,"start":0,"docs":[]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Search(context.Background(), "x", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if capturedPage != "1" {
		t.Errorf("page=%q want 1 when caller passes 0", capturedPage)
	}
}

func TestSearch_InvalidJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{not json`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Search(context.Background(), "x", 1)
	if err == nil {
		t.Fatal("expected error for invalid JSON, got nil")
	}
	if !strings.Contains(err.Error(), "unmarshal") {
		t.Errorf("error should mention unmarshal, got: %v", err)
	}
}
