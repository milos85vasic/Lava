// Package rutracker — forum.go ports two Kotlin use cases:
//   - GetForumUseCase.kt        → ParseForum         (HTML for /forum)
//   - GetCategoryPageUseCase.kt → ParseCategoryPage  (HTML for /forum/{id})
//
// Both parsers are 1:1 selector-faithful with the Kotlin Jsoup walker.
// We use github.com/PuerkitoBio/goquery (the Jsoup-equivalent for Go);
// the per-selector mapping is documented inline at each call site.
//
// Generated-types decision (Sixth Law clause 1 transparency note):
// We REUSE the oapi-codegen types from internal/gen/server (`ForumDto`,
// `CategoryDto`, `CategoryPageDto`, `SectionDto`, `ForumTopicDto`,
// `AuthorDto`, `TorrentStatusDto`) without wrapping. The discriminated
// union `ForumTopicDto` is populated via the generated `FromForum…`
// methods so the JSON shape on the wire is identical to the OpenAPI
// contract. This is the simplest path to satisfy "spec is the contract"
// (lava-api-go CLAUDE.md) — Phase 7 handlers can return these structs
// directly with no boundary translation.
package rutracker

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// ErrNotFound is returned when rutracker.org reports the forum does not
// exist. Phase 7 handlers map this to HTTP 404.
var ErrNotFound = errors.New("rutracker: forum not found")

// ErrForbidden is returned when the forum is gated behind special user
// permissions. Phase 7 handlers map this to HTTP 403.
var ErrForbidden = errors.New("rutracker: forum forbidden")

// ParseForum parses /forum response HTML into the forum-tree DTO.
//
// Selector walk (matches GetForumUseCase.kt):
//
//	doc.Find(".tree-root")               // each top-level category
//	  category.Find(".c-title").Attr("title")  // category name
//	  category.Children().eq(0).Children().eq(1).Children()
//	                                     // forum-row container
//	    row.Children().eq(0).Find("a")[href]   // forum id (URL)
//	    row.Children().eq(0).Find("a").Text()  // forum title
//	    row.Children().eq(1).Children() (optional)
//	                                     // subforums
//	      sub.Find("a")[href]            // subforum id (URL)
//	      sub.Text()                     // subforum title
//
// The "no categories" defensive path (zero `.tree-root` matches) returns
// a ForumDto with an empty Children slice — never panics.
func ParseForum(html []byte) (*gen.ForumDto, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse forum html: %w", err)
	}
	categories := make([]gen.CategoryDto, 0)
	doc.Find(".tree-root").Each(func(_ int, root *goquery.Selection) {
		title, _ := root.Find(".c-title").First().Attr("title")
		// Kotlin: categoryElement.child(0).child(1).children()
		// goquery has no direct .child(idx); use Children().Eq(idx).
		forumElements := root.Children().Eq(0).Children().Eq(1).Children()
		forums := make([]gen.CategoryDto, 0, forumElements.Length())
		forumElements.Each(func(_ int, fe *goquery.Selection) {
			anchor := fe.Children().Eq(0).Find("a").First()
			forumID := nodeAttr(anchor, "href")
			forumTitle := nodeText(anchor)
			subforums := make([]gen.CategoryDto, 0)
			if fe.Children().Length() > 1 {
				fe.Children().Eq(1).Children().Each(func(_ int, sub *goquery.Selection) {
					subAnchor := sub.Find("a").First()
					subID := nodeAttr(subAnchor, "href")
					subTitle := nodeText(sub)
					subforums = append(subforums, gen.CategoryDto{
						Id:   ptrOrNil(subID),
						Name: subTitle,
					})
				})
			}
			subforumsCopy := subforums
			forums = append(forums, gen.CategoryDto{
				Id:       ptrOrNil(forumID),
				Name:     forumTitle,
				Children: &subforumsCopy,
			})
		})
		forumsCopy := forums
		categories = append(categories, gen.CategoryDto{
			Name:     title,
			Children: &forumsCopy,
		})
	})
	return &gen.ForumDto{Children: categories}, nil
}

// isForumExists ports GetCategoryPageUseCase.isForumExists. Two distinct
// rutracker error strings indicate a non-existent forum.
func isForumExists(html []byte) bool {
	return !bytes.Contains(html, []byte("Ошибочный запрос: не задан forum_id")) &&
		!bytes.Contains(html, []byte("Такого форума не существует"))
}

// isForumAvailableForUser ports GetCategoryPageUseCase.isForumAvailableForUser.
func isForumAvailableForUser(html []byte) bool {
	return !bytes.Contains(html, []byte("Извините, только пользователи со специальными правами"))
}

// ParseCategoryPage parses /forum/{id} response HTML into the
// CategoryPageDto. Returns ErrNotFound / ErrForbidden when the upstream
// signals those states (string-match on the Russian error sentences,
// matching the Kotlin parser exactly).
//
// Selector walk (matches GetCategoryPageUseCase.parseCategoryPage):
//
//	doc.Find("#pagination > p:nth-child(1) > b:nth-child(1)")  // currentPage
//	doc.Find("#pagination > p:nth-child(1) > b:nth-child(2)")  // totalPages
//	doc.Find(".maintitle")                                     // forumName
//	doc.Find(".forumlink > a")                                 // subforums
//	doc.Find("table.vf-table.forum > tbody > tr")              // rows
//	  row contains .topicSep child  → section header
//	  row.HasClass("hl-tr")          → topic / torrent row
//	    td#id, a.topicAuthor[href ?u=], .seedmed, .leechmed,
//	    .f-dl, .tt-text, parseTorrentStatus(row)
//
// `forumID` is the path parameter passed to the handler — used only to
// populate `CategoryPageDto.category.id` (the page HTML does not echo it
// back reliably).
func ParseCategoryPage(html []byte, forumID string) (*gen.CategoryPageDto, error) {
	if !isForumExists(html) {
		return nil, ErrNotFound
	}
	if !isForumAvailableForUser(html) {
		return nil, ErrForbidden
	}
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse category html: %w", err)
	}

	currentPage := nodeInt(doc.Find("#pagination > p:nth-child(1) > b:nth-child(1)"), 1)
	totalPages := nodeInt(doc.Find("#pagination > p:nth-child(1) > b:nth-child(2)"), 1)
	forumName := nodeText(doc.Find(".maintitle"))

	// Subforum links: each anchor under `.forumlink` carries its forum
	// id in the `f` query parameter of the href.
	children := make([]gen.CategoryDto, 0)
	doc.Find(".forumlink > a").Each(func(_ int, a *goquery.Selection) {
		id := queryParam(a, "f")
		name := nodeText(a)
		children = append(children, gen.CategoryDto{Id: ptrOrNil(id), Name: name})
	})

	// Topic rows. Two row classes carry data: `topicSep` rows are
	// section dividers (their text is the section name); `hl-tr` rows
	// are actual topics/torrents. Anything else is decoration.
	sections := make([]gen.SectionDto, 0)
	topics := make([]gen.ForumTopicDto, 0)
	var currentSection *string
	currentSectionIDs := make([]string, 0)

	flushSection := func() {
		if currentSection != nil {
			ids := append([]string(nil), currentSectionIDs...)
			sections = append(sections, gen.SectionDto{Name: *currentSection, Topics: ids})
		}
	}

	doc.Find("table.vf-table.forum > tbody > tr").Each(func(_ int, row *goquery.Selection) {
		if hasTopicSepChild(row) {
			flushSection()
			name := nodeText(row)
			currentSection = &name
			currentSectionIDs = currentSectionIDs[:0]
			return
		}
		if !row.HasClass("hl-tr") {
			return
		}
		// `td.attr("id")` in Kotlin reads the first td's id attribute.
		// goquery's Selection.Attr likewise reads the first matched
		// element's attribute.
		id := nodeAttr(row.Find("td").First(), "id")
		authorAnchor := row.Find("a.topicAuthor").First()
		authorIDOpt, _ := queryParamOrNull(authorAnchor, "u")
		authorName := nodeText(authorAnchor)

		seedsVal, hasSeeds := nodeIntOrNil(row.Find(".seedmed"))
		leechesVal, hasLeeches := nodeIntOrNil(row.Find(".leechmed"))

		// `.f-dl` text — Kotlin replaces NBSP with a regular space; our
		// nodeText already does that (see collapseWhitespace).
		size := nodeText(row.Find(".f-dl"))
		fullTitle := nodeText(row.Find(".tt-text"))
		title := getTitle(fullTitle)
		tags := getTags(fullTitle)
		status := parseTorrentStatus(row)

		var author gen.AuthorDto
		if strings.TrimSpace(authorName) == "" {
			author = gen.AuthorDto{
				Name: nodeText(row.Find(".vf-col-author")),
			}
		} else {
			author = gen.AuthorDto{
				Id:   ptrOrNil(authorIDOpt),
				Name: authorName,
			}
		}

		var topic gen.ForumTopicDto
		if status == nil {
			// Kotlin uses `fullTitle` as the title for plain Topic rows.
			t := gen.ForumTopicDtoTopic{
				Author: &author,
				Id:     id,
				Title:  fullTitle,
				Type:   "Topic",
			}
			if err := topic.FromForumTopicDtoTopic(t); err != nil {
				// Should be impossible — local marshal of struct value.
				return
			}
		} else {
			tt := gen.ForumTopicDtoTorrent{
				Author: &author,
				Id:     id,
				Title:  title,
				Tags:   ptrOrNil(strings.TrimSpace(tags)),
				Size:   ptrOrNil(size),
				Status: (*gen.TorrentStatusDto)(status),
				Type:   "Torrent",
			}
			if hasSeeds {
				v := int32(seedsVal)
				tt.Seeds = &v
			}
			if hasLeeches {
				v := int32(leechesVal)
				tt.Leeches = &v
			}
			if err := topic.FromForumTopicDtoTorrent(tt); err != nil {
				return
			}
		}
		topics = append(topics, topic)
		currentSectionIDs = append(currentSectionIDs, id)
	})
	flushSection()

	// Kotlin: `sections.takeIf { it.size > 1 } ?: emptyList()`.
	if len(sections) <= 1 {
		sections = sections[:0]
	}

	out := &gen.CategoryPageDto{
		Category: gen.CategoryDto{
			Id:   ptrOrNil(forumID),
			Name: forumName,
		},
		Page:  int32(currentPage),
		Pages: int32(totalPages),
	}
	if len(sections) > 0 {
		out.Sections = &sections
	} else {
		empty := []gen.SectionDto{}
		out.Sections = &empty
	}
	out.Children = &children
	out.Topics = &topics
	return out, nil
}

// hasTopicSepChild returns true when any direct child of `row` carries
// the `topicSep` class. Mirrors Kotlin's `element.children().any { it.hasClass("topicSep") }`.
func hasTopicSepChild(row *goquery.Selection) bool {
	found := false
	row.Children().EachWithBreak(func(_ int, c *goquery.Selection) bool {
		if c.HasClass("topicSep") {
			found = true
			return false
		}
		return true
	})
	return found
}

// ptrOrNil returns &s when s is non-empty, nil otherwise. Convenience
// for *string fields in the generated DTOs that should be omitted from
// JSON output when absent.
func ptrOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// GetForum fetches /forum.php?map=0 and parses the resulting HTML. The
// path matches RuTrackerInnerApiImpl.kt's `forum()` (Index = "index.php"
// + parameter "map=0") — but the actual upstream Kotlin code targets
// "index.php?map=0". We use the same path here so the Go service hits
// the same upstream endpoint.
func (c *Client) GetForum(ctx context.Context, cookie string) (*gen.ForumDto, error) {
	body, status, err := c.Fetch(ctx, "/index.php?map=0", cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET /index.php?map=0 → %d", status)
	}
	return ParseForum(body)
}

// GetCategoryPage fetches /viewforum.php?f={id}[&start=...] and parses
// the result. `page` is 1-based; nil/<=1 omits the start parameter.
// rutracker pages are 50 topics each (`50 * (page-1)` start offset),
// matching RuTrackerInnerApiImpl.kt's `category(id, page)`.
func (c *Client) GetCategoryPage(ctx context.Context, forumID string, page *int, cookie string) (*gen.CategoryPageDto, error) {
	q := url.Values{}
	q.Set("f", forumID)
	if page != nil && *page > 1 {
		q.Set("start", strconv.Itoa(50*(*page-1)))
	}
	path := "/viewforum.php?" + q.Encode()
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s → %d", path, status)
	}
	return ParseCategoryPage(body, forumID)
}
