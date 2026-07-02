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
	doc.Find("table.t_peer tr").Each(func(_ int, row *goquery.Selection) {
		// Title cell is td.nam > a. The header row (tr.mn) carries td.z / td.zl
		// cells but NO td.nam anchor, so title=="" there and the row is skipped.
		titleAnchor := row.Find("td.nam a").First()
		title := strings.TrimSpace(titleAnchor.Text())
		if title == "" {
			return
		}
		href, _ := titleAnchor.Attr("href")
		id := extractIDFromHref(href)
		if id == "" {
			return
		}

		// Size lives in a td.s cell whose text carries a Cyrillic unit
		// (ГБ/МБ/КБ/ТБ). Other td.s cells in the same row hold the comment
		// count and the upload date, so pick the first one that is a size.
		size := ""
		row.Find("td.s").EachWithBreak(func(_ int, s *goquery.Selection) bool {
			if t := strings.TrimSpace(s.Text()); isSizeText(t) {
				size = t
				return false
			}
			return true
		})

		// Seeders (Сидов) and leechers (Пиров) are bare integers in dedicated
		// cells.
		seeders := atoiTrim(row.Find("td.sl_s").First().Text())
		leechers := atoiTrim(row.Find("td.sl_p").First().Text())

		// The search/browse listing carries no magnet link (the magnet lives on
		// the topic detail page); leave it empty when absent.
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

// isSizeText reports whether t is a kinozal size cell, e.g. "1.71 ГБ".
// kinozal serves Cyrillic size units (ГБ/МБ/КБ/ТБ), not the Latin GB/MB/KB the
// original parser matched — that Latin-unit mismatch was why every real search
// parsed zero size and (via the same synthetic-fixture drift) zero rows.
func isSizeText(t string) bool {
	for _, u := range []string{"ГБ", "МБ", "КБ", "ТБ"} {
		if strings.Contains(t, u) {
			return true
		}
	}
	return false
}

// atoiTrim parses the leading integer of a trimmed cell, returning 0 on any
// non-numeric content (a malformed seeders/leechers cell degrades to 0 rather
// than corrupting the whole page parse).
func atoiTrim(s string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(s))
	return n
}

func parsePagination(doc *goquery.Document) int {
	maxPage := 0
	// Real kinozal pagination links live inside div.paginator and are RELATIVE,
	// e.g. href="?s=1080p&g=0&page=99" — url.Parse gives an empty Path for those,
	// so the old u.Path=="/browse.php" absolute-path guard never matched and
	// TotalPages was stuck at 1 even across 100-page result sets.
	doc.Find("div.paginator a[href]").Each(func(_ int, s *goquery.Selection) {
		href, _ := s.Attr("href")
		u, err := url.Parse(href)
		if err != nil {
			// no-telemetry: pagination probe — malformed href means this
			// link is not part of the pagination set; skip it and continue
			// scanning. The maxPage value reflects whatever links DID parse.
			return
		}
		if p := u.Query().Get("page"); p != "" {
			if n, err := strconv.Atoi(p); err == nil && n > maxPage {
				maxPage = n
			}
		}
	})
	if maxPage == 0 {
		return 1
	}
	return maxPage + 1
}
