// Package rutracker — search.go ports the Kotlin search-results parser:
//   - GetSearchPageUseCase.kt → ParseSearchPage (HTML for /search)
//
// Plus the upstream URL shape from RuTrackerInnerApiImpl.kt's `search()`:
//
//	GET /tracker.php?nm={query}&f={cats}&pn={author}&pid={authorId}
//	    &o={sortType}&s={sortOrder}&tm={period}&start={50*(page-1)}
//
// This is Lava-domain code: the eight rutracker query parameters and
// their value-string encodings (Date→"1", Title→"2", Downloaded→"4",
// Seeds→"10", Leeches→"11", Size→"7"; Ascending→"1"/Descending→"2";
// AllTime→"-1"/Today→"1"/LastThreeDays→"3"/LastWeek→"7"/LastTwoWeeks→
// "14"/LastMonth→"32") are part of the rutracker.org contract.
//
// Semantic divergence from forum.go: the forum scraper treats a row
// without a `.tor-*` status class as "Topic, not Torrent" (status nil
// → ForumTopicDtoTopic). The search scraper instead defaults a missing
// status to "Checking" and ALWAYS emits a torrent. This is faithful to
// `parseTorrentStatus(element) ?: TorrentStatusDto.Checking` in the
// Kotlin original — see `TestParseSearchPage_StatusDefaultsToChecking`.
package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"net/url"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// SearchOpts mirrors the eight Kotlin parameters of
// `GetSearchPageUseCase.invoke(searchQuery, categories, author, authorId,
// sortType, sortOrder, period, page)`. All fields are optional — nil
// means "omit from the upstream query string".
type SearchOpts struct {
	Query      *string
	Categories *string
	Author     *string
	AuthorID   *string
	SortType   *gen.SearchSortTypeDto
	SortOrder  *gen.SearchSortOrderDto
	Period     *gen.SearchPeriodDto
	Page       *int
}

// ParseSearchPage parses /tracker.php response HTML into the SearchPageDto.
//
// Selector walk (matches GetSearchPageUseCase.parseSearchPage):
//
//	doc.Find("#main_content_wrap > div.bottom_info > div.nav > p:nth-child(1)")
//	  navigation.Find("b:nth-child(1)") → currentPage (default 1)
//	  navigation.Find("b:nth-child(2)") → totalPages  (default 1)
//	doc.Find(".hl-tr").Each:
//	  row.Find(".t-title > a")[data-topic_id]   → id
//	  parseTorrentStatus(row) ?? "Checking"     → status
//	  row.Find(".t-title > a").text()           → title-with-tags
//	  getTitle / getTags                        → title, tags
//	  row.Find(".u-name > a")[?pid=]            → author.id
//	  row.Find(".u-name > a").text()            → author.name
//	  row.Find(".f")[?f=]                       → category.id
//	  row.Find(".f").text()                     → category.name
//	  row.Find(".tor-size")[data-ts_text]       → size (formatSize)
//	  row.Find("[style]")[data-ts_text]         → date (unix-ts int64)
//	  row.Find(".seedmed")                      → seeds (int32-clamped)
//	  row.Find(".leechmed")                     → leeches (int32-clamped)
func ParseSearchPage(html []byte) (*gen.SearchPageDto, error) {
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("rutracker: parse search html: %w", err)
	}
	navigation := doc.Find("#main_content_wrap > div.bottom_info > div.nav > p:nth-child(1)")
	currentPage := nodeInt(navigation.Find("b:nth-child(1)"), 1)
	totalPages := nodeInt(navigation.Find("b:nth-child(2)"), 1)

	torrents := make([]gen.ForumTopicDtoTorrent, 0)
	doc.Find(".hl-tr").Each(func(_ int, row *goquery.Selection) {
		titleAnchor := row.Find(".t-title > a").First()
		id, _ := titleAnchor.Attr("data-topic_id")

		// Search semantics: if no `.tor-*` class is present, default to
		// "Checking" rather than "this is a Topic". Always emit a torrent.
		status := parseTorrentStatus(row)
		if status == nil {
			v := string(gen.Checking)
			status = &v
		}

		titleWithTags := nodeText(titleAnchor)
		title := getTitle(titleWithTags)
		tags := getTags(titleWithTags)

		authorAnchor := row.Find(".u-name > a").First()
		authorIDOpt, _ := queryParamOrNull(authorAnchor, "pid")
		authorName := nodeText(authorAnchor)
		author := gen.AuthorDto{
			Id:   ptrOrNil(authorIDOpt),
			Name: authorName,
		}

		categoryAnchor := row.Find(".f").First()
		categoryID := queryParam(categoryAnchor, "f")
		categoryName := nodeText(categoryAnchor)
		category := gen.CategoryDto{
			Id:   ptrOrNil(categoryID),
			Name: categoryName,
		}

		sizeAttr := nodeAttr(row.Find(".tor-size").First(), "data-ts_text")
		// Defensive: an empty / non-numeric `data-ts_text` parses to 0
		// rather than tripping an error. Kotlin's `.toLong()` would throw
		// on bad input — but in practice rutracker always populates it.
		sizeBytes, _ := strconv.ParseInt(sizeAttr, 10, 64)
		size := formatSize(sizeBytes)

		// `[style]` selects any element with a non-empty style attribute;
		// rutracker uses inline `style="..."` on the row's date cell.
		// data-ts_text on that element holds a unix-timestamp digit
		// string. Missing or non-numeric → Date is nil (Kotlin's
		// `toLongOrNull()`).
		dateAttr := nodeAttr(row.Find("[style]").First(), "data-ts_text")
		var datePtr *int64
		if dateAttr != "" {
			if d, err := strconv.ParseInt(dateAttr, 10, 64); err == nil {
				datePtr = &d
			}
		}

		seedsVal, hasSeeds := nodeIntOrNil(row.Find(".seedmed"))
		leechesVal, hasLeeches := nodeIntOrNil(row.Find(".leechmed"))

		tt := gen.ForumTopicDtoTorrent{
			Author:   &author,
			Category: &category,
			Date:     datePtr,
			Id:       id,
			Size:     ptrOrNil(size),
			Status:   (*gen.TorrentStatusDto)(status),
			Tags:     ptrOrNil(strings.TrimSpace(tags)),
			Title:    title,
			Type:     "Torrent",
		}
		// int32 clamp: same pattern as forum.go. The OpenAPI contract
		// types Seeds/Leeches as int32 but `nodeIntOrNil` returns host
		// int (64-bit on modern targets). Out-of-range counts surface as
		// ABSENT rather than silently wrapping to a negative int32.
		if hasSeeds && seedsVal >= math.MinInt32 && seedsVal <= math.MaxInt32 {
			v := int32(seedsVal)
			tt.Seeds = &v
		}
		if hasLeeches && leechesVal >= math.MinInt32 && leechesVal <= math.MaxInt32 {
			v := int32(leechesVal)
			tt.Leeches = &v
		}
		torrents = append(torrents, tt)
	})

	return &gen.SearchPageDto{
		Page:     int32(currentPage),
		Pages:    int32(totalPages),
		Torrents: torrents,
	}, nil
}

// searchSortTypeValue maps the SearchSortTypeDto enum to its rutracker
// `o=` query-string value. Mirrors SearchSortTypeDto.kt's `value` field.
func searchSortTypeValue(t gen.SearchSortTypeDto) string {
	switch t {
	case gen.SearchSortTypeDtoDate:
		return "1"
	case gen.SearchSortTypeDtoTitle:
		return "2"
	case gen.SearchSortTypeDtoDownloaded:
		return "4"
	case gen.SearchSortTypeDtoSize:
		return "7"
	case gen.SearchSortTypeDtoSeeds:
		return "10"
	case gen.SearchSortTypeDtoLeeches:
		return "11"
	default:
		return ""
	}
}

// searchSortOrderValue maps SearchSortOrderDto to its rutracker `s=` value.
// Mirrors SearchSortOrderDto.kt.
func searchSortOrderValue(o gen.SearchSortOrderDto) string {
	switch o {
	case gen.Ascending:
		return "1"
	case gen.Descending:
		return "2"
	default:
		return ""
	}
}

// searchPeriodValue maps SearchPeriodDto to its rutracker `tm=` value.
// Mirrors SearchPeriodDto.kt.
func searchPeriodValue(p gen.SearchPeriodDto) string {
	switch p {
	case gen.AllTime:
		return "-1"
	case gen.Today:
		return "1"
	case gen.LastThreeDays:
		return "3"
	case gen.LastWeek:
		return "7"
	case gen.LastTwoWeeks:
		return "14"
	case gen.LastMonth:
		return "32"
	default:
		return ""
	}
}

// GetSearchPage fetches /tracker.php?<params> and parses the result. nil
// fields in `opts` are omitted from the upstream query. `page` is 1-based;
// nil/<=1 omits the start parameter (rutracker pages are 50 results each,
// matching RuTrackerInnerApiImpl.kt's `search(...)`).
func (c *Client) GetSearchPage(ctx context.Context, opts SearchOpts, cookie string) (*gen.SearchPageDto, error) {
	q := url.Values{}
	if opts.Query != nil {
		q.Set("nm", *opts.Query)
	}
	if opts.Categories != nil {
		q.Set("f", *opts.Categories)
	}
	if opts.Author != nil {
		q.Set("pn", *opts.Author)
	}
	if opts.AuthorID != nil {
		q.Set("pid", *opts.AuthorID)
	}
	if opts.SortType != nil {
		if v := searchSortTypeValue(*opts.SortType); v != "" {
			q.Set("o", v)
		}
	}
	if opts.SortOrder != nil {
		if v := searchSortOrderValue(*opts.SortOrder); v != "" {
			q.Set("s", v)
		}
	}
	if opts.Period != nil {
		if v := searchPeriodValue(*opts.Period); v != "" {
			q.Set("tm", v)
		}
	}
	if opts.Page != nil && *opts.Page > 1 {
		q.Set("start", strconv.Itoa(50*(*opts.Page-1)))
	}

	path := "/tracker.php"
	if encoded := q.Encode(); encoded != "" {
		path = path + "?" + encoded
	}
	body, status, err := c.Fetch(ctx, path, cookie)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s → %d", path, status)
	}
	return ParseSearchPage(body)
}
