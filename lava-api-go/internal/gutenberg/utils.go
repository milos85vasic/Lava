package gutenberg

import (
	"path"
	"sort"
	"strings"
)

func pickBestFormatURL(formats map[string]string) string {
	if formats == nil {
		return ""
	}
	// LVA-022: Gutendex emits text formats WITH a charset suffix
	// ("text/plain; charset=utf-8", "text/plain; charset=us-ascii"), so match by
	// MIME base prefix, not exact key — otherwise the preferred ordering is
	// silently skipped and selection falls through to an arbitrary sorted key.
	preferred := []string{
		"application/epub+zip",
		"text/plain",
		"text/html",
		"application/pdf",
	}
	for _, base := range preferred {
		if url := matchFormatByPrefix(formats, base); url != "" {
			return url
		}
	}
	keys := make([]string, 0, len(formats))
	for k := range formats {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if url := formats[k]; url != "" {
			return url
		}
	}
	return ""
}

func bestFormatName(formats map[string]string) string {
	if formats == nil {
		return ""
	}
	// LVA-022: prefix-match so charset-suffixed Gutendex keys
	// ("text/plain; charset=us-ascii") still produce a non-blank format label.
	switch {
	case matchFormatByPrefix(formats, "application/epub+zip") != "":
		return "EPUB"
	case matchFormatByPrefix(formats, "text/plain") != "":
		return "Text"
	case matchFormatByPrefix(formats, "text/html") != "":
		return "HTML"
	}
	return ""
}

// matchFormatByPrefix returns the first non-empty URL whose MIME key equals
// base or starts with "base;" (a charset-suffixed variant), preferring an
// exact match. Gutendex routinely suffixes text MIME types with "; charset=…".
func matchFormatByPrefix(formats map[string]string, base string) string {
	if url, ok := formats[base]; ok && url != "" {
		return url
	}
	for k, url := range formats {
		if url != "" && strings.HasPrefix(k, base+";") {
			return url
		}
	}
	return ""
}

func primarySubject(subjects []string) string {
	if len(subjects) == 0 {
		return ""
	}
	return subjects[0]
}

func joinStrings(ss []string, sep string) string {
	return strings.Join(ss, sep)
}

func filenameFromURL(raw string) string {
	return path.Base(raw)
}
