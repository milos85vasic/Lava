package nnmclub

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// ParseBrowsePage parses /forum/viewforum.php response HTML into a provider BrowseResult.
// It reuses the same table.forumline row selectors as ParseSearchPage.
func ParseBrowsePage(html []byte, page int) (*provider.BrowseResult, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("nnmclub: parse browse html: %w", err)
	}

	var items []provider.SearchItem
	doc.Find("table.forumline tr").Each(func(_ int, row *goquery.Selection) {
		if row.Find("th").Length() > 0 {
			return
		}

		titleAnchor := row.Find("a.genmed").First()
		if titleAnchor.Length() == 0 {
			return
		}

		href, _ := titleAnchor.Attr("href")
		id := extractTopicID(href)
		if id == "" {
			return
		}

		title := strings.TrimSpace(titleAnchor.Text())
		seeders := parseIntText(row.Find(".seedmed").First().Text())
		leechers := parseIntText(row.Find(".leechmed").First().Text())

		var size string
		cells := row.Find("td")
		if cells.Length() >= 6 {
			size = strings.TrimSpace(cells.Eq(5).Text())
		}

		item := provider.SearchItem{
			ID:       id,
			Title:    title,
			Size:     size,
			Seeders:  seeders,
			Leechers: leechers,
		}

		magnetHref, _ := row.Find(`a[href^="magnet:"]`).First().Attr("href")
		if magnetHref != "" {
			item.MagnetLink = magnetHref
		}

		items = append(items, item)
	})

	totalPages := 1
	doc.Find("a[href*=\"start=\"]").Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		startStr := extractQueryParam(href, "start")
		if startStr != "" {
			start, _ := strconv.Atoi(startStr)
			pageNum := start/50 + 1
			if pageNum > totalPages {
				totalPages = pageNum
			}
		}
	})

	return &provider.BrowseResult{
		Provider: "nnmclub",
		Page:     page,
		Items:    items,
	}, nil
}

// GetBrowsePage fetches /forum/viewforum.php?f=<forumID>&start=<offset> and parses it.
func (c *Client) GetBrowsePage(ctx context.Context, forumID string, page int, cookie string) (*provider.BrowseResult, error) {
	q := url.Values{}
	q.Set("f", forumID)
	if page > 1 {
		q.Set("start", strconv.Itoa(50*(page-1)))
	}
	path := "/forum/viewforum.php?" + q.Encode()
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
	return ParseBrowsePage(body, page)
}
