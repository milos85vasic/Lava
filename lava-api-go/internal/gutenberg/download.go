package gutenberg

import (
	"context"
	"fmt"

	"digital.vasic.lava.apigo/internal/provider"
)

func (c *Client) DownloadBook(ctx context.Context, id string) (*provider.FileDownload, error) {
	path := "/books/" + id + "/"
	var b book
	if err := c.getJSON(ctx, path, &b); err != nil {
		return nil, err
	}

	url := pickBestFormatURL(b.Formats)
	if url == "" {
		return nil, fmt.Errorf("gutenberg: book %s has no downloadable formats", id)
	}

	body, err := c.downloadBytes(ctx, url)
	if err != nil {
		return nil, err
	}

	filename := filenameFromURL(url)
	contentType := "application/octet-stream"

	return &provider.FileDownload{
		Provider:    "gutenberg",
		ID:          id,
		Filename:    filename,
		ContentType: contentType,
		Body:        body,
	}, nil
}
