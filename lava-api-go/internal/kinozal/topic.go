package kinozal

import (
	"bytes"
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// ParseTopicPage parses a kinozal /details.php?id=... HTML page.
func ParseTopicPage(html []byte) (*provider.TopicResult, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("kinozal: parse topic html: %w", err)
	}

	title := strings.TrimSpace(doc.Find("h1").First().Text())
	if title == "" {
		title = strings.TrimSpace(doc.Find("title").First().Text())
	}

	magnetLink, _ := doc.Find("a.magnet").Attr("href")
	if magnetLink == "" {
		magnetLink, _ = doc.Find("a[href^=magnet:]").Attr("href")
	}

	description := strings.TrimSpace(doc.Find("div.content").First().Text())

	id := ""
	doc.Find("a[href*=\"details.php?id=\"]").Each(func(_ int, s *goquery.Selection) {
		if id != "" {
			return
		}
		href, _ := s.Attr("href")
		u, err := url.Parse(href)
		if err != nil {
			// no-telemetry: scraper extracts topic IDs from <a href>
			// values; malformed href = skip this row. The downstream
			// id-list either has the row or it doesn't; an empty id-list
			// surfaces as "no results" to the user.
			return
		}
		id = u.Query().Get("id")
	})

	return &provider.TopicResult{
		Provider:    "kinozal",
		ID:          id,
		Title:       title,
		Description: description,
		MagnetLink:  magnetLink,
	}, nil
}

// GetTopic fetches /details.php?id=<id> and parses the result.
func (c *Client) GetTopic(ctx context.Context, id string, cookie string) (*provider.TopicResult, error) {
	path := "/details.php?id=" + id
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("kinozal: GET %s → %d", path, status)
	}
	result, err := ParseTopicPage(body)
	if err != nil {
		return nil, err
	}
	result.ID = id
	return result, nil
}
