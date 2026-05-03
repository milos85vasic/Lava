package archiveorg

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
)

// Browse queries archive.org's advancedsearch.php for items in a specific
// collection. collectionID is the archive.org collection identifier
// (e.g. "movies", "audio", "texts"). page is 1-based.
func (c *Client) Browse(ctx context.Context, collectionID string, page int) (*SearchResult, error) {
	if page < 1 {
		page = 1
	}
	q := url.Values{}
	q.Set("q", "collection:"+collectionID)
	q.Set("output", "json")
	q.Set("rows", "50")
	q.Set("page", strconv.Itoa(page))

	body, err := c.get(ctx, "/advancedsearch.php", q)
	if err != nil {
		return nil, err
	}

	var sr searchResponse
	if err := json.Unmarshal(body, &sr); err != nil {
		return nil, fmt.Errorf("archiveorg: unmarshal browse: %w", err)
	}

	items := make([]SearchItem, 0, len(sr.Response.Docs))
	for _, d := range sr.Response.Docs {
		item := SearchItem{
			ID:    d.Identifier,
			Title: d.Title,
		}
		if d.Creator != nil {
			item.Creator = *d.Creator
		}
		if d.Downloads != nil {
			item.Downloads = *d.Downloads
		}
		if d.ItemSize != nil {
			item.SizeBytes = *d.ItemSize
		}
		if d.Mediatype != nil {
			item.MediaType = *d.Mediatype
		}
		if d.Year != nil {
			item.Year = *d.Year
		}
		items = append(items, item)
	}

	totalPages := sr.Response.NumFound / 50
	if sr.Response.NumFound%50 != 0 {
		totalPages++
	}
	if totalPages < 1 {
		totalPages = 1
	}

	return &SearchResult{
		Page:       page,
		TotalPages: totalPages,
		Items:      items,
	}, nil
}
