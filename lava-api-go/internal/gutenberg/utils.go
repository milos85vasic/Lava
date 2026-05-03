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
	preferred := []string{
		"application/epub+zip",
		"text/plain",
		"text/html",
		"application/pdf",
		"text/plain; charset=utf-8",
		"text/html; charset=utf-8",
	}
	for _, mime := range preferred {
		if url, ok := formats[mime]; ok && url != "" {
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
	if _, ok := formats["application/epub+zip"]; ok {
		return "EPUB"
	}
	if _, ok := formats["text/plain"]; ok {
		return "Text"
	}
	if _, ok := formats["text/html"]; ok {
		return "HTML"
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
