// Package jackett is the Lava-domain client for a Jackett sidecar fronted
// by lava-api-go over Torznab. It parses Torznab/newznab RSS responses into
// clean Go structs and builds Torznab request URLs.
//
// Per the Decoupled Reusable rule, the struct field set is borrowed from the
// MIT-licensed github.com/cardigann/cardigann/torznab package; the decoder is
// a small first-party encoding/xml implementation so lava-api-go does not
// inherit an unmaintained runtime dependency. If a second vasic-digital
// project wants a generic Torznab client this is a candidate for extraction
// to a submodule.
//
// Per §6.R (no-hardcoding) the Jackett base URL and api_key are NOT literals
// here — they are injected via Config (read from the gitignored host volume /
// env at runtime). Jackett's api_key is a §6.H secret: server-side only,
// never shipped to the device.
package jackett

import (
	"encoding/xml"
	"fmt"
	"strconv"
	"strings"
)

// Torznab attribute names that appear inside <torznab:attr name=... value=...>.
// These are the attribute keys we extract; the list is not exhaustive of what
// Torznab can emit (downloadvolumefactor, peers, category, … exist too) but
// covers the download-confirmation fields the worklog cares about.
const (
	attrSeeders   = "seeders"
	attrSize      = "size"
	attrMagnetURL = "magneturl"
	attrInfohash  = "infohash"
)

// Enclosure type strings (verbatim from the Torznab torrent-support spec).
const (
	// EnclosureTypeTorrent marks a .torrent download enclosure.
	EnclosureTypeTorrent = "application/x-bittorrent"
	// EnclosureTypeMagnet marks a magnet enclosure.
	EnclosureTypeMagnet = "application/x-bittorrent;x-scheme-handler/magnet"
)

// rss is the top-level Torznab RSS document. Torznab is RSS 2.0 with an
// extension namespace xmlns:torznab="http://torznab.com/schemas/2015/feed".
type rss struct {
	XMLName xml.Name   `xml:"rss"`
	Channel rssChannel `xml:"channel"`
}

type rssChannel struct {
	Items []rssItem `xml:"item"`
}

// rssItem is the wire shape of a single <item> in a Torznab results feed.
type rssItem struct {
	Title     string       `xml:"title"`
	GUID      string       `xml:"guid"`
	Enclosure rssEnclosure `xml:"enclosure"`
	// Attrs collects every <torznab:attr>. encoding/xml matches on local
	// name "attr" regardless of the "torznab" namespace prefix, so this also
	// catches the equivalent newznab:attr spelling some indexers emit.
	Attrs []rssAttr `xml:"attr"`
}

type rssEnclosure struct {
	URL    string `xml:"url,attr"`
	Length int64  `xml:"length,attr"`
	Type   string `xml:"type,attr"`
}

type rssAttr struct {
	Name  string `xml:"name,attr"`
	Value string `xml:"value,attr"`
}

// Result is the clean, parsed representation of one Torznab <item>.
type Result struct {
	Title string
	GUID  string

	// DownloadURL is the <enclosure url>. For a .torrent it is an HTTP(S)
	// link (often a Jackett /dl/ proxy link); for a magnet enclosure it is
	// the magnet URI itself.
	DownloadURL string
	// EnclosureType is the raw enclosure type string, one of
	// EnclosureTypeTorrent / EnclosureTypeMagnet (or empty if absent).
	EnclosureType string

	// Seeders is -1 when the attr is absent or unparseable, distinguishing
	// "unknown" from a genuine 0 seeders.
	Seeders int
	// Size is the size in bytes. It prefers the <torznab:attr name="size">
	// value, falling back to the <enclosure length> when the attr is absent.
	Size int64
	// MagnetURL is the <torznab:attr name="magneturl"> value when present,
	// OR the enclosure URL when the enclosure is a magnet. Empty otherwise.
	MagnetURL string
	// Infohash is the <torznab:attr name="infohash"> value, lower-cased.
	Infohash string
}

// IsMagnetEnclosure reports whether the item's enclosure is a magnet link.
func (r Result) IsMagnetEnclosure() bool {
	return r.EnclosureType == EnclosureTypeMagnet || strings.HasPrefix(r.DownloadURL, "magnet:")
}

// ParseResults decodes a Torznab/newznab RSS results feed into a slice of
// Result. It returns an error only on XML that cannot be decoded at all; a
// well-formed feed with zero <item> elements yields an empty slice and no
// error.
func ParseResults(body []byte) ([]Result, error) {
	var doc rss
	if err := xml.Unmarshal(body, &doc); err != nil {
		return nil, fmt.Errorf("jackett: torznab xml decode: %w", err)
	}
	out := make([]Result, 0, len(doc.Channel.Items))
	for _, it := range doc.Channel.Items {
		out = append(out, itemToResult(it))
	}
	return out, nil
}

func itemToResult(it rssItem) Result {
	r := Result{
		Title:         it.Title,
		GUID:          it.GUID,
		DownloadURL:   it.Enclosure.URL,
		EnclosureType: it.Enclosure.Type,
		Seeders:       -1,
	}

	// Fold the torznab:attr set into the result.
	for _, a := range it.Attrs {
		switch strings.ToLower(a.Name) {
		case attrSeeders:
			if n, err := strconv.Atoi(strings.TrimSpace(a.Value)); err == nil {
				r.Seeders = n
			}
		case attrSize:
			if n, err := strconv.ParseInt(strings.TrimSpace(a.Value), 10, 64); err == nil {
				r.Size = n
			}
		case attrMagnetURL:
			r.MagnetURL = strings.TrimSpace(a.Value)
		case attrInfohash:
			r.Infohash = strings.ToLower(strings.TrimSpace(a.Value))
		}
	}

	// Size fallback: when no size attr was present, use the enclosure length.
	if r.Size == 0 && it.Enclosure.Length > 0 {
		r.Size = it.Enclosure.Length
	}

	// Magnet fallback: when the enclosure itself is a magnet but no explicit
	// magneturl attr was provided, the enclosure URL is the magnet.
	if r.MagnetURL == "" && r.IsMagnetEnclosure() && strings.HasPrefix(r.DownloadURL, "magnet:") {
		r.MagnetURL = r.DownloadURL
	}

	return r
}
