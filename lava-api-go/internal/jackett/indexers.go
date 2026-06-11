package jackett

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

// indexersPath is the Jackett configured-indexers endpoint. Like
// torznabResultsPath this is a protocol path segment (not a deployment address),
// so it is a package constant; the base URL + apikey come from Config (§6.R).
const indexersPath = "/api/v2.0/indexers"

// IndexerCap is a single Torznab category capability advertised by an indexer
// (e.g. {ID:"2000", Name:"Movies"}). Surfaced informationally so the catalogue
// can later expose per-indexer categories (spec §8 leaves the category UI out
// of scope; the data is parsed now so the wire shape is stable).
type IndexerCap struct {
	ID   string `json:"ID"`
	Name string `json:"Name"`
}

// IndexerInfo is one configured Jackett indexer. The ID becomes a provider id
// in the registry (each indexer = a provider, spec §4.1); Name is the
// human-readable display name; Caps are the indexer's advertised categories.
type IndexerInfo struct {
	ID   string       `json:"id"`
	Name string       `json:"name"`
	Caps []IndexerCap `json:"caps"`
}

// ListIndexers fetches the set of CONFIGURED Jackett indexers via
//
//	GET <base>/api/v2.0/indexers?configured=true&apikey=<key>
//
// Each configured indexer is exposed as its own discoverable provider in the
// /v1/providers catalogue (operator decision 2026-06-11 §3.2). The apikey is a
// §6.H server-side secret carried inside Config — never a literal, never shipped
// to the device.
//
// An enumeration failure returns an error so the caller (startup wiring) can
// RecordNonFatal + continue serving native providers (spec §5) rather than fail
// hard.
func (c *Client) ListIndexers(ctx context.Context) ([]IndexerInfo, error) {
	v := url.Values{}
	v.Set("configured", "true")
	v.Set("apikey", c.cfg.APIKey)
	reqURL := c.cfg.BaseURL + indexersPath + "?" + v.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("jackett: list indexers request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("jackett: list indexers status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var indexers []IndexerInfo
	if err := json.Unmarshal(body, &indexers); err != nil {
		return nil, fmt.Errorf("jackett: list indexers decode: %w", err)
	}
	return indexers, nil
}
