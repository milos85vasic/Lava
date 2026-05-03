package archiveorg

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
)

// searchResponse is the JSON envelope returned by archive.org's
// advancedsearch.php endpoint.
type searchResponse struct {
	Response struct {
		NumFound int         `json:"numFound"`
		Start    int         `json:"start"`
		Docs     []searchDoc `json:"docs"`
	} `json:"response"`
}

// searchDoc is a single result document from the search endpoint.
type searchDoc struct {
	Identifier string  `json:"identifier"`
	Title      string  `json:"title"`
	Creator    *string `json:"creator"`
	Downloads  *int    `json:"downloads"`
	ItemSize   *int64  `json:"item_size"`
	Mediatype  *string `json:"mediatype"`
	Year       *string `json:"year"`
}

// SearchResult is the domain type returned by Client.Search.
type SearchResult struct {
	Page       int          `json:"page"`
	TotalPages int          `json:"totalPages"`
	Items      []SearchItem `json:"items"`
}

// SearchItem is a single search result row.
type SearchItem struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Creator   string `json:"creator,omitempty"`
	SizeBytes int64  `json:"sizeBytes,omitempty"`
	Downloads int    `json:"downloads,omitempty"`
	MediaType string `json:"mediaType,omitempty"`
	Year      string `json:"year,omitempty"`
}

// Search queries archive.org's advancedsearch.php JSON API.
// page is 1-based to match the provider contract.
func (c *Client) Search(ctx context.Context, query string, page int) (*SearchResult, error) {
	if page < 1 {
		page = 1
	}
	q := url.Values{}
	q.Set("q", query)
	q.Set("output", "json")
	q.Set("rows", "50")
	q.Set("page", strconv.Itoa(page))
	q.Set("sort[]", "downloads desc")

	body, err := c.get(ctx, "/advancedsearch.php", q)
	if err != nil {
		return nil, err
	}

	var sr searchResponse
	if err := json.Unmarshal(body, &sr); err != nil {
		return nil, fmt.Errorf("archiveorg: unmarshal search: %w", err)
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
