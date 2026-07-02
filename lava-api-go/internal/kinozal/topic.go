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

	// kinozal details pages carry NO magnet link (the torrent is fetched via
	// download.php); these generic selectors stay only for synthetic / non-kinozal
	// pages and resolve to empty on a real kinozal page.
	magnetLink, _ := doc.Find("a.magnet").Attr("href")
	if magnetLink == "" {
		magnetLink, _ = doc.Find("a[href^=magnet:]").Attr("href")
	}

	// Description: a real kinozal /details.php page renders the item metadata
	// (Оригинальное название / О фильме / Размер …) inside `div.bx1` blocks. The
	// historical `div.content` selector matched the ENTIRE content column (menu +
	// comments + everything), so the description was an unreadable blob; on many
	// layouts `div.content` is absent entirely and the description came back empty.
	// Prefer the focused bx1 blocks; fall back to div.content for synthetic /
	// non-kinozal pages so the generic path still works.
	var descParts []string
	doc.Find("div.bx1").Each(func(_ int, s *goquery.Selection) {
		if t := strings.TrimSpace(s.Text()); t != "" {
			descParts = append(descParts, t)
		}
	})
	description := strings.Join(descParts, "\n\n")
	if description == "" {
		description = strings.TrimSpace(doc.Find("div.content").First().Text())
	}

	// Poster: kinozal shows the cover image as `img.p200`.
	posterURL, _ := doc.Find("img.p200").Attr("src")

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

	// Download affordance: the actual /download.php anchor on a kinozal details
	// page is gated behind login, so an anonymous scrape sees no download link.
	// Derive the canonical download route from the topic id — the SAME route
	// ProviderAdapter.DownloadFile issues (/download.php?id=<id>). Without this the
	// topic detail renders with no working download button for the user.
	downloadURL := ""
	if id != "" {
		downloadURL = "/download.php?id=" + id
	}

	return &provider.TopicResult{
		Provider:    "kinozal",
		ID:          id,
		Title:       title,
		Description: description,
		PosterURL:   posterURL,
		MagnetLink:  magnetLink,
		DownloadURL: downloadURL,
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
	// The request id is authoritative — prefer it over the anchor-parsed id so the
	// id + derived download route are always keyed on the topic the caller asked
	// for (ParseTopicPage derives DownloadURL from the parsed anchor id, which is
	// empty on layouts without a self-referential details.php link).
	result.ID = id
	if id != "" {
		result.DownloadURL = "/download.php?id=" + id
	}
	return result, nil
}
