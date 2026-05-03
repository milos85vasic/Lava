package archiveorg

import (
	"context"
	"fmt"
	"io"
)

// FileDownload holds the binary payload for a single archive.org file.
type FileDownload struct {
	Filename    string
	ContentType string
	Body        []byte
}

// Download fetches a single file from /download/{identifier}/{filename}.
func (c *Client) Download(ctx context.Context, identifier, filename string) (*FileDownload, error) {
	url := fmt.Sprintf("%s/download/%s/%s", c.baseURL, identifier, filename)
	body, status, err := c.getRaw(ctx, url)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	if status != 200 {
		b, _ := io.ReadAll(body)
		return nil, fmt.Errorf("archiveorg: download HTTP %d: %s", status, string(b))
	}

	data, err := io.ReadAll(body)
	if err != nil {
		return nil, fmt.Errorf("archiveorg: read download body: %w", err)
	}

	return &FileDownload{
		Filename: filename,
		Body:     data,
	}, nil
}
