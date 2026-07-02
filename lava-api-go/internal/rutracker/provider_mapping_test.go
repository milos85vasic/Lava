package rutracker

import (
	"errors"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/provider"
)

// strptr / i32ptr are tiny helpers for building pointer-bearing DTOs.
func strptr(s string) *string { return &s }
func i32ptr(v int32) *int32   { return &v }

// TestFromTorrentDto verifies that every pointer-bearing field of a
// gen.ForumTopicDtoTorrent is correctly dereferenced into the flat
// provider.SearchItem the Android client receives. This is the mapping a
// user's search row passes through; a dropped field is a missing seeder
// count / size / magnet link on the user's screen.
func TestFromTorrentDto(t *testing.T) {
	tests := []struct {
		name string
		in   gen.ForumTopicDtoTorrent
		want provider.SearchItem
	}{
		{
			name: "all fields present",
			in: gen.ForumTopicDtoTorrent{
				Id:         "42",
				Title:      "Big Buck Bunny",
				Size:       strptr("1.5 GB"),
				Seeds:      i32ptr(120),
				Leeches:    i32ptr(7),
				MagnetLink: strptr("magnet:?xt=urn:btih:abc"),
			},
			want: provider.SearchItem{
				ID:         "42",
				Title:      "Big Buck Bunny",
				Size:       "1.5 GB",
				Seeders:    120,
				Leechers:   7,
				MagnetLink: "magnet:?xt=urn:btih:abc",
			},
		},
		{
			name: "all optional pointers nil — only id+title survive",
			in: gen.ForumTopicDtoTorrent{
				Id:    "7",
				Title: "No metadata",
			},
			want: provider.SearchItem{
				ID:    "7",
				Title: "No metadata",
			},
		},
		{
			name: "zero seeds/leeches are preserved (not treated as absent)",
			in: gen.ForumTopicDtoTorrent{
				Id:      "9",
				Title:   "Dead torrent",
				Seeds:   i32ptr(0),
				Leeches: i32ptr(0),
			},
			want: provider.SearchItem{
				ID:       "9",
				Title:    "Dead torrent",
				Seeders:  0,
				Leechers: 0,
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := fromTorrentDto(tc.in)
			if got != tc.want {
				t.Errorf("fromTorrentDto() =\n  %+v\nwant\n  %+v", got, tc.want)
			}
		})
	}
}

// TestFromSearchPage verifies pagination + per-row mapping for a full
// search page. The page/totalPages are what drive the client's infinite
// scroll; a wrong int32→int cast would break paging.
func TestFromSearchPage(t *testing.T) {
	page := &gen.SearchPageDto{
		Page:  3,
		Pages: 50,
		Torrents: []gen.ForumTopicDtoTorrent{
			{Id: "1", Title: "First", Seeds: i32ptr(10)},
			{Id: "2", Title: "Second", Size: strptr("700 MB")},
		},
	}
	got := fromSearchPage(page)

	if got.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", got.Provider)
	}
	if got.Page != 3 {
		t.Errorf("Page = %d, want 3", got.Page)
	}
	if got.TotalPages != 50 {
		t.Errorf("TotalPages = %d, want 50", got.TotalPages)
	}
	if len(got.Results) != 2 {
		t.Fatalf("Results len = %d, want 2", len(got.Results))
	}
	if got.Results[0].ID != "1" || got.Results[0].Seeders != 10 {
		t.Errorf("Results[0] = %+v, want id=1 seeders=10", got.Results[0])
	}
	if got.Results[1].Size != "700 MB" {
		t.Errorf("Results[1].Size = %q, want 700 MB", got.Results[1].Size)
	}
}

// TestFromCategoryPage verifies the per-row mapping of a browsed category
// page: page number cast + torrent field mapping. A user browsing a
// category must see the torrent rows with correct id/seeders.
//
// NOTE on union discrimination: gen.ForumTopicDto.AsForumTopicDtoTorrent()
// is a plain json.Unmarshal that does NOT check the discriminator `Type`
// field, so a non-torrent variant whose JSON happens to be Torrent-shaped
// (overlapping Id/Title) unmarshals without error and IS mapped. This test
// therefore asserts the production behavior for genuine torrent rows; the
// discrimination characteristic is documented, not assumed-away. See
// Stream-A findings.
func TestFromCategoryPage(t *testing.T) {
	var torrentTopic gen.ForumTopicDto
	if err := torrentTopic.FromForumTopicDtoTorrent(gen.ForumTopicDtoTorrent{
		Id:    "100",
		Title: "Category Torrent",
		Seeds: i32ptr(5),
		Type:  "torrent",
	}); err != nil {
		t.Fatalf("build torrent union: %v", err)
	}

	topics := []gen.ForumTopicDto{torrentTopic}
	page := &gen.CategoryPageDto{
		Page:   2,
		Topics: &topics,
	}
	got := fromCategoryPage(page)

	if got.Provider != "rutracker" {
		t.Errorf("Provider = %q, want rutracker", got.Provider)
	}
	if got.Page != 2 {
		t.Errorf("Page = %d, want 2", got.Page)
	}
	if len(got.Items) != 1 {
		t.Fatalf("Items len = %d, want 1", len(got.Items))
	}
	if got.Items[0].ID != "100" || got.Items[0].Seeders != 5 {
		t.Errorf("Items[0] = %+v, want id=100 seeders=5", got.Items[0])
	}
}

// TestFromCategoryPage_NilTopics verifies the nil-topics branch produces an
// empty (but valid) result rather than panicking — an empty category must
// render as "no results", not crash.
func TestFromCategoryPage_NilTopics(t *testing.T) {
	got := fromCategoryPage(&gen.CategoryPageDto{Page: 1, Topics: nil})
	if got == nil {
		t.Fatal("got nil result")
	}
	if len(got.Items) != 0 {
		t.Errorf("Items len = %d, want 0", len(got.Items))
	}
}

// TestFromForumDto_Recursive verifies the recursive category-tree mapping,
// including nested subcategories. The forum tree is the user's top-level
// navigation; a broken recursion drops whole sections of the catalogue.
func TestFromForumDto_Recursive(t *testing.T) {
	leaf := gen.CategoryDto{Id: strptr("11"), Name: "Comedy"}
	children := []gen.CategoryDto{leaf}
	forum := &gen.ForumDto{
		Children: []gen.CategoryDto{
			{
				Id:       strptr("1"),
				Name:     "Movies",
				Children: &children,
			},
			{
				Name: "Uncategorized (no id)",
			},
		},
	}
	got := fromForumDto(forum)

	if len(got.Categories) != 2 {
		t.Fatalf("Categories len = %d, want 2", len(got.Categories))
	}
	movies := got.Categories[0]
	if movies.ID != "1" || movies.Name != "Movies" {
		t.Errorf("Categories[0] = {%q,%q}, want {1,Movies}", movies.ID, movies.Name)
	}
	if len(movies.Subcategories) != 1 {
		t.Fatalf("Movies.Subcategories len = %d, want 1", len(movies.Subcategories))
	}
	if movies.Subcategories[0].ID != "11" || movies.Subcategories[0].Name != "Comedy" {
		t.Errorf("nested subcat = %+v, want {11,Comedy}", movies.Subcategories[0])
	}
	// Category with nil Id pointer must map to empty ID, not panic.
	if got.Categories[1].ID != "" {
		t.Errorf("nil-id category ID = %q, want empty", got.Categories[1].ID)
	}
}

// TestFromTopicPage verifies the topic detail mapping. The magnet link is the
// user's actual download action target.
//
// LVA-024: this test previously asserted `len(Files)==1 && Files[0].Size=="4 GB"`
// — locking in a bug where the torrent SIZE string was wedged into a synthetic
// TopicFile{Name:"Size"}, so the topic screen showed one nonsense "Size" file
// instead of the (empty) real file list. The size is not a file; the corrected
// contract is that no synthetic file is fabricated.
//
// Falsifiability: restore the `out.Files = []provider.TopicFile{{Name:"Size", …}}`
// line in fromTopicPage → this fails "Files = [{Size 4 GB ...}], want 0 entries".
func TestFromTopicPage(t *testing.T) {
	tp := &gen.TopicPageDto{
		Id:    "555",
		Title: "Detailed Topic",
		TorrentData: &gen.TorrentDataDto{
			MagnetLink: strptr("magnet:?xt=urn:btih:xyz"),
			Size:       strptr("4 GB"),
		},
	}
	got := fromTopicPage(tp)

	if got.ID != "555" || got.Title != "Detailed Topic" {
		t.Errorf("got {%q,%q}, want {555,Detailed Topic}", got.ID, got.Title)
	}
	if got.MagnetLink != "magnet:?xt=urn:btih:xyz" {
		t.Errorf("MagnetLink = %q, want magnet:?xt=urn:btih:xyz", got.MagnetLink)
	}
	if len(got.Files) != 0 {
		t.Errorf("Files = %+v, want 0 entries (the size string is NOT a file — LVA-024)", got.Files)
	}
}

// TestFromTopicPage_NilTorrentData verifies the nil-TorrentData branch: a
// topic with no torrent attached must still render its title without a
// magnet link, not crash.
func TestFromTopicPage_NilTorrentData(t *testing.T) {
	got := fromTopicPage(&gen.TopicPageDto{Id: "1", Title: "T", TorrentData: nil})
	if got.MagnetLink != "" {
		t.Errorf("MagnetLink = %q, want empty", got.MagnetLink)
	}
	if len(got.Files) != 0 {
		t.Errorf("Files len = %d, want 0", len(got.Files))
	}
}

// TestFromCommentsPage verifies comment mapping incl. author name extraction.
func TestFromCommentsPage(t *testing.T) {
	cp := &gen.CommentsPageDto{
		Page:  1,
		Pages: 4,
		Posts: []gen.PostDto{
			{Id: "p1", Date: "2024-01-01", Author: gen.AuthorDto{Name: "alice"}},
			{Id: "p2", Date: "2024-01-02", Author: gen.AuthorDto{Name: "bob"}},
		},
	}
	got := fromCommentsPage(cp)

	if got.Page != 1 || got.Total != 4 {
		t.Errorf("Page/Total = %d/%d, want 1/4", got.Page, got.Total)
	}
	if len(got.Items) != 2 {
		t.Fatalf("Items len = %d, want 2", len(got.Items))
	}
	if got.Items[0].ID != "p1" || got.Items[0].Author != "alice" || got.Items[0].Date != "2024-01-01" {
		t.Errorf("Items[0] = %+v, want id=p1 author=alice date=2024-01-01", got.Items[0])
	}
}

// TestFromFavoritesDto verifies that favorites map only torrent-variant rows.
func TestFromFavoritesDto(t *testing.T) {
	var fav1 gen.ForumTopicDto
	if err := fav1.FromForumTopicDtoTorrent(gen.ForumTopicDtoTorrent{Id: "fav1", Title: "Saved", Type: "torrent"}); err != nil {
		t.Fatalf("build union: %v", err)
	}
	got := fromFavoritesDto(&gen.FavoritesDto{Topics: []gen.ForumTopicDto{fav1}})

	if len(got.Items) != 1 {
		t.Fatalf("Items len = %d, want 1", len(got.Items))
	}
	if got.Items[0].ID != "fav1" {
		t.Errorf("Items[0].ID = %q, want fav1", got.Items[0].ID)
	}
}

// TestFromCategoryPage_SkipsNonTorrentVariant is the LVA-025 regression.
//
// A rutracker category page can carry mixed ForumTopicDto union variants:
// Torrent rows AND Topic rows (forum threads / announcements that have no
// torrent attached). The blind generated accessor AsForumTopicDtoTorrent()
// does NOT consult the union "type" discriminator — a Topic union round-trips
// through it with NO error because all variants share Id/Title/Author/Category
// and every torrent-specific field is a nilable pointer. Consequence: a Topic
// row was silently mapped into a SearchItem with empty Size/Seeders/MagnetLink,
// so the browse screen showed a garbage "torrent" with no size and no download
// action instead of skipping the non-torrent entry.
//
// The fix routes fromCategoryPage through AsForumTopicDtoTorrentChecked(), which
// returns a discriminator-mismatch error for the Topic union, so the Topic is
// skipped and only the real Torrent appears.
//
// Falsifiability: revert fromCategoryPage to the blind AsForumTopicDtoTorrent()
// → len(Items) == 2 and the fake topic row (empty Size, 0 Seeders) appears →
// "Items len = 2, want 1 (the Topic variant must be skipped, LVA-025)".
func TestFromCategoryPage_SkipsNonTorrentVariant(t *testing.T) {
	var torrentUnion gen.ForumTopicDto
	if err := torrentUnion.FromForumTopicDtoTorrent(gen.ForumTopicDtoTorrent{
		Id: "200", Title: "Real Torrent", Seeds: i32ptr(7),
		Size: strptr("1 GB"), MagnetLink: strptr("magnet:?xt=urn:btih:real"),
		Type: "Torrent",
	}); err != nil {
		t.Fatalf("build torrent union: %v", err)
	}
	var topicUnion gen.ForumTopicDto
	if err := topicUnion.FromForumTopicDtoTopic(gen.ForumTopicDtoTopic{
		Id: "300", Title: "Forum Thread (no torrent)", Type: "Topic",
	}); err != nil {
		t.Fatalf("build topic union: %v", err)
	}

	topics := []gen.ForumTopicDto{torrentUnion, topicUnion}
	got := fromCategoryPage(&gen.CategoryPageDto{Page: 1, Topics: &topics})

	if len(got.Items) != 1 {
		t.Fatalf("Items len = %d, want 1 (the Topic variant must be skipped, LVA-025); items=%+v", len(got.Items), got.Items)
	}
	it := got.Items[0]
	if it.ID != "200" {
		t.Errorf("surviving item ID = %q, want 200 (the real Torrent)", it.ID)
	}
	if it.Size != "1 GB" || it.Seeders != 7 || it.MagnetLink != "magnet:?xt=urn:btih:real" {
		t.Errorf("torrent item lost data: %+v, want Size=1GB Seeders=7 magnet set", it)
	}
}

// TestFromFavoritesDto_SkipsNonTorrentVariant is the LVA-025 regression for the
// favorites/bookmarks surface. favorites.go genuinely produces a Topic union
// when a bookmarked row has no torrent status (a bookmarked forum thread), and
// a Torrent union otherwise. The blind accessor turned the bookmarked Topic
// into a fake empty torrent row in the user's favorites list.
//
// Falsifiability: revert fromFavoritesDto to the blind accessor → len(Items)==2
// and the empty fake torrent for "fav-topic" appears → "Items len = 2, want 1".
func TestFromFavoritesDto_SkipsNonTorrentVariant(t *testing.T) {
	var torrentUnion gen.ForumTopicDto
	if err := torrentUnion.FromForumTopicDtoTorrent(gen.ForumTopicDtoTorrent{
		Id: "fav-torrent", Title: "Saved Torrent", Size: strptr("2 GB"), Type: "Torrent",
	}); err != nil {
		t.Fatalf("build torrent union: %v", err)
	}
	var topicUnion gen.ForumTopicDto
	if err := topicUnion.FromForumTopicDtoTopic(gen.ForumTopicDtoTopic{
		Id: "fav-topic", Title: "Saved Forum Thread", Type: "Topic",
	}); err != nil {
		t.Fatalf("build topic union: %v", err)
	}

	got := fromFavoritesDto(&gen.FavoritesDto{
		Topics: []gen.ForumTopicDto{torrentUnion, topicUnion},
	})

	if len(got.Items) != 1 {
		t.Fatalf("Items len = %d, want 1 (the bookmarked Topic must be skipped, LVA-025); items=%+v", len(got.Items), got.Items)
	}
	if got.Items[0].ID != "fav-torrent" || got.Items[0].Size != "2 GB" {
		t.Errorf("surviving favorite = %+v, want id=fav-torrent size=2GB", got.Items[0])
	}
}

// TestToRutrackerSearchOpts verifies the provider→rutracker option mapping,
// asserting on which pointers are set vs left nil (nil ⇒ param omitted from
// the upstream query — wrong nil-ness breaks the user's search filter).
func TestToRutrackerSearchOpts(t *testing.T) {
	out := toRutrackerSearchOpts(provider.SearchOpts{
		Query:    "linux",
		Page:     2,
		Sort:     "Date",
		Order:    "Descending",
		Category: "100,200",
	})
	if out.Query == nil || *out.Query != "linux" {
		t.Errorf("Query = %v, want linux", out.Query)
	}
	if out.Page == nil || *out.Page != 2 {
		t.Errorf("Page = %v, want 2", out.Page)
	}
	if out.SortType == nil || *out.SortType != gen.SearchSortTypeDto("Date") {
		t.Errorf("SortType = %v, want Date", out.SortType)
	}
	if out.SortOrder == nil || *out.SortOrder != gen.SearchSortOrderDto("Descending") {
		t.Errorf("SortOrder = %v, want Descending", out.SortOrder)
	}
	if out.Categories == nil || *out.Categories != "100,200" {
		t.Errorf("Categories = %v, want 100,200", out.Categories)
	}
}

// TestToRutrackerSearchOpts_Empty verifies that empty provider opts leave
// every upstream pointer nil — an empty query must NOT inject empty-string
// params that rutracker would reject.
func TestToRutrackerSearchOpts_Empty(t *testing.T) {
	out := toRutrackerSearchOpts(provider.SearchOpts{})
	if out.Query != nil {
		t.Errorf("Query = %v, want nil", out.Query)
	}
	if out.Page != nil {
		t.Errorf("Page = %v, want nil", out.Page)
	}
	if out.SortType != nil || out.SortOrder != nil || out.Categories != nil {
		t.Errorf("expected all sort/cat pointers nil, got %+v", out)
	}
}

// TestCredToCookie verifies cookie extraction: only the "cookie" credential
// type yields a cookie; everything else is anonymous (empty).
func TestCredToCookie(t *testing.T) {
	if got := credToCookie(provider.Credentials{Type: "cookie", CookieValue: "bb_session=abc"}); got != "bb_session=abc" {
		t.Errorf("cookie cred = %q, want bb_session=abc", got)
	}
	if got := credToCookie(provider.Credentials{Type: "token", Token: "t"}); got != "" {
		t.Errorf("token cred = %q, want empty", got)
	}
	if got := credToCookie(provider.Credentials{}); got != "" {
		t.Errorf("zero cred = %q, want empty", got)
	}
}

// TestMapError verifies the rutracker→provider sentinel translation. The
// HTTP status the user receives is derived from these sentinels, so a wrong
// mapping shows the wrong error.
func TestMapError(t *testing.T) {
	tests := []struct {
		in   error
		want error
	}{
		{ErrNotFound, provider.ErrNotFound},
		{ErrForbidden, provider.ErrForbidden},
		{ErrUnauthorized, provider.ErrUnauthorized},
		{ErrCircuitOpen, provider.ErrCircuitOpen},
		{ErrNoData, provider.ErrNoData},
		{ErrUnknown, provider.ErrUnknown},
	}
	for _, tc := range tests {
		if got := mapError(tc.in); !errors.Is(got, tc.want) {
			t.Errorf("mapError(%v) = %v, want %v", tc.in, got, tc.want)
		}
	}
	// An unrecognised error passes through unchanged.
	custom := errors.New("boom")
	if got := mapError(custom); got != custom {
		t.Errorf("mapError(custom) = %v, want passthrough %v", got, custom)
	}
}

// TestContentDispositionFilename verifies the filename extraction from the
// Content-Disposition header. The extracted name is the user's saved
// .torrent file name.
func TestContentDispositionFilename(t *testing.T) {
	tests := []struct {
		in   string
		want string
	}{
		{`attachment; filename="Big.Buck.Bunny.torrent"`, "Big.Buck.Bunny.torrent"},
		{`inline; filename="a b c.torrent"`, "a b c.torrent"},
		{`no-filename-here`, "no-filename-here"},
		{``, ""},
		{`filename="unterminated`, "unterminated"},
	}
	for _, tc := range tests {
		if got := contentDispositionFilename(tc.in); got != tc.want {
			t.Errorf("contentDispositionFilename(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestProviderAdapter_Metadata verifies the static metadata + capability
// honesty (§6.E): every declared capability must be a real value, and the
// adapter must compile-time satisfy provider.Provider (asserted at top).
func TestProviderAdapter_Metadata(t *testing.T) {
	a := NewProviderAdapter(nil)
	if a.ID() != "rutracker" {
		t.Errorf("ID = %q, want rutracker", a.ID())
	}
	if a.DisplayName() != "RuTracker.org" {
		t.Errorf("DisplayName = %q, want RuTracker.org", a.DisplayName())
	}
	if a.AuthType() != provider.AuthCaptchaLogin {
		t.Errorf("AuthType = %q, want CAPTCHA_LOGIN", a.AuthType())
	}
	if a.Encoding() != "windows-1251" {
		t.Errorf("Encoding = %q, want windows-1251", a.Encoding())
	}
	caps := a.Capabilities()
	if len(caps) == 0 {
		t.Fatal("Capabilities() returned empty set")
	}
	// CapSearch must be present (the core capability the parity test gates).
	found := false
	for _, c := range caps {
		if c == provider.CapSearch {
			found = true
		}
	}
	if !found {
		t.Error("Capabilities() missing CapSearch")
	}
}
