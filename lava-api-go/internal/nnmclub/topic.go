package nnmclub

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// ParseTopicPage parses /forum/viewtopic.php?t=<id> response HTML into a provider TopicResult.
//
// Selector walk:
//
//	doc.Find("div.postbody").First().text() → description
//	doc.Find("a[href^=magnet:]").First().attr("href") → magnetLink
//	doc.Find("a[href*=download.php?id=]").First().attr("href") → downloadUrl
//
// The real nnmclub.to viewtopic page has NO #pagecontent element (the old
// selector "#pagecontent .postbody" matched nothing, so the description parsed
// silently EMPTY for every user). Its #contentdiv is the hidden (display:none)
// file-list spoiler, not the description. The opening post's description is the
// FIRST div.postbody; a page-top <span class="postbody"> anchor is excluded by
// pinning the selector to div.postbody. Verified against a captured real page in
// testdata/topic/topic_real.html.
func ParseTopicPage(html []byte, id string) (*provider.TopicResult, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("nnmclub: parse topic html: %w", err)
	}

	// Title: first .maintitle or the page title
	title := strings.TrimSpace(doc.Find(".maintitle").First().Text())
	if title == "" {
		title = strings.TrimSpace(doc.Find("title").First().Text())
	}

	// Description from the first post body. nnmclub renders the opening post's
	// release description in the first div.postbody; there is no #pagecontent
	// wrapper and #contentdiv is the hidden file-list spoiler (see doc above).
	description := strings.TrimSpace(doc.Find("div.postbody").First().Text())

	// Magnet link
	magnetLink, _ := doc.Find(`a[href^="magnet:"]`).First().Attr("href")

	// Download URL
	var downloadURL string
	doc.Find(`a[href*="download.php"]`).Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		if strings.Contains(href, "id=") && downloadURL == "" {
			downloadURL = href
		}
	})

	return &provider.TopicResult{
		Provider:    "nnmclub",
		ID:          id,
		Title:       title,
		Description: description,
		MagnetLink:  magnetLink,
		DownloadURL: downloadURL,
	}, nil
}

// GetTopicPage fetches /forum/viewtopic.php?t=<id> and parses it.
func (c *Client) GetTopicPage(ctx context.Context, id string, page int, cookie string) (*provider.TopicResult, error) {
	path := "/forum/viewtopic.php?t=" + id
	if page > 1 {
		path += "&start=" + fmt.Sprintf("%d", 50*(page-1))
	}
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if status >= 400 {
		return nil, fmt.Errorf("nnmclub: GET %s → %d", path, status)
	}
	return ParseTopicPage(body, id)
}
