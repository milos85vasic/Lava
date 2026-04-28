package rutracker

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

// loadFavoritesFixture mirrors loadFixture from forum_test.go but
// scoped to testdata/favorites/. Synthetic fixtures faithful to the
// real upstream selector walk; the cross-backend parity test in
// Phase 10 is the live double-check.
func loadFavoritesFixture(t *testing.T, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", "favorites", name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

// TestParseFavoritesPagesCount_TableDriven covers Kotlin's
// `takeLast(2).firstOrNull()` semantics on the .pg list, plus the
// `max(secondToLast, currentPage)` clamp. Each row asserts directly on
// the user-visible (well, caller-visible) integer return — Sixth Law
// clause 3.
func TestParseFavoritesPagesCount_TableDriven(t *testing.T) {
	cases := []struct {
		name string
		html string
		want int
	}{
		{
			name: "0_pg_b1_returns_1",
			html: `<div id="pagination"><p><b>1</b></p></div>`,
			want: 1,
		},
		{
			name: "0_pg_b3_returns_3",
			html: `<div id="pagination"><p><b>3</b></p></div>`,
			want: 3,
		},
		{
			name: "1_pg_5_b1_returns_5",
			html: `<div id="pagination"><p><b>1</b></p>
				<a class="pg" href="x">5</a></div>`,
			want: 5,
		},
		{
			name: "3_pg_3_5_7_b2_returns_5",
			// takeLast(2) of [3,5,7] = [5,7]; firstOrNull() = 5.
			// max(5, 2) = 5.
			html: `<div id="pagination"><p><b>2</b></p>
				<a class="pg" href="x">3</a>
				<a class="pg" href="x">5</a>
				<a class="pg" href="x">7</a></div>`,
			want: 5,
		},
		{
			name: "2_pg_3_5_b1_returns_3",
			// takeLast(2) of [3,5] = [3,5]; firstOrNull() = 3.
			// max(3, 1) = 3.
			html: `<div id="pagination"><p><b>1</b></p>
				<a class="pg" href="x">3</a>
				<a class="pg" href="x">5</a></div>`,
			want: 3,
		},
		{
			name: "2_pg_3_5_b10_returns_10",
			// max(3, 10) = 10 — currentPage dominates.
			html: `<div id="pagination"><p><b>10</b></p>
				<a class="pg" href="x">3</a>
				<a class="pg" href="x">5</a></div>`,
			want: 10,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ParseFavoritesPagesCount([]byte(tc.html))
			if got != tc.want {
				t.Errorf("ParseFavoritesPagesCount: got %d, want %d", got, tc.want)
			}
		})
	}
}

// TestParseFavoritesPagesCount_Fixture3pages exercises the same path as
// the table-driven test but using a dedicated fixture (closer to a real
// upstream payload).
func TestParseFavoritesPagesCount_Fixture3pages(t *testing.T) {
	html := loadFavoritesFixture(t, "pagesCount_3pages.html")
	if got, want := ParseFavoritesPagesCount(html), 5; got != want {
		t.Errorf("ParseFavoritesPagesCount(pagesCount_3pages.html): got %d, want %d", got, want)
	}
}

// TestParseFavoritesPage_TopicAndTorrent verifies the discriminated
// ForumTopicDto union: a row with .tor-approved is unmarshalled as a
// Torrent variant carrying status/size/seeds/leeches; a row without a
// torrent-status class is unmarshalled as a Topic variant whose Title
// retains the [tag] brackets (Kotlin uses fullTitle, not the stripped
// title).
//
// Primary assertions (Sixth Law clause 3) are on the parsed struct
// fields — the same JSON the API client sees on the wire.
func TestParseFavoritesPage_TopicAndTorrent(t *testing.T) {
	html := loadFavoritesFixture(t, "page1.html")
	out, err := ParseFavoritesPage(html)
	if err != nil {
		t.Fatalf("ParseFavoritesPage: %v", err)
	}
	if len(out) != 2 {
		t.Fatalf("ParseFavoritesPage: got %d rows, want 2", len(out))
	}

	// Row 0: torrent variant.
	tor, err := out[0].AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("row 0 AsForumTopicDtoTorrent: %v", err)
	}
	if string(tor.Type) != "Torrent" {
		t.Errorf("row 0 type: got %q, want Torrent", string(tor.Type))
	}
	if tor.Id != "2001" {
		t.Errorf("row 0 id: got %q, want 2001", tor.Id)
	}
	if tor.Title != "Movie One" {
		t.Errorf("row 0 title: got %q, want %q", tor.Title, "Movie One")
	}
	if tor.Tags == nil || strings.TrimSpace(*tor.Tags) != "[Drama] [1080p]" {
		gotTags := "<nil>"
		if tor.Tags != nil {
			gotTags = *tor.Tags
		}
		t.Errorf("row 0 tags: got %q, want %q", gotTags, "[Drama] [1080p]")
	}
	if tor.Status == nil || string(*tor.Status) != "Approved" {
		gotStatus := "<nil>"
		if tor.Status != nil {
			gotStatus = string(*tor.Status)
		}
		t.Errorf("row 0 status: got %q, want Approved", gotStatus)
	}
	if tor.Size == nil || *tor.Size != "2.4 GB" {
		gotSize := "<nil>"
		if tor.Size != nil {
			gotSize = *tor.Size
		}
		t.Errorf("row 0 size: got %q, want %q", gotSize, "2.4 GB")
	}
	if tor.Seeds == nil || *tor.Seeds != 12 {
		t.Errorf("row 0 seeds: got %v, want 12", tor.Seeds)
	}
	if tor.Leeches == nil || *tor.Leeches != 3 {
		t.Errorf("row 0 leeches: got %v, want 3", tor.Leeches)
	}
	if tor.Author == nil || tor.Author.Name != "alice" {
		gotName := "<nil>"
		if tor.Author != nil {
			gotName = tor.Author.Name
		}
		t.Errorf("row 0 author name: got %q, want alice", gotName)
	}
	if tor.Author == nil || tor.Author.Id == nil || *tor.Author.Id != "42" {
		gotID := "<nil>"
		if tor.Author != nil && tor.Author.Id != nil {
			gotID = *tor.Author.Id
		}
		t.Errorf("row 0 author id: got %q, want 42", gotID)
	}
	if tor.Category == nil || tor.Category.Name != "Movies" {
		t.Errorf("row 0 category name: want Movies")
	}
	if tor.Category == nil || tor.Category.Id == nil || *tor.Category.Id != "7" {
		gotID := "<nil>"
		if tor.Category != nil && tor.Category.Id != nil {
			gotID = *tor.Category.Id
		}
		t.Errorf("row 0 category id: got %q, want 7", gotID)
	}

	// Row 1: topic variant.
	top, err := out[1].AsForumTopicDtoTopic()
	if err != nil {
		t.Fatalf("row 1 AsForumTopicDtoTopic: %v", err)
	}
	if string(top.Type) != "Topic" {
		t.Errorf("row 1 type: got %q, want Topic", string(top.Type))
	}
	if top.Id != "2002" {
		t.Errorf("row 1 id: got %q, want 2002", top.Id)
	}
	// Topic variant uses fullTitle, brackets retained.
	if top.Title != "[Discussion] A General Thread" {
		t.Errorf("row 1 title: got %q, want %q", top.Title, "[Discussion] A General Thread")
	}
	if top.Author == nil || top.Author.Name != "bob" {
		t.Errorf("row 1 author name: want bob")
	}
	if top.Category == nil || top.Category.Name != "Talk" {
		t.Errorf("row 1 category name: want Talk")
	}
}

// TestParseFavoritesPage_Empty verifies the no-rows path: a navigation
// block but no .hl-tr rows yields an empty list (no panic, no error).
func TestParseFavoritesPage_Empty(t *testing.T) {
	html := loadFavoritesFixture(t, "empty.html")
	out, err := ParseFavoritesPage(html)
	if err != nil {
		t.Fatalf("ParseFavoritesPage: %v", err)
	}
	if len(out) != 0 {
		t.Errorf("ParseFavoritesPage(empty.html): got %d rows, want 0", len(out))
	}
}

// TestGetFavorites_HappyPathSinglePage asserts the single-page walk:
// page 1 advertises pagesCount=1, no further upstream traffic. Primary
// assertion is on FavoritesDto.Topics length (the user-visible JSON
// payload).
func TestGetFavorites_HappyPathSinglePage(t *testing.T) {
	page1 := loadFavoritesFixture(t, "page1.html")
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(page1)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetFavorites(context.Background(), "cookie=v")
	if err != nil {
		t.Fatalf("GetFavorites: %v", err)
	}
	if out == nil {
		t.Fatal("GetFavorites: nil FavoritesDto")
	}
	if got, want := len(out.Topics), 2; got != want {
		t.Errorf("FavoritesDto.Topics length: got %d, want %d", got, want)
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Errorf("upstream hits: got %d, want 1 (single-page)", got)
	}
}

// TestGetFavorites_MultiPageWalksAllPages asserts the multi-page walk:
// page 1 advertises 3 pages; pages 2 and 3 are fetched at
// ?start=50/?start=100 respectively; the resulting Topics list is the
// concatenation in order.
//
// Primary assertions (Sixth Law clause 3) are on the visible Topics ids
// (their order is the user-visible signal that page-walk order is
// preserved).
func TestGetFavorites_MultiPageWalksAllPages(t *testing.T) {
	page1 := loadFavoritesFixture(t, "page1_multi.html")
	page2 := loadFavoritesFixture(t, "page2.html")
	page3 := loadFavoritesFixture(t, "page3.html")

	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		switch r.URL.Query().Get("start") {
		case "":
			_, _ = w.Write(page1)
		case "50":
			_, _ = w.Write(page2)
		case "100":
			_, _ = w.Write(page3)
		default:
			t.Errorf("unexpected start=%q", r.URL.Query().Get("start"))
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetFavorites(context.Background(), "cookie=v")
	if err != nil {
		t.Fatalf("GetFavorites: %v", err)
	}
	if out == nil {
		t.Fatal("GetFavorites: nil FavoritesDto")
	}
	if got, want := len(out.Topics), 3; got != want {
		t.Fatalf("FavoritesDto.Topics length: got %d, want %d", got, want)
	}
	wantIDs := []string{"3001", "3050", "3100"}
	for i, want := range wantIDs {
		top, err := out.Topics[i].AsForumTopicDtoTopic()
		if err != nil {
			t.Fatalf("Topics[%d] AsForumTopicDtoTopic: %v", i, err)
		}
		if top.Id != want {
			t.Errorf("Topics[%d].id: got %q, want %q (page-walk order)", i, top.Id, want)
		}
	}
	if got := atomic.LoadInt32(&hits); got != 3 {
		t.Errorf("upstream hits: got %d, want 3 (3-page walk)", got)
	}
}

// TestGetFavorites_EmptyCookie_ErrUnauthorized — empty cookie
// short-circuits, no upstream traffic.
func TestGetFavorites_EmptyCookie_ErrUnauthorized(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetFavorites(context.Background(), "")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("GetFavorites err=%v, want ErrUnauthorized", err)
	}
	if out != nil {
		t.Errorf("GetFavorites: out=%v, want nil on ErrUnauthorized", out)
	}
	if got := atomic.LoadInt32(&hits); got != 0 {
		t.Errorf("upstream hits: got %d, want 0 (empty cookie must short-circuit)", got)
	}
}

// TestGetFavorites_NotAuthorised — page 1 response has no
// "logged-in-username" marker → ErrUnauthorized; subsequent pages MUST
// NOT be hit.
func TestGetFavorites_NotAuthorised(t *testing.T) {
	guest := loadFavoritesFixture(t, "not_authorised.html")
	var page2Hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("start") != "" {
			atomic.AddInt32(&page2Hits, 1)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(guest)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetFavorites(context.Background(), "cookie=v")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("GetFavorites err=%v, want ErrUnauthorized", err)
	}
	if out != nil {
		t.Errorf("GetFavorites: out=%v, want nil on ErrUnauthorized", out)
	}
	if got := atomic.LoadInt32(&page2Hits); got != 0 {
		t.Errorf("page-2/3 hits: got %d, want 0 (must abort before fetching more pages)", got)
	}
}

// successfulMainPageForFavorites is reused by the AddFavorite /
// RemoveFavorite tests below — it satisfies IsAuthorised AND
// ParseFormToken with token "tok".
const successfulMainPageForFavorites = `<html><body>
<div id='logged-in-username'>milos</div>
<script>
var profile = { form_token: 'tok', other: 'noise' };
</script>
</body></html>`

// TestAddFavorite_HappyPath wires AddFavorite to a real httptest server
// and asserts:
//  1. (true, nil) is returned (success sentence "Тема добавлена" in
//     POST response body).
//  2. the captured POST form body has the exact expected fields.
//
// Sixth Law clause 1: traversal is end-to-end through Fetch → PostForm.
// Sixth Law clause 3: primary assertions are on the captured wire bytes
// (the same the upstream sees).
func TestAddFavorite_HappyPath(t *testing.T) {
	const (
		topicID = "topic-7"
		cookie  = "bb_session=abc; bb_data=opaque"
	)
	var (
		gotAction    string
		gotTopicID   string
		gotFormToken string
		postCount    int32
	)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPageForFavorites))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			atomic.AddInt32(&postCount, 1)
			if err := r.ParseForm(); err != nil {
				t.Errorf("ParseForm: %v", err)
			}
			gotAction = r.PostForm.Get("action")
			gotTopicID = r.PostForm.Get("topic_id")
			gotFormToken = r.PostForm.Get("form_token")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>... Тема добавлена ...</html>"))
		default:
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddFavorite(context.Background(), topicID, cookie)
	if err != nil {
		t.Fatalf("AddFavorite: %v", err)
	}
	if !ok {
		t.Errorf("AddFavorite: ok=false, want true (success sentence present)")
	}
	if got := atomic.LoadInt32(&postCount); got != 1 {
		t.Errorf("postCount=%d want 1", got)
	}
	if gotAction != "bookmark_add" {
		t.Errorf("form action=%q want bookmark_add", gotAction)
	}
	if gotTopicID != topicID {
		t.Errorf("form topic_id=%q want %q", gotTopicID, topicID)
	}
	if gotFormToken != "tok" {
		t.Errorf("form form_token=%q want %q", gotFormToken, "tok")
	}
}

// TestAddFavorite_UpstreamSilentReject — POST returns body without the
// success sentence; the function must return (false, nil), which the
// handler maps to JSON `false`.
func TestAddFavorite_UpstreamSilentReject(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPageForFavorites))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>error: form_token mismatch</html>"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddFavorite(context.Background(), "t1", "cookie=v")
	if err != nil {
		t.Fatalf("AddFavorite: %v", err)
	}
	if ok {
		t.Errorf("AddFavorite: ok=true, want false (success sentence absent)")
	}
}

// TestAddFavorite_EmptyCookie_ErrUnauthorized — empty cookie
// short-circuits before any upstream traffic.
func TestAddFavorite_EmptyCookie_ErrUnauthorized(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddFavorite(context.Background(), "t1", "")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("AddFavorite err=%v want ErrUnauthorized", err)
	}
	if ok {
		t.Errorf("AddFavorite: ok=true, want false on ErrUnauthorized")
	}
	if got := atomic.LoadInt32(&hits); got != 0 {
		t.Errorf("server hits=%d want 0", got)
	}
}

// TestAddFavorite_FormTokenMissing_ErrUnauthorized — GET response has
// the marker but no form_token; AddFavorite must abort before POST.
func TestAddFavorite_FormTokenMissing_ErrUnauthorized(t *testing.T) {
	var postHits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<div id='logged-in-username'>milos</div>"))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			atomic.AddInt32(&postHits, 1)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.AddFavorite(context.Background(), "t1", "cookie=v")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("AddFavorite err=%v want ErrUnauthorized", err)
	}
	if ok {
		t.Errorf("AddFavorite: ok=true, want false on ErrUnauthorized")
	}
	if got := atomic.LoadInt32(&postHits); got != 0 {
		t.Errorf("/bookmarks.php POST hits=%d want 0", got)
	}
}

// TestRemoveFavorite_HappyPath asserts the analogous flow for
// RemoveFavorite. Captures the form body and verifies:
//   - action=bookmark_delete (NOT bookmark_add)
//   - topic_id=<id>
//   - form_token=<token>
//   - request_origin=from_topic_page (this field is the only difference
//     from AddFavorite — explicitly asserted as a regression target)
//   - the success sentence is "Тема удалена" (NOT "Тема добавлена")
func TestRemoveFavorite_HappyPath(t *testing.T) {
	const (
		topicID = "topic-9"
		cookie  = "bb_session=abc; bb_data=opaque"
	)
	var (
		gotAction        string
		gotTopicID       string
		gotFormToken     string
		gotRequestOrigin string
		postCount        int32
	)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPageForFavorites))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			atomic.AddInt32(&postCount, 1)
			if err := r.ParseForm(); err != nil {
				t.Errorf("ParseForm: %v", err)
			}
			gotAction = r.PostForm.Get("action")
			gotTopicID = r.PostForm.Get("topic_id")
			gotFormToken = r.PostForm.Get("form_token")
			gotRequestOrigin = r.PostForm.Get("request_origin")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>... Тема удалена ...</html>"))
		default:
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.RemoveFavorite(context.Background(), topicID, cookie)
	if err != nil {
		t.Fatalf("RemoveFavorite: %v", err)
	}
	if !ok {
		t.Errorf("RemoveFavorite: ok=false, want true (success sentence present)")
	}
	if got := atomic.LoadInt32(&postCount); got != 1 {
		t.Errorf("postCount=%d want 1", got)
	}
	if gotAction != "bookmark_delete" {
		t.Errorf("form action=%q want bookmark_delete", gotAction)
	}
	if gotTopicID != topicID {
		t.Errorf("form topic_id=%q want %q", gotTopicID, topicID)
	}
	if gotFormToken != "tok" {
		t.Errorf("form form_token=%q want %q", gotFormToken, "tok")
	}
	if gotRequestOrigin != "from_topic_page" {
		t.Errorf("form request_origin=%q want from_topic_page", gotRequestOrigin)
	}
}

// TestRemoveFavorite_RequestOriginPresent is a focused regression test
// for the only field-shape difference between AddFavorite and
// RemoveFavorite: the extra request_origin=from_topic_page form field.
// Asserts on the captured wire bytes directly (the encoded form body),
// since that is the user-affecting signal — without this field the
// upstream rejects the deletion.
func TestRemoveFavorite_RequestOriginPresent(t *testing.T) {
	var rawBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPageForFavorites))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			b, _ := io.ReadAll(r.Body)
			rawBody = string(b)
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>Тема удалена</html>"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if _, err := c.RemoveFavorite(context.Background(), "topic-1", "cookie=v"); err != nil {
		t.Fatalf("RemoveFavorite: %v", err)
	}
	values, err := url.ParseQuery(rawBody)
	if err != nil {
		t.Fatalf("ParseQuery(%q): %v", rawBody, err)
	}
	if values.Get("request_origin") != "from_topic_page" {
		t.Errorf("captured form: request_origin=%q, want from_topic_page (raw body=%q)",
			values.Get("request_origin"), rawBody)
	}
}

// TestRemoveFavorite_UpstreamSilentReject — POST response without the
// success sentence "Тема удалена" maps to (false, nil). This is the
// guard against the most plausible regression: dropping a word from the
// success sentence (e.g. "была удалена") which would silently break
// all delete operations for end users.
func TestRemoveFavorite_UpstreamSilentReject(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/index.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(successfulMainPageForFavorites))
		case r.Method == http.MethodPost && r.URL.Path == "/bookmarks.php":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("<html>nothing to delete</html>"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	ok, err := c.RemoveFavorite(context.Background(), "t1", "cookie=v")
	if err != nil {
		t.Fatalf("RemoveFavorite: %v", err)
	}
	if ok {
		t.Errorf("RemoveFavorite: ok=true, want false (success sentence absent)")
	}
}

// TestFavoritesPagePath_StartParam is a small unit-level pin on the
// page → path mapping. Page 1 omits ?start= entirely; page 2 emits
// start=50; page 3 emits start=100. Wire-equivalent to Kotlin's
// upstream call shape.
func TestFavoritesPagePath_StartParam(t *testing.T) {
	cases := []struct {
		page int
		want string
	}{
		{0, "/bookmarks.php"},
		{1, "/bookmarks.php"},
		{2, "/bookmarks.php?start=50"},
		{3, "/bookmarks.php?start=100"},
	}
	for _, tc := range cases {
		got := favoritesPagePath(tc.page)
		if got != tc.want {
			t.Errorf("favoritesPagePath(%d): got %q, want %q", tc.page, got, tc.want)
		}
	}
}

