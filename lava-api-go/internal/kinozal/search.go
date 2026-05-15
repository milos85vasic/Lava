package kinozal

import (
	"bytes"
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	"digital.vasic.lava.apigo/internal/provider"
)

// ParseSearchPage parses a kinozal search/browse HTML page into a provider-agnostic
// SearchResult. It expects result rows inside <table class="tumblers">.
func ParseSearchPage(html []byte) (*provider.SearchResult, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("kinozal: parse search html: %w", err)
	}

	items := make([]provider.SearchItem, 0)
	doc.Find("table.tumblers tr").Each(func(_ int, row *goquery.Selection) {
		// Skip header rows.
		if row.Find("th").Length() > 0 {
			return
		}
		titleAnchor := row.Find("a.namer").First()
		title := strings.TrimSpace(titleAnchor.Text())
		if title == "" {
			return
		}
		href, _ := titleAnchor.Attr("href")
		id := extractIDFromHref(href)
		if id == "" {
			return
		}

		var size string
		var seeders, leechers int
		row.Find("span.sider").Each(func(_ int, s *goquery.Selection) {
			text := strings.TrimSpace(s.Text())
			switch {
			case strings.Contains(text, "GB") || strings.Contains(text, "MB") || strings.Contains(text, "KB") || text == "B":
				size = text
			case strings.HasPrefix(text, "S:"):
				seeders = parseIntAfterColon(text)
			case strings.HasPrefix(text, "L:"):
				leechers = parseIntAfterColon(text)
			}
		})

		magnetLink, _ := row.Find("a[href^=magnet:]").Attr("href")

		items = append(items, provider.SearchItem{
			ID:         id,
			Title:      title,
			Size:       size,
			Seeders:    seeders,
			Leechers:   leechers,
			MagnetLink: magnetLink,
		})
	})

	totalPages := parsePagination(doc)
	return &provider.SearchResult{
		Provider:   "kinozal",
		Page:       0,
		TotalPages: totalPages,
		Results:    items,
	}, nil
}

// Search fetches /browse.php?s=<query>&page=<page> and parses the result.
func (c *Client) Search(ctx context.Context, query string, page int, cookie string) (*provider.SearchResult, error) {
	q := url.Values{}
	q.Set("s", query)
	if page > 0 {
		q.Set("page", strconv.Itoa(page))
	}
	path := "/browse.php?" + q.Encode()
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("kinozal: GET %s → %d", path, status)
	}
	result, err := ParseSearchPage(body)
	if err != nil {
		return nil, err
	}
	result.Page = page
	return result, nil
}

func extractIDFromHref(href string) string {
	u, err := url.Parse(href)
	if err != nil {
		// no-telemetry: scraper helper — empty return signals "id absent"
		// to caller, which is the same shape as a happy-path absent id.
		return ""
	}
	return u.Query().Get("id")
}

func parseIntAfterColon(s string) int {
	parts := strings.SplitN(s, ":", 2)
	if len(parts) != 2 {
		return 0
	}
	v, _ := strconv.Atoi(strings.TrimSpace(parts[1]))
	return v
}

func parsePagination(doc *goquery.Document) int {
	maxPage := 0
	doc.Find("a[href]").Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		u, err := url.Parse(href)
		if err != nil {
			// no-telemetry: pagination probe — malformed href means this
			// link is not part of the pagination set; skip it and continue
			// scanning. The maxPage value reflects whatever links DID parse.
			return
		}
		if u.Path == "/browse.php" {
			if p := u.Query().Get("page"); p != "" {
				if n, err := strconv.Atoi(p); err == nil && n > maxPage {
					maxPage = n
				}
			}
		}
	})
	if maxPage == 0 {
		return 1
	}
	return maxPage + 1
}
