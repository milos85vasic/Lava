package gutenberg

import (
	"context"
	"net/url"
	"strconv"

	"digital.vasic.lava.apigo/internal/provider"
)

func (c *Client) Browse(ctx context.Context, topic string, page int) (*provider.BrowseResult, error) {
	q := url.Values{}
	if topic != "" {
		q.Set("topic", topic)
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

	out := &provider.BrowseResult{
		Provider: "gutenberg",
		Page:     page,
		Items:    make([]provider.SearchItem, 0, len(res.Results)),
	}
	for _, b := range res.Results {
		out.Items = append(out.Items, bookToSearchItem(b))
	}
	return out, nil
}
