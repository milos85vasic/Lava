package archiveorg

import (
	"context"
	"encoding/json"
	"fmt"
)

// metadataResponse is the JSON envelope returned by /metadata/{identifier}.
type metadataResponse struct {
	Metadata struct {
		Title       flexString `json:"title"`
		Creator     flexString `json:"creator"`
		Description flexString `json:"description"`
		Date        flexString `json:"date"`
		Mediatype   flexString `json:"mediatype"`
	} `json:"metadata"`
	Files []metadataFile `json:"files"`
}

// metadataFile is a file entry inside a metadata response.
type metadataFile struct {
	Name   string  `json:"name"`
	Size   *string `json:"size"`
	Format *string `json:"format"`
	Source *string `json:"source"`
}

// TopicResult is the domain type returned by Client.Topic.
type TopicResult struct {
	ID          string      `json:"id"`
	Title       string      `json:"title"`
	Creator     string      `json:"creator,omitempty"`
	Description string      `json:"description,omitempty"`
	Date        string      `json:"date,omitempty"`
	MediaType   string      `json:"mediaType,omitempty"`
	Files       []TopicFile `json:"files"`
}

// TopicFile is a file listed inside a topic.
type TopicFile struct {
	Name   string `json:"name"`
	Size   string `json:"size,omitempty"`
	Format string `json:"format,omitempty"`
	Source string `json:"source,omitempty"`
}

// Topic fetches metadata for a single archive.org item.
func (c *Client) Topic(ctx context.Context, identifier string) (*TopicResult, error) {
	body, err := c.get(ctx, "/metadata/"+identifier, nil)
	if err != nil {
		return nil, err
	}

	var mr metadataResponse
	if err := json.Unmarshal(body, &mr); err != nil {
		return nil, fmt.Errorf("archiveorg: unmarshal metadata: %w", err)
	}

	result := TopicResult{
		ID:          identifier,
		Title:       string(mr.Metadata.Title),
		Creator:     string(mr.Metadata.Creator),
		Description: string(mr.Metadata.Description),
		Date:        string(mr.Metadata.Date),
		MediaType:   string(mr.Metadata.Mediatype),
	}

	files := make([]TopicFile, 0, len(mr.Files))
	for _, f := range mr.Files {
		tf := TopicFile{Name: f.Name}
		if f.Size != nil {
			tf.Size = *f.Size
		}
		if f.Format != nil {
			tf.Format = *f.Format
		}
		if f.Source != nil {
			tf.Source = *f.Source
		}
		files = append(files, tf)
	}
	result.Files = files

	return &result, nil
}
