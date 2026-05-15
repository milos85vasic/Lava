// Package rutracker — favorites.go ports the bookmarks ("favorites")
// flow from Kotlin:
//
//   - GetFavoritesUseCase.kt    → GetFavorites + ParseFavoritesPage +
//     ParseFavoritesPagesCount.
//   - AddFavoriteUseCase.kt     → AddFavorite (3-step flow like
//     AddComment).
//   - RemoveFavoriteUseCase.kt  → RemoveFavorite (same shape as
//     AddFavorite but action=bookmark_delete
//   - extra request_origin=from_topic_page
//   - success sentence "Тема удалена").
//   - RuTrackerInnerApiImpl.kt:131-163 — the upstream URL shape
//     (/bookmarks.php with start = 50*(page-1)
//     for page > 1) and the form-body
//     contracts for add/remove.
//
// The Russian success sentences "Тема добавлена" / "Тема удалена" are
// the byte-equal literals from AddFavoriteUseCase.kt:16 and
// RemoveFavoriteUseCase.kt:16 — they are the only signal the upstream
// gives for acceptance; absence ⇒ the caller maps to JSON `false`.
//
// The auth-check for GetFavorites is performed AGAINST the bookmarks
// page response (not against /index.php) — matching Kotlin's
// `withAuthorisedCheckUseCase(api.favorites(validToken, 1))`. The
// auth-check for AddFavorite/RemoveFavorite is performed against
// /index.php (matching Kotlin's `api.mainPage(validToken)` call), which
// also yields the rotating form_token via ParseFormToken.
package rutracker

import (
	"bytes"
	"context"
	"math"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// favoriteAddedSentence is the byte-equal Russian success sentence from
// AddFavoriteUseCase.kt:16. The presence of this sentence in the
// /bookmarks.php response body is the ONLY signal that rutracker
// accepted the bookmark-add; absence means the upstream rejected it
// (rotating form_token mismatch, account restrictions, etc.).
var favoriteAddedSentence = []byte("Тема добавлена")

// favoriteRemovedSentence is the byte-equal Russian success sentence
// from RemoveFavoriteUseCase.kt:16, with the same semantics as
// favoriteAddedSentence above but for the bookmark-delete action.
var favoriteRemovedSentence = []byte("Тема удалена")

// ParseFavoritesPagesCount extracts the bookmarks.php pagination count
// from the response HTML.
//
// Selector walk (matches GetFavoritesUseCase.parsePagesCount):
//
//	#pagination > b                      → currentPage (toInt(1))
//	#pagination > .pg                    → list of paginator anchors
//	  takeLast(2).firstOrNull().toInt(1) → secondToLast .pg link, or
//	                                       the only one when there is
//	                                       just one, or 1 when none.
//	max(secondToLast, currentPage)       → returned value
//
// Both currentPage and the .pg-derived value default to 1 when absent.
func ParseFavoritesPagesCount(html []byte) int {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		// no-telemetry: page-count helper returns the "default 1 page"
		// fallback per the function-header KDoc contract. Pagination UI
		// renders correctly with 1 page; no user-visible error.
		return 1
	}
	nav := doc.Find("#pagination")
	currentPage := nodeInt(nav.Find("b"), 1)
	pgs := nav.Find(".pg")
	lastButOne := 1
	switch n := pgs.Length(); {
	case n >= 2:
		// Kotlin: takeLast(2).firstOrNull() — the second-to-last .pg
		// link (the page-number link just before the "next page" link).
		lastButOne = nodeInt(pgs.Eq(n-2), 1)
	case n == 1:
		// takeLast(2) of a single-element list yields that single
		// element; firstOrNull() returns it.
		lastButOne = nodeInt(pgs.Eq(0), 1)
	}
	if lastButOne > currentPage {
		return lastButOne
	}
	return currentPage
}

// ParseFavoritesPage parses a single bookmarks.php response page into
// the row list. Each .hl-tr row is mapped to a ForumTopicDto; rows
// carrying a torrent-status CSS class become Torrent variants, others
// become Topic variants.
//
// Selector walk (matches GetFavoritesUseCase.parseFavorites):
//
//	.hl-tr rows, each with:
//	  .topic-selector[data-topic_id]              → id
//	  .torTopic.ts-text                           → fullTitle
//	  parseTorrentStatus(row)                     → status
//	  .topicAuthor[?u=]                           → authorId
//	  .topicAuthor > .topicAuthor (text)          → authorName
//	  .t-forum-cell > a:last[?f=]                 → categoryId
//	  .t-forum-cell > .ts-text                    → categoryName
//	Torrent-only:
//	  .f-dl                                       → size (raw text)
//	  .seedmed                                    → seeds (intOrNil)
//	  .leechmed                                   → leeches (intOrNil)
//
// For the Topic variant Kotlin uses `fullTitle` as the title (NOT the
// stripped title) — preserved here for parity. For the Torrent variant
// the int32 clamp pattern is applied to seeds/leeches: out-of-range
// upstream values are treated as ABSENT, matching forum.go.
func ParseFavoritesPage(html []byte) ([]gen.ForumTopicDto, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, err
	}
	rows := doc.Find(".hl-tr")
	out := make([]gen.ForumTopicDto, 0, rows.Length())
	rows.Each(func(_ int, row *goquery.Selection) {
		id := nodeAttr(row.Find(".topic-selector").First(), "data-topic_id")
		fullTitle := nodeText(row.Find(".torTopic.ts-text"))
		title := getTitle(fullTitle)
		tags := getTags(fullTitle)
		status := parseTorrentStatus(row)

		// Author: Kotlin uses `.topicAuthor` for the href (?u=) AND
		// `.topicAuthor > .topicAuthor` for the visible name (the
		// rutracker bookmark row nests an inner .topicAuthor span
		// inside the outer .topicAuthor anchor).
		authorAnchor := row.Find(".topicAuthor").First()
		authorID, _ := queryParamOrNull(authorAnchor, "u")
		authorName := nodeText(row.Find(".topicAuthor > .topicAuthor"))
		author := gen.AuthorDto{
			Id:   ptrOrNil(authorID),
			Name: authorName,
		}

		// Category: Kotlin selects `.t-forum-cell select("a").last()`
		// for the id and `.t-forum-cell > .ts-text` for the name.
		// goquery's Last() returns the last matched element — equivalent
		// to Kotlin Elements.last().
		categoryAnchor := row.Find(".t-forum-cell").Find("a").Last()
		categoryID := queryParam(categoryAnchor, "f")
		categoryName := nodeText(row.Find(".t-forum-cell > .ts-text"))
		category := gen.CategoryDto{
			Id:   ptrOrNil(categoryID),
			Name: categoryName,
		}

		var topic gen.ForumTopicDto
		if status == nil {
			// Topic variant — Kotlin uses fullTitle (with tags), not
			// the stripped title.
			t := gen.ForumTopicDtoTopic{
				Author:   &author,
				Category: &category,
				Id:       id,
				Title:    fullTitle,
				Type:     "Topic",
			}
			if err := topic.FromForumTopicDtoTopic(t); err != nil {
				return
			}
		} else {
			size := nodeText(row.Find(".f-dl"))
			seedsVal, hasSeeds := nodeIntOrNil(row.Find(".seedmed"))
			leechesVal, hasLeeches := nodeIntOrNil(row.Find(".leechmed"))

			tt := gen.ForumTopicDtoTorrent{
				Author:   &author,
				Category: &category,
				Id:       id,
				Title:    title,
				Tags:     ptrOrNil(strings.TrimSpace(tags)),
				Size:     ptrOrNil(size),
				Status:   (*gen.TorrentStatusDto)(status),
				Type:     "Torrent",
			}
			// int32 clamp — see forum.go ParseCategoryPage for the same
			// pattern. Out-of-range values are treated as ABSENT.
			if hasSeeds && seedsVal >= math.MinInt32 && seedsVal <= math.MaxInt32 {
				v := int32(seedsVal)
				tt.Seeds = &v
			}
			if hasLeeches && leechesVal >= math.MinInt32 && leechesVal <= math.MaxInt32 {
				v := int32(leechesVal)
				tt.Leeches = &v
			}
			if err := topic.FromForumTopicDtoTorrent(tt); err != nil {
				return
			}
		}
		out = append(out, topic)
	})
	return out, nil
}

// favoritesPagePath builds the /bookmarks.php URL for the given 1-based
// page number. For page <= 1 the start parameter is omitted entirely
// (the rutracker default is the first page); otherwise start =
// 50*(page-1).
//
// Wire-equivalence note: Kotlin's
// `parameter("start", page?.let { (50 * (page - 1)).toString() })` for
// page=1 emits `?start=0`; we omit the parameter instead. Both produce
// identical upstream behaviour (rutracker.org treats absent start and
// start=0 as the first page) — the divergence is in the wire query
// string, not the response.
func favoritesPagePath(page int) string {
	if page <= 1 {
		return "/bookmarks.php"
	}
	q := url.Values{}
	q.Set("start", strconv.Itoa(50*(page-1)))
	return "/bookmarks.php?" + q.Encode()
}

// GetFavorites walks every favourites page (1..N) and returns the
// concatenated list of bookmarked topics.
//
// Flow (matches GetFavoritesUseCase.invoke):
//  1. require cookie non-empty (else ErrUnauthorized — no upstream
//     traffic).
//  2. fetch page 1 with cookie.
//  3. require IsAuthorised(html) — the auth check runs against the
//     bookmarks page response, not /index.php (parity with Kotlin's
//     `withAuthorisedCheckUseCase(api.favorites(validToken, 1))`).
//  4. parse pagesCount from page 1.
//  5. for page in 2..pagesCount: fetch + parse, appending to topics.
//  6. return FavoritesDto with the concatenated topics.
func (c *Client) GetFavorites(ctx context.Context, cookie string) (*gen.FavoritesDto, error) {
	if cookie == "" {
		return nil, ErrUnauthorized
	}
	body, status, err := c.Fetch(ctx, favoritesPagePath(1), cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, ErrUnauthorized
	}
	if !IsAuthorised(body) {
		return nil, ErrUnauthorized
	}

	pagesCount := ParseFavoritesPagesCount(body)
	first, err := ParseFavoritesPage(body)
	if err != nil {
		return nil, err
	}
	topics := first
	for page := 2; page <= pagesCount; page++ {
		b, st, err := c.Fetch(ctx, favoritesPagePath(page), cookie)
		if err != nil {
			return nil, err
		}
		if st >= 400 {
			return nil, ErrUnauthorized
		}
		more, err := ParseFavoritesPage(b)
		if err != nil {
			return nil, err
		}
		topics = append(topics, more...)
	}
	return &gen.FavoritesDto{Topics: topics}, nil
}

// AddFavorite adds a topic to the user's bookmarks. Three-step flow
// like AddComment:
//
//  1. require cookie non-empty (else ErrUnauthorized — no upstream
//     traffic).
//  2. GET /index.php; require IsAuthorised + ParseFormToken (else
//     ErrUnauthorized).
//  3. POST /bookmarks.php with form data {action=bookmark_add,
//     topic_id=<id>, form_token=<token>}; check the response contains
//     "Тема добавлена".
//
// Returns true iff the upstream emitted the success sentence. False
// indicates the upstream silently rejected the request.
func (c *Client) AddFavorite(ctx context.Context, id, cookie string) (bool, error) {
	if cookie == "" {
		return false, ErrUnauthorized
	}
	indexBody, status, err := c.Fetch(ctx, "/index.php", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		return false, ErrUnauthorized
	}
	if !IsAuthorised(indexBody) {
		return false, ErrUnauthorized
	}
	formToken, ok := ParseFormToken(indexBody)
	if !ok {
		return false, ErrUnauthorized
	}
	form := url.Values{}
	form.Set("action", "bookmark_add")
	form.Set("topic_id", id)
	form.Set("form_token", formToken)

	body, postStatus, err := c.PostForm(ctx, "/bookmarks.php", form, cookie)
	if err != nil {
		return false, err
	}
	if postStatus >= 400 {
		return false, ErrUnauthorized
	}
	return bytes.Contains(body, favoriteAddedSentence), nil
}

// RemoveFavorite mirrors AddFavorite, but with:
//   - action=bookmark_delete (instead of bookmark_add).
//   - extra form field request_origin=from_topic_page (matching
//     RuTrackerInnerApiImpl.removeFavorite).
//   - success sentence "Тема удалена" (instead of "Тема добавлена").
func (c *Client) RemoveFavorite(ctx context.Context, id, cookie string) (bool, error) {
	if cookie == "" {
		return false, ErrUnauthorized
	}
	indexBody, status, err := c.Fetch(ctx, "/index.php", cookie)
	if err != nil {
		return false, err
	}
	if status >= 400 {
		return false, ErrUnauthorized
	}
	if !IsAuthorised(indexBody) {
		return false, ErrUnauthorized
	}
	formToken, ok := ParseFormToken(indexBody)
	if !ok {
		return false, ErrUnauthorized
	}
	form := url.Values{}
	form.Set("action", "bookmark_delete")
	form.Set("topic_id", id)
	form.Set("form_token", formToken)
	form.Set("request_origin", "from_topic_page")

	body, postStatus, err := c.PostForm(ctx, "/bookmarks.php", form, cookie)
	if err != nil {
		return false, err
	}
	if postStatus >= 400 {
		return false, ErrUnauthorized
	}
	return bytes.Contains(body, favoriteRemovedSentence), nil
}
