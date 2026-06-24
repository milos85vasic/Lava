# §6.O Closure Log — TopicPageDto MissingFieldException (2026-06-24)

**State:** PARTIAL — NOT fully resolved in 1.3.11-1073. Honest downgrade (§6.J self-audit).

**Crashlytics issue ID:** `8cde0ac208b3b928fdd125fd458ece9f` (client RELEASE, `digital.vasic.lava.client`)
**Type:** NON_FATAL. firstSeen=lastSeen 1.3.9. 1 event / 1 user in the 30-day window.

## What the issue ACTUALLY is (corrected after reading the full Crashlytics subtitle)
The exception message is:
> Fields **[id, title, author, category, torrentData, commentsPage]** are required for type
> `lava.network.dto.topic.TopicPageDto`, but they were missing at path: $

kotlinx.serialization lists the **missing** fields — so the Internet Archive `WPO-*` crawl-topic
`/topic2/<id>` response omits **ALL SIX** of TopicPageDto's fields (it is an archived web page, not a
torrent topic — none of id/title/author/category/torrentData/commentsPage is present). Note: `author`,
`category`, `torrentData` are nullable but have **no default**, so kotlinx still requires the KEYS present.

## What was done in 1073 (necessary but NOT sufficient)
`TopicPageDto.commentsPage` was given a default (`= TopicPageCommentsDto(page=1, pages=1, posts=emptyList())`).
This removes `commentsPage` from the required set — but the other FIVE (id, title, author, category,
torrentData) are still required, so the IA crawl-topic payload **still throws MissingFieldException**
(now listing 5 instead of 6). **The 1073 change does not, by itself, stop the crash for the reported case.**
It is retained because it is a valid improvement for any topic that supplies the other five but omits comments.

## Why NOT a rushed blanket fix
Defaulting `id`/`title` to `""` + `author`/`category`/`torrentData` to `null` would stop the throw, but
risks **masking real malformed-topic bugs** for normal rutracker/rutor topics (a normal topic genuinely
missing its `id` is an error, not a sparse-IA case) — a §6.J concern. The correct fix needs investigation:
(a) lava-api-go's IA provider populating the topic-page shape for crawl items, OR (b) a dedicated
sparse/crawl-topic DTO + the topic screen handling it, OR (c) the client not requesting `/topic2` for IA
crawl topics. This is tracked as a FOLLOW-UP.

## Impact + decision
NON_FATAL, 1 event, niche (Internet-Archive crawl topics only). It does NOT crash the app (a recorded
non-fatal; the topic page fails to render for those items). It is **not a regression** — IA crawl topics
were already affected on 1.3.9. It therefore does NOT block the 1.3.11-1073 distribute (which fixes the
P0 credentials FATAL + P1 nesting FATAL + the search timeout). The proper fix ships in a later build.

## §6.O.2 Challenge + §6.O.5
The WPO-* topic-render Challenge is OWED with the proper fix. The Crashlytics issue stays OPEN; operator
does not close-mark until the full fix lands + is verified on-device.
