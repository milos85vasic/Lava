package nnmclub

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func readTestData(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("read testdata %s: %v", name, err)
	}
	return b
}

func newAdapter(t *testing.T, h http.HandlerFunc) *ProviderAdapter {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return NewProviderAdapter(NewClient(srv.URL))
}

// TestAdapter_Metadata verifies the static provider metadata.
func TestAdapter_Metadata(t *testing.T) {
	a := NewProviderAdapter(NewClient("http://unused"))
	if a.DisplayName() != "NNM-Club" {
		t.Errorf("DisplayName = %q, want NNM-Club", a.DisplayName())
	}
	if a.AuthType() != provider.AuthFormLogin {
		t.Errorf("AuthType = %q, want FORM_LOGIN", a.AuthType())
	}
	if a.Encoding() != "windows-1251" {
		t.Errorf("Encoding = %q, want windows-1251", a.Encoding())
	}
}

// TestAdapter_GetForumTree_EndToEnd drives GetForumTree through the real
// Fetch + parseForumTree against served index HTML, asserting the user-
// visible forum categories (id + name) appear in the tree.
func TestAdapter_GetForumTree_EndToEnd(t *testing.T) {
	html := `<html><body>
	  <a class="forumlink" href="viewforum.php?f=10">Linux Distributions</a>
	  <a class="forumlink" href="viewforum.php?f=20">Movies</a>
	  <a class="forumlink" href="viewforum.php">No ID — skipped</a>
	</body></html>`
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/forum/index.php" {
			t.Errorf("path = %q, want /forum/index.php", r.URL.Path)
		}
		w.Write([]byte(html))
	})

	tree, err := a.GetForumTree(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("GetForumTree: %v", err)
	}
	if tree.Provider != "nnmclub" {
		t.Errorf("Provider = %q, want nnmclub", tree.Provider)
	}
	if len(tree.Categories) != 2 {
		t.Fatalf("Categories len = %d, want 2 (the id-less link is skipped)", len(tree.Categories))
	}
	if tree.Categories[0].ID != "10" || tree.Categories[0].Name != "Linux Distributions" {
		t.Errorf("Categories[0] = %+v, want {10,Linux Distributions}", tree.Categories[0])
	}
}

// TestAdapter_GetTopic_EndToEnd drives GetTopic through GetTopicPage +
// ParseTopicPage, asserting the magnet link the user taps to download.
func TestAdapter_GetTopic_EndToEnd(t *testing.T) {
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write(readTestData(t, "topic/topic_normal.html"))
	})

	res, err := a.GetTopic(context.Background(), "1001", 1, provider.Credentials{})
	if err != nil {
		t.Fatalf("GetTopic: %v", err)
	}
	if res.ID != "1001" {
		t.Errorf("ID = %q, want 1001", res.ID)
	}
	if res.MagnetLink == "" {
		t.Error("MagnetLink empty, want the parsed magnet: URI")
	}
}

// TestAdapter_DownloadFile_EndToEnd asserts the binary .torrent bytes +
// the filename extracted from Content-Disposition.
func TestAdapter_DownloadFile_EndToEnd(t *testing.T) {
	want := []byte("d8:announce4:teste")
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/forum/download.php" {
			t.Errorf("path = %q, want /forum/download.php", r.URL.Path)
		}
		w.Header().Set("Content-Disposition", `attachment; filename="ubuntu.torrent"`)
		w.Header().Set("Content-Type", "application/x-bittorrent")
		w.Write(want)
	})

	fd, err := a.DownloadFile(context.Background(), "1001", provider.Credentials{})
	if err != nil {
		t.Fatalf("DownloadFile: %v", err)
	}
	if fd.Filename != "ubuntu.torrent" {
		t.Errorf("Filename = %q, want ubuntu.torrent", fd.Filename)
	}
	if string(fd.Body) != string(want) {
		t.Errorf("Body = %q, want %q", fd.Body, want)
	}
}

// TestAdapter_DownloadFile_404 asserts a 404 maps to provider.ErrNotFound
// (the user sees "not found", not a corrupt empty file).
func TestAdapter_DownloadFile_404(t *testing.T) {
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	if _, err := a.DownloadFile(context.Background(), "x", provider.Credentials{}); !errors.Is(err, provider.ErrNotFound) {
		t.Errorf("err = %v, want ErrNotFound", err)
	}
}

// TestAdapter_GetComments_EndToEnd drives GetComments through Fetch +
// parseComments, asserting the parsed comment author+body the user reads.
func TestAdapter_GetComments_EndToEnd(t *testing.T) {
	html := `<html><body><table class="forumline">
	  <tr><td><span class="name">alice</span></td><td><div class="postbody">first post</div></td></tr>
	  <tr><td><b class="postauthor">bob</b></td><td><div class="postbody">second post</div></td></tr>
	  <tr><td><div class="postbody">no author — skipped</div></td></tr>
	</table></body></html>`
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(html))
	})

	res, err := a.GetComments(context.Background(), "1001", 1, provider.Credentials{})
	if err != nil {
		t.Fatalf("GetComments: %v", err)
	}
	if res.Provider != "nnmclub" {
		t.Errorf("Provider = %q, want nnmclub", res.Provider)
	}
	if len(res.Items) != 2 {
		t.Fatalf("Items len = %d, want 2 (author-less row skipped)", len(res.Items))
	}
	if res.Items[0].Author != "alice" || res.Items[0].Body != "first post" {
		t.Errorf("Items[0] = %+v, want {alice, first post}", res.Items[0])
	}
	if res.Items[1].Author != "bob" {
		t.Errorf("Items[1].Author = %q, want bob (postauthor fallback)", res.Items[1].Author)
	}
}

// TestAdapter_CheckAuth verifies the IsAuthorised(index) semantics: the
// index page served to an anonymous session (no logout marker) reports
// unauthenticated; a logged-in index reports authenticated.
func TestAdapter_CheckAuth(t *testing.T) {
	anonHTML := readTestData(t, "login/index_anon.html")
	loggedHTML := readTestData(t, "login/index_logged_in.html")

	served := anonHTML
	a := newAdapter(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write(served)
	})

	ok, err := a.CheckAuth(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("CheckAuth(anon): %v", err)
	}
	if ok {
		t.Error("CheckAuth(anon index) = true, want false")
	}

	served = loggedHTML
	ok, err = a.CheckAuth(context.Background(), provider.Credentials{Type: "cookie", CookieValue: "phpbb=1"})
	if err != nil {
		t.Fatalf("CheckAuth(logged): %v", err)
	}
	if !ok {
		t.Error("CheckAuth(logged-in index) = false, want true")
	}
}

// TestAdapter_UnsupportedCapabilities verifies the honest ErrUnsupported
// surface (§6.E) for add-comment / add-favorite / remove-favorite / captcha,
// and the empty-but-valid favorites result.
func TestAdapter_UnsupportedCapabilities(t *testing.T) {
	a := NewProviderAdapter(NewClient("http://unused"))
	if ok, err := a.AddComment(context.Background(), "1", "x", provider.Credentials{}); ok || !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("AddComment = (%v,%v), want (false,ErrUnsupported)", ok, err)
	}
	if ok, err := a.AddFavorite(context.Background(), "1", provider.Credentials{}); ok || !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("AddFavorite = (%v,%v), want (false,ErrUnsupported)", ok, err)
	}
	if ok, err := a.RemoveFavorite(context.Background(), "1", provider.Credentials{}); ok || !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("RemoveFavorite = (%v,%v), want (false,ErrUnsupported)", ok, err)
	}
	if _, err := a.FetchCaptcha(context.Background(), "p"); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("FetchCaptcha err = %v, want ErrUnsupported", err)
	}
	fav, err := a.GetFavorites(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("GetFavorites: %v", err)
	}
	if fav.Provider != "nnmclub" || len(fav.Items) != 0 {
		t.Errorf("favorites = %+v, want nnmclub empty list", fav)
	}
}

// TestMapError verifies the nnmclub→provider sentinel translation.
func TestMapError(t *testing.T) {
	cases := []struct{ in, want error }{
		{ErrNotFound, provider.ErrNotFound},
		{ErrUnauthorized, provider.ErrUnauthorized},
		{ErrCircuitOpen, provider.ErrCircuitOpen},
	}
	for _, tc := range cases {
		if got := mapError(tc.in); !errors.Is(got, tc.want) {
			t.Errorf("mapError(%v) = %v, want %v", tc.in, got, tc.want)
		}
	}
	custom := errors.New("x")
	if got := mapError(custom); got != custom {
		t.Errorf("mapError passthrough = %v, want %v", got, custom)
	}
}

// TestContentDispositionFilename covers the helper incl. the unterminated
// (no closing quote) branch — which returns the FULL header, distinct from
// the kinozal variant.
func TestContentDispositionFilename(t *testing.T) {
	cases := []struct{ in, want string }{
		{`attachment; filename="a.torrent"`, "a.torrent"},
		{`no-prefix`, "no-prefix"},
		{``, ""},
		{`filename="unterminated`, `filename="unterminated`}, // no closing quote -> full string
	}
	for _, tc := range cases {
		if got := contentDispositionFilename(tc.in); got != tc.want {
			t.Errorf("contentDispositionFilename(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestExtractTopicID covers the helper incl. the relative-URL fallback split.
func TestExtractTopicID(t *testing.T) {
	cases := []struct{ in, want string }{
		{"viewtopic.php?t=12345", "12345"},
		{"https://nnmclub.to/forum/viewtopic.php?t=678", "678"},
		{"viewtopic.php", ""},
		{"", ""},
	}
	for _, tc := range cases {
		if got := extractTopicID(tc.in); got != tc.want {
			t.Errorf("extractTopicID(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestParseForumTree_Direct covers the parser directly incl. the
// empty-name / empty-id skip branches.
func TestParseForumTree_Direct(t *testing.T) {
	html := `<a class="forumlink" href="viewforum.php?f=1">Cat One</a>
	         <a class="forumlink" href="viewforum.php?f=2"></a>
	         <a class="forumlink" href="viewforum.php">No ID</a>`
	tree, err := parseForumTree([]byte(html))
	if err != nil {
		t.Fatalf("parseForumTree: %v", err)
	}
	if len(tree.Categories) != 1 || tree.Categories[0].ID != "1" {
		t.Errorf("Categories = %+v, want one {1,Cat One} (blank-name + id-less skipped)", tree.Categories)
	}
}
