// Package rutracker — topic.go ports the topic-page parser
// (ParseTopicPageUseCase.kt) and the three Get* dispatchers
// (GetTopicUseCase / GetTopicPageUseCase / GetCommentsPageUseCase).
//
// Three routes share this code:
//
//   - GET /topic/{id}     → discriminated union {Topic|Torrent|CommentsPage};
//                           "magnet:?" substring on the page → torrent path.
//   - GET /topic2/{id}    → non-polymorphic TopicPageDto (always, with
//                           optional torrentData and a commentsPage block).
//   - GET /comments/{id}  → CommentsPageDto only. Implemented in comments.go.
//
// Pagination: rutracker topic pages carry 30 posts each (NOT 50 like the
// forum / search routes). This is the `30*(page-1)` start offset in
// fetchTopic (see comments.go).
//
// Title handling differs across siblings: ParseCommentsPageUseCase uses
// the raw `#topic-title` text; ParseTopicPageUseCase strips bracket-tags
// via getTitle() and exposes them as `tags` on the TorrentDataDto. We
// preserve that asymmetry exactly.
package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// ParseTopicPage parses the HTML of a /viewtopic.php?t={id} response into
// a TopicPageDto. This is the structurally-simpler sibling of GetTopic:
// /topic2/{id} ALWAYS returns this shape regardless of whether the topic
// is a torrent or a discussion thread.
func ParseTopicPage(html []byte) (*gen.TopicPageDto, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse topic page html: %w", err)
	}

	titleSel := doc.Find("#topic-title").First()
	id := queryParam(titleSel, "t")
	rawTitle := nodeText(titleSel)
	title := getTitle(rawTitle)
	tags := strings.TrimSpace(getTags(rawTitle))

	firstPost := doc.Find("tbody[id^=post]").First()
	var author *gen.AuthorDto
	if firstPost.Length() > 0 {
		a := parseAuthorFromPost(firstPost)
		author = &a
	}

	categoryAnchor := doc.Find(".nav.w100.pad_2").Find("a").Last()
	var category *gen.CategoryDto
	categoryID := queryParam(categoryAnchor, "f")
	categoryName := nodeText(categoryAnchor)
	if categoryID != "" || categoryName != "" {
		c := gen.CategoryDto{
			Id:   ptrOrNil(categoryID),
			Name: categoryName,
		}
		category = &c
	}

	torrentData := parseTorrentData(doc, firstPost, tags)

	navigation := doc.Find("#pagination > tbody > tr > td > p:nth-child(1)")
	currentPage := nodeInt(navigation.Find("b:nth-child(1)"), 1)
	totalPages := nodeInt(navigation.Find("b:nth-child(2)"), 1)

	posts := make([]gen.PostDto, 0)
	doc.Find("tbody[id^=post]").Each(func(_ int, p *goquery.Selection) {
		posts = append(posts, parsePost(p))
	})

	return &gen.TopicPageDto{
		Id:       id,
		Title:    title,
		Author:   author,
		Category: category,
		CommentsPage: gen.TopicPageCommentsDto{
			Page:  int32(currentPage),
			Pages: int32(totalPages),
			Posts: posts,
		},
		TorrentData: torrentData,
	}, nil
}

// parseAuthorFromPost ports the AuthorDto constructor used by both
// ParseCommentsPageUseCase and ParseTopicPageUseCase.parseAuthor.
func parseAuthorFromPost(post *goquery.Selection) gen.AuthorDto {
	posterAnchor := post.Find(".poster_btn").Find(".txtb").First()
	authorIDOpt, _ := queryParamOrNull(posterAnchor, "u")
	return gen.AuthorDto{
		Id:        ptrOrNil(authorIDOpt),
		Name:      nodeText(post.Find(".nick")),
		AvatarUrl: ptrOrNil(nodeAttr(post.Find(".avatar > img"), "src")),
	}
}

// parseTorrentData ports ParseTopicPageUseCase.parseTorrentData. It
// returns nil when none of seeds/leeches/status/size are present — the
// "this isn't actually a torrent" guard.
func parseTorrentData(doc *goquery.Document, firstPost *goquery.Selection, tags string) *gen.TorrentDataDto {
	header := doc.Find("table.forumline.dl_list > tbody > tr")
	seedsVal, hasSeeds := nodeIntOrNil(header.Find(".seed > b"))
	leechesVal, hasLeeches := nodeIntOrNil(header.Find(".leech > b"))

	statusSel := doc.Find("#tor-status-resp").First()
	status := parseTorrentStatus(statusSel)

	var size string
	if doc.Find("#logged-in-username").Length() > 0 {
		size = nodeText(doc.Find("#tor-size-humn"))
	} else {
		size = nodeText(doc.Find(".attach_link > ul > li:nth-child(2)"))
	}

	var date string
	var posterURL string
	if firstPost != nil && firstPost.Length() > 0 {
		date = nodeText(firstPost.Find(".p-link"))
		posterURL = nodeAttr(firstPost.Find(".postImg.postImgAligned.img-right"), "title")
	}

	magnetLink := nodeAttr(doc.Find(".magnet-link"), "href")

	// Kotlin guard: emit TorrentDataDto only if at least one of seeds /
	// leeches / status / size is present. Other fields (date, posterUrl,
	// magnetLink, tags) being non-empty is NOT enough on their own.
	if !hasSeeds && !hasLeeches && status == nil && strings.TrimSpace(size) == "" {
		return nil
	}

	td := &gen.TorrentDataDto{
		Tags:       ptrOrNil(tags),
		PosterUrl:  ptrOrNil(posterURL),
		Status:     (*gen.TorrentStatusDto)(status),
		Date:       ptrOrNil(date),
		Size:       ptrOrNil(size),
		MagnetLink: ptrOrNil(magnetLink),
	}
	// int32 clamp: same pattern as forum.go / search.go. Out-of-range
	// upstream counts surface as ABSENT rather than wrapping to negative.
	if hasSeeds && seedsVal >= math.MinInt32 && seedsVal <= math.MaxInt32 {
		v := int32(seedsVal)
		td.Seeds = &v
	}
	if hasLeeches && leechesVal >= math.MinInt32 && leechesVal <= math.MaxInt32 {
		v := int32(leechesVal)
		td.Leeches = &v
	}
	return td
}

// GetTopicPage fetches /viewtopic.php?t={id}[&start=...] and parses the
// response as a TopicPageDto. Used by the /topic2/{id} handler.
func (c *Client) GetTopicPage(ctx context.Context, id string, page *int, cookie string) (*gen.TopicPageDto, error) {
	body, err := c.fetchTopic(ctx, id, page, cookie)
	if err != nil {
		return nil, err
	}
	return ParseTopicPage(body)
}

// GetTopic is the dispatcher behind /topic/{id}. It returns a
// ForumTopicDto discriminated union — Topic, Torrent, or CommentsPage.
//
// Detection: if the page body contains the literal substring "magnet:?",
// we are looking at a torrent (the OP carries the magnet link). The
// torrent variant is owned by Task 6.6 (ParseTorrentUseCase port). For
// 6.4, the dispatcher returns a partial ForumTopicDtoTopic populated
// from the topic-page parser's identification fields. Task 6.6 will
// upgrade this branch to the full Torrent variant.
func (c *Client) GetTopic(ctx context.Context, id string, page *int, cookie string) (*gen.ForumTopicDto, error) {
	body, err := c.fetchTopic(ctx, id, page, cookie)
	if err != nil {
		return nil, err
	}

	out := &gen.ForumTopicDto{}
	if bytes.Contains(body, []byte("magnet:?")) {
		// TODO(SP-2 Task 6.6): replace this partial Topic-shape stub with
		// the full ParseTorrentUseCase result. For now the dispatcher
		// signals "this is a torrent" by populating the topic-shape
		// identification fields from the topic-page parser; downstream
		// callers that need the magnet/seeds/etc. should use /topic2.
		page, perr := ParseTopicPage(body)
		if perr != nil {
			return nil, perr
		}
		topicVariant := gen.ForumTopicDtoTopic{
			Id:       page.Id,
			Title:    page.Title,
			Author:   page.Author,
			Category: page.Category,
			Type:     gen.Topic,
		}
		if err := out.FromForumTopicDtoTopic(topicVariant); err != nil {
			return nil, fmt.Errorf("rutracker: marshal Topic variant: %w", err)
		}
		return out, nil
	}

	cp, perr := ParseCommentsPage(body)
	if perr != nil {
		return nil, perr
	}
	if err := out.FromForumTopicDtoCommentsPage(*cp); err != nil {
		return nil, fmt.Errorf("rutracker: marshal CommentsPage variant: %w", err)
	}
	return out, nil
}
