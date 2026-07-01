package jackett

import (
	"context"
	"encoding/json"
	"fmt"
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
// This is a Jackett MANAGEMENT endpoint: when the Jackett dashboard is password
// protected, apikey alone is NOT sufficient — Jackett answers with an HTTP 302
// redirect to /UI/Login. The request therefore routes through getManagement,
// which transparently acquires + reuses the dashboard session cookie (see
// Client.login). Torznab feeds (Search / caps / Download) are unaffected — they
// authenticate by apikey and never touch the cookie path.
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

	body, err := c.getManagement(ctx, reqURL)
	if err != nil {
		return nil, fmt.Errorf("jackett: list indexers: %w", err)
	}

	var indexers []IndexerInfo
	if err := json.Unmarshal(body, &indexers); err != nil {
		return nil, fmt.Errorf("jackett: list indexers decode: %w", err)
	}
	return indexers, nil
}
