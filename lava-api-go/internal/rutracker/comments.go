// Package rutracker — comments.go ports ParseCommentsPageUseCase.kt.
// Used by /comments/{id} and (indirectly) the non-torrent branch of
// /topic/{id}. Returns the OpenAPI ForumTopicDtoCommentsPage shape (alias:
// CommentsPageDto).
//
// Selector walk (matches ParseCommentsPageUseCase.kt:10-47):
//
//	#topic-title         queryParam("t") → id; nodeText() → title
//	.nav.w100.pad_2 a    last → category {id from queryParam("f"), name from text}
//	tbody[id^=post] first
//	  .magnet-link[href] → IF non-empty, REMOVE the entire post (it's the
//	                       torrent-data first post, not a real comment).
//	#pagination > tbody > tr > td > p:nth-child(1)
//	  b:nth-child(1)     → page (default 1)
//	  b:nth-child(2)     → pages (default 1)
//	tbody[id^=post] each
//	  .post_body[id]      strip "p-" prefix → post id
//	  .poster_btn .txtb   queryParamOrNull("u") → author.id
//	  .nick               text → author.name
//	  .avatar > img[src]  → author.avatarUrl
//	  .p-link             text → date
//	  .post_body          ParsePostBody(...) → children
//
// Top-level Author at the comments-page level is intentionally NOT
// populated — the Kotlin parser leaves it null (the per-post authors are
// what matters for a comments view).
package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// ParseCommentsPage parses the HTML of a /viewtopic.php?t={id} response
// (when no torrent magnet is present, or the caller is the dedicated
// /comments/{id} route) into the CommentsPageDto.
func ParseCommentsPage(html []byte) (*gen.CommentsPageDto, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse comments html: %w", err)
	}

	titleSel := doc.Find("#topic-title").First()
	id := queryParam(titleSel, "t")
	title := nodeText(titleSel)

	categoryNodes := doc.Find(".nav.w100.pad_2").Find("a")
	categoryLast := categoryNodes.Last()
	categoryID := queryParam(categoryLast, "f")
	categoryName := nodeText(categoryLast)

	// First-post magnet stripping. The first tbody[id^=post] on a topic
	// page is the OP — when the topic is a torrent, that OP carries the
	// magnet link. The Kotlin parser drops it from the comments list so a
	// pure comments rendering doesn't double up the torrent's body.
	firstPost := doc.Find("tbody[id^=post]").First()
	if firstPost.Length() > 0 {
		magnet := firstPost.Find(".magnet-link")
		href, _ := magnet.Attr("href")
		if strings.TrimSpace(href) != "" {
			firstPost.Remove()
		}
	}

	navigation := doc.Find("#pagination > tbody > tr > td > p:nth-child(1)")
	currentPage := nodeInt(navigation.Find("b:nth-child(1)"), 1)
	totalPages := nodeInt(navigation.Find("b:nth-child(2)"), 1)

	posts := make([]gen.PostDto, 0)
	doc.Find("tbody[id^=post]").Each(func(_ int, p *goquery.Selection) {
		posts = append(posts, parsePost(p))
	})

	out := &gen.CommentsPageDto{
		Type:  gen.CommentsPage,
		Id:    id,
		Title: title,
		Page:  int32(currentPage),
		Pages: int32(totalPages),
		Posts: posts,
	}
	if categoryID != "" || categoryName != "" {
		cat := gen.CategoryDto{
			Id:   ptrOrNil(categoryID),
			Name: categoryName,
		}
		out.Category = &cat
	}
	return out, nil
}

// parsePost ports the per-row PostDto construction shared between the
// comments-page parser and the topic-page parser.
func parsePost(p *goquery.Selection) gen.PostDto {
	postBody := p.Find(".post_body").First()
	rawID, _ := postBody.Attr("id")
	// Kotlin: substringAfter("p-") — "after" matches strings.SplitN([id], "p-", 2)[1]
	// when the prefix exists; falls back to the original string when not.
	postID := rawID
	if i := strings.Index(rawID, "p-"); i >= 0 {
		postID = rawID[i+len("p-"):]
	}

	posterAnchor := p.Find(".poster_btn").Find(".txtb").First()
	authorIDOpt, _ := queryParamOrNull(posterAnchor, "u")

	authorName := nodeText(p.Find(".nick"))
	avatarSrc := nodeAttr(p.Find(".avatar > img"), "src")

	author := gen.AuthorDto{
		Id:        ptrOrNil(authorIDOpt),
		Name:      authorName,
		AvatarUrl: ptrOrNil(avatarSrc),
	}

	date := nodeText(p.Find(".p-link"))
	children := ParsePostBody(postBody)

	return gen.PostDto{
		Id:       postID,
		Author:   author,
		Date:     date,
		Children: children,
	}
}

// GetCommentsPage fetches /viewtopic.php?t={id}[&start=...] and parses
// the result via ParseCommentsPage. `page` is 1-based; nil/<=1 omits the
// `start` parameter. Topic pages are 30 posts each (NOT 50 like the
// forum/search routes — see RuTrackerInnerApiImpl.topic).
//
// Returns ErrNotFound / ErrForbidden when the upstream signals those
// states via the Russian error sentences (see topic_predicates.go).
func (c *Client) GetCommentsPage(ctx context.Context, id string, page *int, cookie string) (*gen.CommentsPageDto, error) {
	body, err := c.fetchTopic(ctx, id, page, cookie)
	if err != nil {
		return nil, err
	}
	return ParseCommentsPage(body)
}

// fetchTopic is shared between GetTopic, GetTopicPage and GetCommentsPage:
// it builds the /viewtopic.php URL, fetches it, and applies the three
// not-found / forbidden predicates BEFORE returning the body. Pulling
// this into a single helper means the predicate ordering (notFound →
// moderated → blocked-region) cannot drift between callers.
func (c *Client) fetchTopic(ctx context.Context, id string, page *int, cookie string) ([]byte, error) {
	q := url.Values{}
	q.Set("t", id)
	if page != nil && *page > 1 {
		q.Set("start", strconv.Itoa(30*(*page-1)))
	}
	path := "/viewtopic.php?" + q.Encode()
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s → %d", path, status)
	}
	if !isTopicExists(body) {
		return nil, ErrNotFound
	}
	if isTopicModerated(body) || isBlockedForRegion(body) {
		return nil, ErrForbidden
	}
	return body, nil
}
