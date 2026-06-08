package rutracker

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// readFixtureFor loads a checked-in HTML fixture by category+name. Mirrors
// the per-category loaders already used in this package; centralised here so
// the adapter-level test can serve every endpoint's golden HTML.
func readFixtureFor(t *testing.T, category, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", category, name))
	if err != nil {
		t.Fatalf("read fixture %s/%s: %v", category, name, err)
	}
	return b
}

// adapterFixtureServer serves the real rutracker fixtures keyed on the URL
// path each Client method fetches, so the ProviderAdapter delegation methods
// can be driven end-to-end through the REAL *Client (real HTTP round-trip,
// real parsers) — the same path a live request takes, minus the live host.
func adapterFixtureServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/index.php"):
			_, _ = w.Write(readFixtureFor(t, "forum", "forum_tree.html"))
		case strings.HasPrefix(r.URL.Path, "/tracker.php"):
			_, _ = w.Write(readFixtureFor(t, "search", "search_results.html"))
		case strings.HasPrefix(r.URL.Path, "/viewforum.php"):
			_, _ = w.Write(readFixtureFor(t, "forum", "category_page1.html"))
		case strings.HasPrefix(r.URL.Path, "/viewtopic.php"):
			_, _ = w.Write(readFixtureFor(t, "topic", "topic_torrent.html"))
		case strings.HasPrefix(r.URL.Path, "/bookmarks.php"):
			_, _ = w.Write(readFixtureFor(t, "favorites", "page1.html"))
		case strings.HasPrefix(r.URL.Path, "/dl.php"):
			w.Header().Set("Content-Type", "application/x-bittorrent")
			w.Header().Set("Content-Disposition", `attachment; filename="movie.torrent"`)
			_, _ = w.Write([]byte("d8:announce4:teste"))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

func cookieCred() provider.Credentials {
	return provider.Credentials{Type: "cookie", CookieValue: "bb_session=valid"}
}

// TestAdapter_Search_E2E drives ProviderAdapter.Search through the real
// Client + parser against the search fixture and asserts the user-visible
// search rows surface (titles on the wire are what fill the results list).
func TestAdapter_Search_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	res, err := a.Search(context.Background(), provider.SearchOpts{Query: "movie"}, cookieCred())
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", res.Provider)
	}
	if len(res.Results) == 0 {
		t.Fatal("Search returned 0 results, want the fixture rows")
	}
	var titles []string
	for _, it := range res.Results {
		titles = append(titles, it.Title)
	}
	joined := strings.Join(titles, "|")
	if !strings.Contains(joined, "Plain Title") {
		t.Errorf("results titles = %q, want one containing 'Plain Title'", joined)
	}
}

// TestAdapter_Search_AnonymousUnauthorized verifies the anonymous-search
// short-circuit maps to provider.ErrUnauthorized — the user sees 401, not a
// silent empty page. Exercises the credToCookie("") → ErrUnauthorized →
// mapError path of the adapter.
func TestAdapter_Search_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	_, err := a.Search(context.Background(), provider.SearchOpts{Query: "x"}, provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous Search err = %v, want provider.ErrUnauthorized", err)
	}
}

// TestAdapter_Browse_E2E drives ProviderAdapter.Browse against a category
// fixture and asserts the mapped browse rows.
func TestAdapter_Browse_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	res, err := a.Browse(context.Background(), "100", 0, cookieCred())
	if err != nil {
		t.Fatalf("Browse: %v", err)
	}
	if res.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", res.Provider)
	}
	if len(res.Items) == 0 {
		t.Error("Browse returned 0 items, want the category fixture rows")
	}
}

// TestAdapter_GetForumTree_E2E drives the forum-tree delegation and asserts
// the recursive category mapping surfaces the top-level catalogue sections.
func TestAdapter_GetForumTree_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	tree, err := a.GetForumTree(context.Background(), cookieCred())
	if err != nil {
		t.Fatalf("GetForumTree: %v", err)
	}
	if len(tree.Categories) == 0 {
		t.Fatal("GetForumTree returned 0 categories")
	}
	if tree.Categories[0].Name != "Movies" {
		t.Errorf("first category = %q, want Movies", tree.Categories[0].Name)
	}
}

// TestAdapter_GetTopic_E2E drives the topic delegation against the torrent
// fixture and asserts the mapped topic identity + magnet link (the user's
// download target).
func TestAdapter_GetTopic_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	topic, err := a.GetTopic(context.Background(), "42", 1, cookieCred())
	if err != nil {
		t.Fatalf("GetTopic: %v", err)
	}
	if topic.ID != "42" || topic.Title != "Movie" {
		t.Errorf("topic = {%q,%q}, want {42,Movie}", topic.ID, topic.Title)
	}
	if !strings.HasPrefix(topic.MagnetLink, "magnet:?") {
		t.Errorf("MagnetLink = %q, want a magnet: URI", topic.MagnetLink)
	}
}

// TestAdapter_GetComments_E2E drives the comments delegation against the
// topic fixture (comments share the viewtopic endpoint) and asserts a valid
// mapped result with the correct provider id.
func TestAdapter_GetComments_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	res, err := a.GetComments(context.Background(), "42", 1, cookieCred())
	if err != nil {
		t.Fatalf("GetComments: %v", err)
	}
	if res.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", res.Provider)
	}
}

// TestAdapter_GetTorrent_E2E drives the .torrent metadata delegation and
// asserts the filename (from Content-Disposition), content type, and the
// real torrent bytes — exactly what the client saves.
func TestAdapter_GetTorrent_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	tr, err := a.GetTorrent(context.Background(), "42", cookieCred())
	if err != nil {
		t.Fatalf("GetTorrent: %v", err)
	}
	if tr.Filename != "movie.torrent" {
		t.Errorf("Filename = %q, want movie.torrent (from Content-Disposition)", tr.Filename)
	}
	if tr.ContentType != "application/x-bittorrent" {
		t.Errorf("ContentType = %q, want application/x-bittorrent", tr.ContentType)
	}
	if string(tr.Body) != "d8:announce4:teste" {
		t.Errorf("Body = %q, want the served torrent bytes", string(tr.Body))
	}
}

// TestAdapter_DownloadFile_E2E drives the binary download delegation — the
// same upstream call as GetTorrent but mapped into a FileDownload.
func TestAdapter_DownloadFile_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	dl, err := a.DownloadFile(context.Background(), "42", cookieCred())
	if err != nil {
		t.Fatalf("DownloadFile: %v", err)
	}
	if dl.ContentType != "application/x-bittorrent" {
		t.Errorf("ContentType = %q, want application/x-bittorrent", dl.ContentType)
	}
	if string(dl.Body) != "d8:announce4:teste" {
		t.Errorf("Body = %q, want the served torrent bytes", string(dl.Body))
	}
}

// TestAdapter_GetTorrent_AnonymousUnauthorized verifies the anonymous
// download short-circuit maps to provider.ErrUnauthorized — anonymous users
// cannot download .torrent files; the adapter must surface 401, not 200.
func TestAdapter_GetTorrent_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	_, err := a.GetTorrent(context.Background(), "42", provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous GetTorrent err = %v, want provider.ErrUnauthorized", err)
	}
}

// TestAdapter_HealthCheck_Healthy verifies HealthCheck reports Healthy=true
// when the forum endpoint responds. This is the value the /health endpoint
// surfaces to ops.
func TestAdapter_HealthCheck_Healthy(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	hs, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck: %v", err)
	}
	if !hs.Healthy {
		t.Error("HealthCheck Healthy = false, want true (forum endpoint responded)")
	}
}

// TestAdapter_GetFavorites_E2E drives the favorites delegation against the
// authorised bookmarks fixture and asserts the mapped result.
func TestAdapter_GetFavorites_E2E(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	res, err := a.GetFavorites(context.Background(), cookieCred())
	if err != nil {
		t.Fatalf("GetFavorites: %v", err)
	}
	if res.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", res.Provider)
	}
	if len(res.Items) == 0 {
		t.Error("GetFavorites returned 0 items, want the bookmarks fixture rows")
	}
}

// TestAdapter_GetFavorites_AnonymousUnauthorized verifies anonymous users
// get 401 for favorites — favorites are per-account and require a cookie.
func TestAdapter_GetFavorites_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	_, err := a.GetFavorites(context.Background(), provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous GetFavorites err = %v, want provider.ErrUnauthorized", err)
	}
}

// TestAdapter_CheckAuth_Authorised verifies CheckAuth returns true when the
// index page carries the logged-in marker — the user's session is valid.
func TestAdapter_CheckAuth_Authorised(t *testing.T) {
	// Serve an authorised index page (the favorites fixture carries the
	// logged-in-username marker IsAuthorised looks for).
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(readFixtureFor(t, "favorites", "page1.html"))
	}))
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	ok, err := a.CheckAuth(context.Background(), cookieCred())
	if err != nil {
		t.Fatalf("CheckAuth: %v", err)
	}
	if !ok {
		t.Error("CheckAuth = false, want true (index page has logged-in marker)")
	}
}

// TestAdapter_CheckAuth_Unauthorised verifies CheckAuth returns false when
// the index page lacks the marker (cookie expired) — the client must
// re-prompt for login, not assume a valid session.
func TestAdapter_CheckAuth_Unauthorised(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("<html>guest page, no marker</html>"))
	}))
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	ok, err := a.CheckAuth(context.Background(), cookieCred())
	if err != nil {
		t.Fatalf("CheckAuth: %v", err)
	}
	if ok {
		t.Error("CheckAuth = true, want false (index page lacks logged-in marker)")
	}
}

// TestAdapter_AddComment_AnonymousUnauthorized verifies an anonymous user
// cannot post a comment — the adapter must surface 401, never a fake
// success. Exercises the AddComment delegation + mapError path.
func TestAdapter_AddComment_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	ok, err := a.AddComment(context.Background(), "42", "hi", provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous AddComment err = %v, want provider.ErrUnauthorized", err)
	}
	if ok {
		t.Error("anonymous AddComment ok = true, want false")
	}
}

// TestAdapter_AddFavorite_AnonymousUnauthorized verifies anonymous add-
// favorite is rejected with 401 (and returns ok=false).
func TestAdapter_AddFavorite_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	ok, err := a.AddFavorite(context.Background(), "42", provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous AddFavorite err = %v, want provider.ErrUnauthorized", err)
	}
	if ok {
		t.Error("anonymous AddFavorite ok = true, want false")
	}
}

// TestAdapter_RemoveFavorite_AnonymousUnauthorized verifies anonymous
// remove-favorite is rejected with 401 (and returns ok=false).
func TestAdapter_RemoveFavorite_AnonymousUnauthorized(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	ok, err := a.RemoveFavorite(context.Background(), "42", provider.Credentials{})
	if err != provider.ErrUnauthorized {
		t.Errorf("anonymous RemoveFavorite err = %v, want provider.ErrUnauthorized", err)
	}
	if ok {
		t.Error("anonymous RemoveFavorite ok = true, want false")
	}
}

// TestAdapter_FetchCaptcha_InvalidPath verifies the captcha delegation
// surfaces an error for an undecodable captcha path — the client must show a
// failure, not a blank image.
func TestAdapter_FetchCaptcha_InvalidPath(t *testing.T) {
	srv := adapterFixtureServer(t)
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	// "!!!" is not valid base64 → DecodeCaptchaPath fails → error.
	_, err := a.FetchCaptcha(context.Background(), "!!!not-base64!!!")
	if err == nil {
		t.Error("FetchCaptcha with invalid path returned nil error, want a decode error")
	}
}

// TestAdapter_HealthCheck_Unhealthy verifies HealthCheck reports
// Healthy=false (without error) when the upstream is down — a 5xx forum
// response. The /health endpoint must show degraded, not crash.
func TestAdapter_HealthCheck_Unhealthy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer srv.Close()
	a := NewProviderAdapter(NewClient(srv.URL))

	hs, err := a.HealthCheck(context.Background())
	if err != nil {
		t.Fatalf("HealthCheck returned error, want nil with Healthy=false: %v", err)
	}
	if hs.Healthy {
		t.Error("HealthCheck Healthy = true, want false (upstream returned 5xx)")
	}
}
