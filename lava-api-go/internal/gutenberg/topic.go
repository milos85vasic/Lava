package gutenberg

import (
	"context"
	"fmt"
	"strconv"

	"digital.vasic.lava.apigo/internal/provider"
)

func (c *Client) GetBook(ctx context.Context, id string) (*provider.TopicResult, error) {
	path := "/books/" + id + "/"

	var b book
	if err := c.getJSON(ctx, path, &b); err != nil {
		return nil, err
	}

	creator := ""
	if len(b.Authors) > 0 {
		creator = b.Authors[0].Name
	}

	downloadURL := pickBestFormatURL(b.Formats)

	return &provider.TopicResult{
		Provider:    "gutenberg",
		ID:          strconv.Itoa(b.ID),
		Title:       b.Title,
		Description: formatDescription(b),
		DownloadURL: downloadURL,
		Files: []provider.TopicFile{
			{Name: b.Title + " — " + creator, Size: ""},
		},
	}, nil
}

func formatDescription(b book) string {
	desc := "Authors: "
	for i, a := range b.Authors {
		if i > 0 {
			desc += ", "
		}
		desc += a.Name
	}
	if len(b.Subjects) > 0 {
		desc += "\nSubjects: " + joinStrings(b.Subjects, ", ")
	}
	desc += fmt.Sprintf("\nDownloads: %d", b.DownloadCount)
	return desc
}
