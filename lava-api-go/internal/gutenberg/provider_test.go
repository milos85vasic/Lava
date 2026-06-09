package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestProviderAdapter_Metadata(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	if a.ID() != "gutenberg" {
		t.Errorf("id=%q want gutenberg", a.ID())
	}
	if a.DisplayName() != "Project Gutenberg" {
		t.Errorf("displayName=%q want Project Gutenberg", a.DisplayName())
	}
	if a.AuthType() != provider.AuthNone {
		t.Errorf("authType=%q want NONE", a.AuthType())
	}
	if a.Encoding() != "UTF-8" {
		t.Errorf("encoding=%q want UTF-8", a.Encoding())
	}
	caps := a.Capabilities()
	if len(caps) != 4 {
		t.Fatalf("expected 4 capabilities, got %d", len(caps))
	}
}

func TestProviderAdapter_GetForumTree(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	tree, err := a.GetForumTree(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("GetForumTree error: %v", err)
	}
	if tree == nil {
		t.Fatal("tree is nil")
	}
	if len(tree.Categories) != 6 {
		t.Errorf("categories=%d want 6", len(tree.Categories))
	}
}

func TestProviderAdapter_UnsupportedMethods(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	ctx := context.Background()
	cred := provider.Credentials{}

	if _, err := a.GetTorrent(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("GetTorrent: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.GetComments(ctx, "1", 0, cred); err != provider.ErrUnsupported {
		t.Errorf("GetComments: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.AddComment(ctx, "1", "hi", cred); err != provider.ErrUnsupported {
		t.Errorf("AddComment: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.GetFavorites(ctx, cred); err != provider.ErrUnsupported {
		t.Errorf("GetFavorites: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.AddFavorite(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("AddFavorite: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.RemoveFavorite(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("RemoveFavorite: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.Login(ctx, provider.LoginOpts{}); err != provider.ErrUnsupported {
		t.Errorf("Login: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.FetchCaptcha(ctx, ""); err != provider.ErrUnsupported {
		t.Errorf("FetchCaptcha: expected ErrUnsupported, got %v", err)
	}
}

// gutendexJSON is one Gutendex book record reused across the delegating-method
// tests below. It carries enough fields to prove the adapter forwards the real
// upstream payload through to the mapped provider result (not a stub).
const gutendexOneBook = `{
	"count": 1,
	"results": [
		{"id":1342,"title":"Pride and Prejudice","authors":[{"name":"Austen, Jane"}],
		 "formats":{"application/epub+zip":"http://x/1342.epub","text/plain":"http://x/1342.txt"},
		 "download_count":99,"subjects":["Fiction"],"languages":["en"]}
	]
}`

// TestProviderAdapter_Search_DelegatesAndMaps drives ProviderAdapter.Search end
// to end against a real HTTP server (the only faked boundary). It asserts the
// adapter actually issues the upstream search request AND that the upstream
// record reaches the caller's SearchResult — proving Search is real delegation,
// not a stub.
//
// Falsifiability: change Search to `return nil, nil` → "Search returned nil
// result". Change it to ignore opts.Query → capturedQuery mismatch fails.
func TestProviderAdapter_Search_DelegatesAndMaps(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(gutendexOneBook))
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	res, err := a.Search(context.Background(), provider.SearchOpts{Query: "pride", Page: 1}, provider.Credentials{})
	if err != nil {
		t.Fatalf("Search error: %v", err)
	}
	if res == nil {
		t.Fatal("Search returned nil result")
	}
	if capturedPath != "/books/?page=1&search=pride" {
		t.Errorf("upstream path=%q, want /books/?page=1&search=pride (Search must forward the query)", capturedPath)
	}
	if len(res.Results) != 1 || res.Results[0].ID != "1342" || res.Results[0].Title != "Pride and Prejudice" {
		t.Errorf("Search results=%+v, want one item id=1342 title=Pride and Prejudice", res.Results)
	}
}

// TestProviderAdapter_Browse_DelegatesAndMaps drives ProviderAdapter.Browse end
// to end. Falsifiability: `return nil, nil` → "Browse returned nil"; dropping
// the categoryID forward → capturedPath topic= mismatch.
func TestProviderAdapter_Browse_DelegatesAndMaps(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(gutendexOneBook))
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	res, err := a.Browse(context.Background(), "fiction", 3, provider.Credentials{})
	if err != nil {
		t.Fatalf("Browse error: %v", err)
	}
	if res == nil {
		t.Fatal("Browse returned nil")
	}
	if capturedPath != "/books/?page=3&topic=fiction" {
		t.Errorf("upstream path=%q, want /books/?page=3&topic=fiction", capturedPath)
	}
	if len(res.Items) != 1 || res.Items[0].ID != "1342" {
		t.Errorf("Browse items=%+v, want one item id=1342", res.Items)
	}
}

// TestProviderAdapter_GetTopic_DelegatesAndMaps drives ProviderAdapter.GetTopic
// end to end against the single-book endpoint. Falsifiability: `return nil, nil`
// → "GetTopic returned nil"; returning the wrong id → Title mismatch.
func TestProviderAdapter_GetTopic_DelegatesAndMaps(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":1342,"title":"Pride and Prejudice",
			"authors":[{"name":"Austen, Jane"}],
			"formats":{"text/plain":"http://x/1342.txt"},
			"download_count":99,"subjects":["Fiction"],"languages":["en"]}`))
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	res, err := a.GetTopic(context.Background(), "1342", 0, provider.Credentials{})
	if err != nil {
		t.Fatalf("GetTopic error: %v", err)
	}
	if res == nil {
		t.Fatal("GetTopic returned nil")
	}
	if capturedPath != "/books/1342/" {
		t.Errorf("upstream path=%q, want /books/1342/", capturedPath)
	}
	if res.ID != "1342" || res.Title != "Pride and Prejudice" {
		t.Errorf("GetTopic result={%q,%q}, want {1342,Pride and Prejudice}", res.ID, res.Title)
	}
}

// TestProviderAdapter_DownloadFile_DelegatesAndReturnsBytes drives
// ProviderAdapter.DownloadFile end to end: first the metadata fetch to resolve
// the format URL, then the actual byte download. Asserts the real bytes reach
// the caller. Falsifiability: `return nil, nil` → "DownloadFile returned nil";
// wrong byte plumbing → Data mismatch.
func TestProviderAdapter_DownloadFile_DelegatesAndReturnsBytes(t *testing.T) {
	const body = "THE FULL TEXT OF THE BOOK"
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/books/1342/" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"id":1342,"title":"Pride and Prejudice",
				"authors":[{"name":"Austen, Jane"}],
				"formats":{"text/plain; charset=utf-8":"` + srv.URL + `/1342.txt"},
				"download_count":99,"subjects":["Fiction"],"languages":["en"]}`))
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	dl, err := a.DownloadFile(context.Background(), "1342", provider.Credentials{})
	if err != nil {
		t.Fatalf("DownloadFile error: %v", err)
	}
	if dl == nil {
		t.Fatal("DownloadFile returned nil")
	}
	if string(dl.Body) != body {
		t.Errorf("DownloadFile bytes=%q, want %q", string(dl.Body), body)
	}
}

// TestProviderAdapter_HealthCheck_TrueOnReachable asserts HealthCheck reports
// Healthy=true when the upstream books endpoint responds. Falsifiability:
// invert the success branch (`Healthy:false`) → "want Healthy=true".
func TestProviderAdapter_HealthCheck_TrueOnReachable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"count":0,"results":[]}`))
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	st, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck error: %v", err)
	}
	if st == nil || !st.Healthy {
		t.Errorf("HealthCheck=%+v, want Healthy=true for a reachable upstream", st)
	}
}

// TestProviderAdapter_HealthCheck_FalseOnUnreachable asserts HealthCheck reports
// Healthy=false (and NO error — the failure is surfaced via the status, per the
// adapter's no-telemetry comment) when the upstream returns an error status.
// Falsifiability: change the error branch to `Healthy:true` → "want Healthy=false".
func TestProviderAdapter_HealthCheck_FalseOnUnreachable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	a := NewProviderAdapter(NewClient(srv.URL))
	st, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck should surface failure via status, not error; got err=%v", err)
	}
	if st == nil || st.Healthy {
		t.Errorf("HealthCheck=%+v, want Healthy=false for an unreachable upstream", st)
	}
}

func TestProviderAdapter_CheckAuth(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	ok, err := a.CheckAuth(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("CheckAuth error: %v", err)
	}
	if !ok {
		t.Error("CheckAuth should return true for no-auth provider")
	}
}
