package kinozal

import (
	"context"
	"fmt"
	"net/url"
	"strconv"

	"digital.vasic.lava.apigo/internal/provider"
)

// Browse fetches /browse.php?c=<categoryID>&page=<page> and parses the result.
func (c *Client) Browse(ctx context.Context, categoryID string, page int, cookie string) (*provider.BrowseResult, error) {
	q := url.Values{}
	q.Set("c", categoryID)
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
	searchResult, err := ParseSearchPage(body)
	if err != nil {
		return nil, err
	}
	return &provider.BrowseResult{
		Provider: "kinozal",
		Page:     page,
		Items:    searchResult.Results,
	}, nil
}
