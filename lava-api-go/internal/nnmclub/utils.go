package nnmclub

import (
	"net/url"
	"strconv"
	"strings"
)

// extractTopicID pulls the numeric id from a viewtopic.php?t=12345 href.
func extractTopicID(href string) string {
	u, err := url.Parse(href)
	if err != nil {
		// Fallback: manual split for relative URLs like "viewtopic.php?t=12345"
		if idx := strings.Index(href, "t="); idx != -1 {
			return strings.TrimSpace(href[idx+2:])
		}
		return ""
	}
	return strings.TrimSpace(u.Query().Get("t"))
}

// extractQueryParam returns a query parameter value from a relative or absolute URL string.
func extractQueryParam(href, key string) string {
	u, err := url.Parse(href)
	if err != nil {
		return ""
	}
	return u.Query().Get(key)
}

// parseIntText strips spaces and parses an int, returning 0 on failure.
func parseIntText(s string) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	v, _ := strconv.Atoi(s)
	return v
}
