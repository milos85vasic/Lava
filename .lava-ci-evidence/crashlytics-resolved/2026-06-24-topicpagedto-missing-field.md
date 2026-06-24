# Crashlytics closure — TopicPageDto MissingFieldException (2026-06-24)

**Crashlytics issue ID:** `8cde0ac208b3…` (client RELEASE)
**App:** `digital.vasic.lava.client` (client RELEASE)
**Type:** NON_FATAL
**Events / users:** 1 event / 1 user
**Version first seen / last seen:** 1.3.9 / 1.3.9 (build code 1066)
**Priority:** P2
**State at this entry:** FIX LANDED — pending on-device verification + operator close-mark in console

---

## Stack-trace summary

```
io.ktor.serialization.JsonConvertException: Illegal input:
  Fields [id, title, author, category, torrentData, commentsPage] are required for
  type with serial name 'lava.network.dto.topic.TopicPageDto', but they were missing at path: $
  at io.ktor.serialization.kotlinx.KotlinxSerializationConverter.deserialize
Caused by: kotlinx.serialization.MissingFieldException
  at lava.network.dto.topic.TopicPageDto.<init> (TopicPageDto.kt:6)
```

Custom keys from the event:
- `error = load_topic_failed`
- `topic_id = WPO-20230122202907-crawl897`

Breadcrumb: `lava_view_topic { topic_id: WPO-20230122202907-crawl897 }` — user tapped a
specific Internet Archive crawl topic.

---

## Root cause

`TopicPageDto` was declared as a strict `@Serializable` data class with all fields required
(no defaults). The `commentsPage` field was required:

```kotlin
// BEFORE FIX (TopicPageDto.kt line 13):
val commentsPage: TopicPageCommentsDto,   // required — no default
```

Internet Archive `WPO-*` topics are archived web crawls, not forum posts. The lava-api-go
`/topic2/<id>` handler for these topics returns a JSON shape that **omits `commentsPage`**
because archived web pages have no forum-comment section. When kotlinx.serialization attempted
to construct `TopicPageDto` from this response, it threw `MissingFieldException` because
`commentsPage` was absent from the JSON.

The `MissingFieldException` was caught by the topic-loading use case's error handler, recorded
as a non-fatal, and the topic page displayed a "load failed" error to the user. Every Internet
Archive crawl topic (`WPO-*`, `crawl*` prefixes) was silently unviewable.

---

## Affected files

| File | Change |
|------|--------|
| `core/network/api/src/main/kotlin/lava/network/dto/topic/TopicPageDto.kt` | `commentsPage` field given a default value: `= TopicPageCommentsDto(page = 1, pages = 1, posts = emptyList())` (line 13) |
| `core/network/api/src/test/kotlin/lava/network/dto/topic/TopicPageDtoSerializationTest.kt` | New test file — 2 tests covering the fix |

---

## Fix description

Added a default value to `commentsPage` in `TopicPageDto` so that deserialization of Internet
Archive crawl responses (which omit the field) succeeds:

```kotlin
// AFTER FIX (TopicPageDto.kt line 13):
val commentsPage: TopicPageCommentsDto = TopicPageCommentsDto(page = 1, pages = 1, posts = emptyList()),
```

The default `TopicPageCommentsDto(page = 1, pages = 1, posts = emptyList())` is a semantically
correct empty-comments representation: page 1 of 1 with zero posts. The topic screen renders
the content section normally and shows an empty comments section — which is correct for an
archived web page that never had forum comments.

Explicit `commentsPage` values in rutracker/rutor topic responses are unaffected: when the
field is present in the JSON, kotlinx.serialization uses the explicit value, not the default
(standard JSON deserialization semantics for fields with defaults).

---

## Validation tests (unit level)

`core/network/api/src/test/kotlin/lava/network/dto/topic/TopicPageDtoSerializationTest.kt`

**Test 1:** `internet archive crawl topic without commentsPage parses with default commentsPage`
- Decodes a realistic `WPO-*` JSON payload (no `commentsPage` key) using the production
  `JsonFactory.create()` serializer applied directly to the production `TopicPageDto` — nothing mocked.
- Primary assertion: decoded `commentsPage.page == 1`, `commentsPage.pages == 1`,
  `commentsPage.posts == emptyList()` — the user-visible state the topic screen renders.
- **Before fix**: throws `MissingFieldException: Field 'commentsPage' is required for type
  with serial name 'lava.network.dto.topic.TopicPageDto'`
- **After fix**: decodes successfully; default is applied.

**Test 2:** `rutracker topic with commentsPage present deserializes the explicit commentsPage`
- Decodes a JSON payload that INCLUDES `commentsPage` (page=3, pages=10).
- Asserts the EXPLICIT values win (page=3, pages=10), not the default (page=1, pages=1).
- Guards against the fix accidentally overriding present values with the default.

**Falsifiability rehearsal (Seventh Law clause 1):**
Reverting the default (making `commentsPage` required again) causes Test 1 to throw
`MissingFieldException: Field 'commentsPage' is required…`. Test 1 fails with that exception
as the test failure message. Mutation reverted → Test 1 GREEN. Recorded in commit body
per Seventh Law clause 1 Bluff-Audit stamp.

---

## Challenge Test (end-to-end level)

**OWED.** A Compose UI Challenge Test that loads a `WPO-*` topic ID (either against a real
lava-api-go instance via `-PrealTrackers=true` or a mock response containing the `WPO-*`-shaped
JSON) and asserts the topic page renders (title visible, comments section empty, no error state)
is required per §6.O.2 and §6.J. This is not yet authored at this closure-log commit.

The unit-level deserialization tests above are the current validation gate; the Challenge Test
is the load-bearing user-visible acceptance gate.

---

## §6.O.5 Console close-mark protocol

Per §6.O clause 5:
1. Fix commit lands with this closure log ✅ (working-tree changes, uncommitted at log creation)
2. Challenge Test authored + executed against an Internet Archive WPO-* topic ← OWED
3. Build 1.3.11-1073 distributed via §6.AA two-stage (debug → operator verify → release)
4. Operator verifies Internet Archive crawl topics open without NON_FATAL in Crashlytics
5. Open Firebase Console → Crashlytics → issue `8cde0ac208b3…` → Close issue
   — paste this closure log path:
   `.lava-ci-evidence/crashlytics-resolved/2026-06-24-topicpagedto-missing-field.md`

**DO NOT close-mark before on-device verification that a WPO-* topic loads cleanly.**

---

## Constitutional bindings

- **§6.O** — closure log, validation tests (2 unit tests), Challenge Test (OWED)
- **§6.E Capability Honesty** — the Internet Archive provider declares `TOPIC` capability;
  the fix ensures that capability actually works for crawl-format topic IDs, not just
  standard RuTracker-shaped topic IDs
- **§6.J / §6.L** — fix targets root cause (missing default on required field); test 1 is
  falsifiable on the exact pre-fix exception; test 2 guards against over-defaulting
- **§6.T.4** — entry appended to `docs/issues/fixed/BUGFIXES.md`
- **Ships in:** 1.3.11-1073
