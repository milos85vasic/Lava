// Package rutracker — torrent.go ports two Kotlin use cases:
//
//   - ParseTorrentUseCase.kt → ParseTorrent (HTML for a torrent topic →
//     full TorrentDto including the description PostElement tree).
//   - GetTorrentUseCase.kt   → (*Client).GetTorrent (wraps the topic
//     fetch with the not-found / forbidden / moderated predicates).
//   - GetTorrentFileUseCase.kt + RuTrackerInnerApiImpl.download →
//     (*Client).GetTorrentFile (binary .torrent download with upstream
//     Content-Disposition / Content-Type headers preserved).
//
// Two routes are served by this file:
//
//   - GET /torrent/{id}    → ForumTopicDtoTorrent (alias TorrentDto).
//   - GET /download/{id}   → binary .torrent stream (TorrentFile).
//
// Selector divergence with topic.go: ParseTopicPage uses the DOM check
// `#logged-in-username` (presence of an element with that id) to choose
// between `#tor-size-humn` and `.attach_link > ul > li:nth-child(2)`.
// ParseTorrentUseCase uses the substring check `html.contains("logged-in-username")`
// (literal byte search). They CAN differ on adversarial inputs (e.g. the
// substring inside a script comment with no DOM element). We are faithful
// to ParseTorrentUseCase here — bytes.Contains, not the DOM probe.
package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// TorrentFile is the binary .torrent download — filename, content type,
// and payload bytes — mirroring core/network/dto/FileDto.kt
// (contentDisposition, contentType, bytes).
type TorrentFile struct {
	ContentDisposition string
	ContentType        string
	Bytes              []byte
}

// ParseTorrent parses /viewtopic.php response HTML into a
// ForumTopicDtoTorrent (alias TorrentDto). Faithful 1:1 port of
// ParseTorrentUseCase.kt — selector walk and the substring-based
// `logged-in-username` size-branch are preserved exactly.
//
// Note: the OpenAPI ForumTopicDtoTorrent.Date field exists (int64,
// nullable) but the Kotlin parser does NOT populate it for /torrent/{id}.
// We leave it nil here for parity. Date IS surfaced on /topic2/{id} via
// TorrentDataDto.Date (string) — a separate code path in topic.go.
func ParseTorrent(html []byte) (*gen.ForumTopicDtoTorrent, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse torrent html: %w", err)
	}

	titleSel := doc.Find("#topic-title").First()
	id := queryParam(titleSel, "t")
	rawTitle := nodeText(titleSel)
	title := getTitle(rawTitle)
	tags := strings.TrimSpace(getTags(rawTitle))

	// Author — Kotlin: doc.select(".nick").first()?.text()?.let { ... }.
	// Only emit AuthorDto when .nick text is non-empty.
	var author *gen.AuthorDto
	nickSel := doc.Find(".nick").First()
	authorName := nodeText(nickSel)
	if strings.TrimSpace(authorName) != "" {
		posterAnchor := doc.Find(".poster_btn").Find(".txtb").First()
		authorIDOpt, _ := queryParamOrNull(posterAnchor, "u")
		a := gen.AuthorDto{
			Id:   ptrOrNil(authorIDOpt),
			Name: authorName,
		}
		author = &a
	}

	// Category: last anchor under .nav.w100.pad_2.
	categoryAnchor := doc.Find(".nav.w100.pad_2").Find("a").Last()
	categoryID := queryParam(categoryAnchor, "f")
	categoryName := nodeText(categoryAnchor)
	var category *gen.CategoryDto
	if categoryID != "" || categoryName != "" {
		c := gen.CategoryDto{
			Id:   ptrOrNil(categoryID),
			Name: categoryName,
		}
		category = &c
	}

	magnetLink := nodeAttr(doc.Find(".magnet-link"), "href")

	// Header row: seeds / leeches.
	header := doc.Find("table.forumline.dl_list > tbody > tr")
	seedsVal, hasSeeds := nodeIntOrNil(header.Find(".seed > b"))
	leechesVal, hasLeeches := nodeIntOrNil(header.Find(".leech > b"))

	statusSel := doc.Find("#tor-status-resp").First()
	status := parseTorrentStatus(statusSel)

	// Size branch — substring check on raw HTML, NOT a DOM probe. See
	// the package doc comment above for the rationale.
	var size string
	if bytes.Contains(html, []byte("logged-in-username")) {
		size = nodeText(doc.Find("#tor-size-humn"))
	} else {
		size = nodeText(doc.Find(".attach_link > ul > li:nth-child(2)"))
	}

	description := parseTorrentDescription(html)

	out := &gen.ForumTopicDtoTorrent{
		Type:        gen.Torrent,
		Id:          id,
		Title:       title,
		Tags:        ptrOrNil(tags),
		Author:      author,
		Category:    category,
		Status:      (*gen.TorrentStatusDto)(status),
		Size:        ptrOrNil(size),
		MagnetLink:  ptrOrNil(magnetLink),
		Description: &description,
	}
	// int32 clamp pattern (forum.go / topic.go): out-of-range upstream
	// counts surface as ABSENT rather than wrapping to negative int32.
	if hasSeeds && seedsVal >= math.MinInt32 && seedsVal <= math.MaxInt32 {
		v := int32(seedsVal)
		out.Seeds = &v
	}
	if hasLeeches && leechesVal >= math.MinInt32 && leechesVal <= math.MaxInt32 {
		v := int32(leechesVal)
		out.Leeches = &v
	}
	return out, nil
}

// parseTorrentDescription mirrors ParseTorrentUseCase.parseTorrentDescription
// (lines 47-55). The Kotlin uses try/catch to swallow ANY parse exception
// and fall back to an empty list — we reproduce that defensive shape:
// goquery.NewDocumentFromReader will not panic, but a missing first-post
// or missing .post_body MUST produce an empty Children slice without
// erroring out.
func parseTorrentDescription(html []byte) gen.TorrentDescriptionDto {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		// no-telemetry: HTML-parse failure → empty description (function
		// header explicitly documents the "lenient parser, never errors
		// out" contract). User sees "no description" instead of an error
		// page; identical to the Kotlin parser's behavior.
		return gen.TorrentDescriptionDto{Children: []gen.PostElementDto{}}
	}
	firstPost := doc.Find("tbody[id^=post]").First()
	if firstPost.Length() == 0 {
		return gen.TorrentDescriptionDto{Children: []gen.PostElementDto{}}
	}
	body := firstPost.Find(".post_body").First()
	if body.Length() == 0 {
		return gen.TorrentDescriptionDto{Children: []gen.PostElementDto{}}
	}
	return gen.TorrentDescriptionDto{Children: ParsePostBody(body)}
}

// GetTorrent fetches /viewtopic.php?t={id} with the supplied cookie and
// returns the parsed TorrentDto. Wraps the topic fetch with the same
// not-found / forbidden / moderated / blocked-region predicates as
// GetTopic / GetCommentsPage / GetTopicPage so the predicate ordering is
// uniform across siblings.
//
// `cookie` may be empty for public/anonymous fetches; ParseTorrent's
// `logged-in-username` substring check on the response HTML chooses the
// correct size-source branch automatically.
func (c *Client) GetTorrent(ctx context.Context, id, cookie string) (*gen.ForumTopicDtoTorrent, error) {
	q := url.Values{}
	q.Set("t", id)
	path := "/viewtopic.php?" + q.Encode()
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s -> %d", path, status)
	}
	if !isTopicExists(body) {
		return nil, ErrNotFound
	}
	if isTopicModerated(body) || isBlockedForRegion(body) {
		return nil, ErrForbidden
	}
	return ParseTorrent(body)
}

// GetTorrentFile fetches /dl.php?t={id} with the supplied cookie and
// returns the binary payload + the upstream Content-Disposition and
// Content-Type headers. Maps to GET /download/{id}.
//
// Empty cookie short-circuits with ErrUnauthorized — matching the Kotlin
// WithTokenVerificationUseCase precheck. Anonymous downloads are
// rejected at this layer with NO upstream traffic.
func (c *Client) GetTorrentFile(ctx context.Context, id, cookie string) (*TorrentFile, error) {
	if cookie == "" {
		return nil, ErrUnauthorized
	}
	q := url.Values{}
	q.Set("t", id)
	path := "/dl.php?" + q.Encode()
	body, status, headers, err := c.FetchWithHeaders(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s -> %d", path, status)
	}
	return &TorrentFile{
		ContentDisposition: headers.Get("Content-Disposition"),
		ContentType:        headers.Get("Content-Type"),
		Bytes:              body,
	}, nil
}
