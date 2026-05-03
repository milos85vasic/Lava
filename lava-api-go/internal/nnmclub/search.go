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

// ParseSearchPage parses /forum/tracker.php response HTML into a provider SearchResult.
//
// Selector walk:
//   doc.Find("table.forumline tr").Each:
//     row.Find("a.genmed") → title anchor; href "viewtopic.php?t=12345" → id
//     row.Find(".seedmed") → seeders
//     row.Find(".leechmed") → leechers
//     6th <td> text → size
func ParseSearchPage(html []byte, page int) (*provider.SearchResult, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("nnmclub: parse search html: %w", err)
	}

	var items []provider.SearchItem
	doc.Find("table.forumline tr").Each(func(_ int, row *goquery.Selection) {
		// Skip header rows
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

		// Size is typically in the 6th column
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

		// Magnet link if present in the row
		magnetHref, _ := row.Find(`a[href^="magnet:"]`).First().Attr("href")
		if magnetHref != "" {
			item.MagnetLink = magnetHref
		}

		items = append(items, item)
	})

	// Total pages: look for pagination links and compute max page number.
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

	return &provider.SearchResult{
		Provider:   "nnmclub",
		Page:       page,
		TotalPages: totalPages,
		Results:    items,
	}, nil
}

// GetSearchPage fetches /forum/tracker.php?nm=<query>&start=<offset> and parses it.
func (c *Client) GetSearchPage(ctx context.Context, opts provider.SearchOpts, cookie string) (*provider.SearchResult, error) {
	q := url.Values{}
	if opts.Query != "" {
		q.Set("nm", opts.Query)
	}
	if opts.Page > 1 {
		q.Set("start", strconv.Itoa(50*(opts.Page-1)))
	}
	path := "/forum/tracker.php"
	if encoded := q.Encode(); encoded != "" {
		path = path + "?" + encoded
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
	return ParseSearchPage(body, opts.Page)
}
