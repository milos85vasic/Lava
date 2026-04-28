// Package rutracker — topic_predicates.go ports the three Russian-error-
// sentence predicates from core/network/rutracker/.../domain/Utils.kt that
// gate the topic-page parsers (Get{Topic,TopicPage,CommentsPage}UseCase).
//
// These predicates run BEFORE goquery parsing — they're cheap byte scans
// against the raw upstream HTML and short-circuit ErrNotFound / ErrForbidden
// without paying the parse cost. The exact Russian sentences come from
// rutracker.org's error pages (Lava domain knowledge); changing them is a
// rutracker-contract change, not a refactor.
package rutracker

import "bytes"

// isTopicExists ports Utils.kt:66-70. Three distinct rutracker error
// strings indicate a topic that does not exist (deleted, in trash, or
// missing topic_id query parameter).
func isTopicExists(html []byte) bool {
	return !bytes.Contains(html, []byte("Тема не найдена")) &&
		!bytes.Contains(html, []byte("Тема находится в мусорке")) &&
		!bytes.Contains(html, []byte("Ошибочный запрос: не указан topic_id"))
}

// isBlockedForRegion ports Utils.kt:72-74. rutracker geo-blocks a subset
// of distributions; the upstream returns a page carrying this sentence
// rather than an HTTP-level error.
func isBlockedForRegion(html []byte) bool {
	return bytes.Contains(html, []byte("Извините, раздача недоступна для вашего региона"))
}

// isTopicModerated ports Utils.kt:76-78. A torrent that is queued for
// moderator review surfaces this sentence in the topic body.
func isTopicModerated(html []byte) bool {
	return bytes.Contains(html, []byte("Раздача ожидает проверки модератором"))
}
