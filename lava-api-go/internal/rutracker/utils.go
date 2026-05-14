// Package rutracker — utils.go contains the small selector / text helpers
// ported 1:1 from core/network/rutracker/.../domain/Utils.kt. Only the
// subset actually used by the forum scraper (Task 6.2) is ported here per
// YAGNI; subsequent tasks (6.3-6.8) extend this file rather than redefine.
//
// Naming follows Go convention: Kotlin's `Element.toStr()` becomes
// `nodeText(s)`, `Element.toInt()` becomes `nodeInt(s, default)`,
// `Element.url()` becomes `nodeAttr(s, "href")`, `queryParam`/
// `queryParamOrNull` keep their names. Each helper takes a
// `*goquery.Selection` so callers can chain selectors as in the Kotlin
// original.
package rutracker

import (
	"fmt"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

// nodeText returns the trimmed text of the given selection.
//
// Equivalent to Kotlin's `Element?.toStr()` / `Elements?.toStr()` —
// both delegate to Jsoup. For a SINGLE-element selection Jsoup's
// `Element.text()` is the obvious behaviour. For a MULTI-element
// selection Jsoup's `Elements.text()` joins the per-element text with
// a SINGLE SPACE separator; goquery's `Selection.Text()` is NOT
// equivalent — it concatenates the text of every matched element with
// no separator at all. To preserve Kotlin parity we walk the matched
// elements ourselves and join with " " before collapsing whitespace.
// This matters in production: a row carrying two `.seedmed` siblings
// ("12" and "34") MUST yield "12 34" (then nil through toIntOrNull),
// not "1234" (a wrong, plausible-looking 1,234).
//
// When the selection is empty the empty string is returned, never an
// error.
func nodeText(s *goquery.Selection) string {
	if s == nil || s.Length() == 0 {
		return ""
	}
	parts := make([]string, 0, s.Length())
	s.Each(func(_ int, e *goquery.Selection) {
		parts = append(parts, e.Text())
	})
	return collapseWhitespace(strings.Join(parts, " "))
}

// nodeInt parses the trimmed text of the selection as a base-10 integer
// and returns `def` on any failure (empty selection, non-numeric text).
// Equivalent to Kotlin's `Element?.toInt(default)`.
func nodeInt(s *goquery.Selection, def int) int {
	v, ok := nodeIntOrNil(s)
	if !ok {
		return def
	}
	return v
}

// nodeIntOrNil parses the trimmed text of the selection as a base-10
// integer. Returns (value, true) on success, (0, false) otherwise.
// Equivalent to Kotlin's `Elements?.toIntOrNull()`. Reads through
// nodeText so the multi-match join semantics apply uniformly — a
// two-sibling selection of "12" and "34" yields "12 34", which fails
// strconv.Atoi and returns (0, false).
func nodeIntOrNil(s *goquery.Selection) (int, bool) {
	if s == nil || s.Length() == 0 {
		return 0, false
	}
	t := nodeText(s)
	if t == "" {
		return 0, false
	}
	v, err := strconv.Atoi(t)
	if err != nil {
		// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
		return 0, false
	}
	return v, true
}

// nodeAttr returns the named attribute value of the FIRST matched element,
// or the empty string if absent. Equivalent to Kotlin's
// `Element?.attr("href")`.
func nodeAttr(s *goquery.Selection, name string) string {
	if s == nil || s.Length() == 0 {
		return ""
	}
	v, _ := s.Attr(name)
	return v
}

// queryParamOrNull returns the FIRST value of the named query parameter
// from the selection's `href` attribute. Returns "", false when the
// selection has no href, the href is unparseable, or the parameter is
// absent. Equivalent to Kotlin's `Element?.queryParamOrNull(key)`.
func queryParamOrNull(s *goquery.Selection, key string) (string, bool) {
	href := nodeAttr(s, "href")
	if href == "" {
		return "", false
	}
	// Jsoup's `urlOrNull` returns the raw href; the Kotlin parser then
	// runs URI.create on it. rutracker hrefs commonly look like
	// "viewforum.php?f=123" — a relative reference that net/url parses
	// fine without a base.
	u, err := url.Parse(href)
	if err != nil {
		// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
		return "", false
	}
	q := u.Query()
	if !q.Has(key) {
		return "", false
	}
	v := q.Get(key)
	if v == "" {
		return "", false
	}
	return v, true
}

// queryParam is queryParamOrNull but returns "" on miss; callers that
// require presence check for empty.
func queryParam(s *goquery.Selection, key string) string {
	v, _ := queryParamOrNull(s, key)
	return v
}

// getTitle strips `[tag]` runs and trims, mirroring Utils.kt's getTitle.
// rutracker forum-row titles look like "[Genre] Movie Name [1080p]" and
// the human-visible title is "Movie Name".
func getTitle(titleWithTags string) string {
	out := tagBracketRe.ReplaceAllString(titleWithTags, "")
	out = strings.ReplaceAll(out, "  ", " ")
	out = strings.ReplaceAll(out, "[", "")
	out = strings.ReplaceAll(out, "]", "")
	return strings.TrimSpace(out)
}

// getTags concatenates every `[...]` run found in the title, separated
// by single spaces (the Kotlin StringBuilder appends `(group, ' ')` on
// each match). Mirrors Utils.kt's getTags.
func getTags(titleWithTags string) string {
	matches := tagBracketRe.FindAllString(titleWithTags, -1)
	var b strings.Builder
	for _, m := range matches {
		b.WriteString(m)
		b.WriteByte(' ')
	}
	return b.String()
}

// parseTorrentStatus mirrors ParseTorrentStatusUseCase.kt — it walks the
// row looking for one of eight status CSS classes and returns the first
// match, or nil if none are present (which the caller maps to "this row
// is a Topic, not a Torrent").
//
// Order matches the Kotlin `when` block exactly. The first match wins.
func parseTorrentStatus(s *goquery.Selection) *string {
	if s == nil {
		return nil
	}
	for _, c := range torrentStatusClasses {
		if s.Find("."+c.cls).Length() > 0 {
			v := c.status
			return &v
		}
	}
	return nil
}

// torrentStatusClasses is the priority-ordered list of CSS class -> status
// enum value, ported from ParseTorrentStatusUseCase.kt. Keep this list in
// sync with internal/gen/server/api.gen.go's TorrentStatusDto enum.
var torrentStatusClasses = []struct {
	cls    string
	status string
}{
	{"tor-dup", "Duplicate"},
	{"tor-not-approved", "NotApproved"},
	{"tor-checking", "Checking"},
	{"tor-approved", "Approved"},
	{"tor-need-edit", "NeedEdit"},
	{"tor-closed", "Closed"},
	{"tor-no-desc", "NoDescription"},
	{"tor-consumed", "Consumed"},
}

// tagBracketRe matches a `[...]` run, non-greedy. Mirrors the Kotlin
// regex `(\[[^]]*])`.
var tagBracketRe = regexp.MustCompile(`\[[^]]*]`)

// formatSize ports Utils.kt's `formatSize(sizeBytes: Long): String`. Used
// by the search scraper (Task 6.3) to render the .tor-size data-ts_text
// byte count as a human-readable "1.5 GB"-style string.
//
// Locale note: Kotlin's `String.format("%.1f %sB", …)` uses the JVM
// default locale, which in production runs in en_US/C inside the
// container. Go's `fmt.Sprintf` is locale-independent and always emits
// `.` as the decimal separator — TestFormatSize_Cases pins this so a
// stray `,` decimal separator from a locale change can never ship.
//
// Boundary: `< 1024` returns "<n> B" (no decimals). Otherwise the
// exponent is `floor(log_1024(sizeBytes))`, capped implicitly at 6
// (KMGTPE). Negative or zero inputs go through the "< 1024" branch as
// in the Kotlin original.
func formatSize(sizeBytes int64) string {
	if sizeBytes < 1024 {
		return fmt.Sprintf("%d B", sizeBytes)
	}
	exp := int(math.Log(float64(sizeBytes)) / math.Log(1024.0))
	const prefixes = "KMGTPE"
	if exp < 1 {
		exp = 1
	}
	if exp > len(prefixes) {
		exp = len(prefixes)
	}
	pre := string(prefixes[exp-1])
	value := float64(sizeBytes) / math.Pow(1024.0, float64(exp))
	return fmt.Sprintf("%.1f %sB", value, pre)
}

// collapseWhitespace mimics Jsoup's `Element.text()` whitespace policy:
// any run of ASCII whitespace (incl. NBSP) collapses to a single space,
// leading/trailing whitespace is trimmed.
func collapseWhitespace(s string) string {
	// Replace NBSP (U+00A0) with regular space first — Jsoup treats it
	// as whitespace; Go's unicode.IsSpace also treats it as space.
	s = strings.ReplaceAll(s, " ", " ")
	var b strings.Builder
	b.Grow(len(s))
	prevSpace := true // suppresses leading space
	for _, r := range s {
		if r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == '\f' || r == '\v' {
			if !prevSpace {
				b.WriteByte(' ')
				prevSpace = true
			}
			continue
		}
		b.WriteRune(r)
		prevSpace = false
	}
	out := b.String()
	return strings.TrimRight(out, " ")
}
