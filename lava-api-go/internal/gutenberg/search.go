package gutenberg

import (
	"context"
	"net/url"
	"strconv"

	"digital.vasic.lava.apigo/internal/provider"
)

func (c *Client) Search(ctx context.Context, query string, page int) (*provider.SearchResult, error) {
	q := url.Values{}
	if query != "" {
		q.Set("search", query)
	}
	if page > 0 {
		q.Set("page", strconv.Itoa(page))
	}
	path := "/books/"
	if encoded := q.Encode(); encoded != "" {
		path = path + "?" + encoded
	}

	var res bookList
	if err := c.getJSON(ctx, path, &res); err != nil {
		return nil, err
	}

	out := &provider.SearchResult{
		Provider:   "gutenberg",
		Page:       page,
		TotalPages: estimateTotalPages(res.Count),
		Results:    make([]provider.SearchItem, 0, len(res.Results)),
	}
	for _, b := range res.Results {
		out.Results = append(out.Results, bookToSearchItem(b))
	}
	return out, nil
}

func bookToSearchItem(b book) provider.SearchItem {
	creator := ""
	if len(b.Authors) > 0 {
		creator = b.Authors[0].Name
	}
	return provider.SearchItem{
		ID:       strconv.Itoa(b.ID),
		Title:    b.Title,
		Creator:  creator,
		Category: primarySubject(b.Subjects),
		Format:   bestFormatName(b.Formats),
	}
}

func estimateTotalPages(count int) int {
	if count <= 0 {
		return 1
	}
	pages := count / 32
	if count%32 > 0 {
		pages++
	}
	if pages < 1 {
		pages = 1
	}
	return pages
}
