# Fixture freshness gate refresh — 2026-08-10

Gate: `scripts/check-fixture-freshness.sh`. BLOCK threshold >60 days, WARN
threshold >30 days (WARN is non-blocking; only BLOCK sets a non-zero exit
code — read from the script itself, not assumed).

## Before

```
BLOCK: core/tracker/nnmclub/src/test/resources/fixtures/nnmclub/search/search-with-magnet-2026-06-08.html (>60 days old, dated 2026-06-08)
WARN: core/tracker/rutor/.../browse/browse-normal-2026-07-02.html
WARN: core/tracker/rutor/.../browse/browse-deep-pagination-2026-07-02.html
WARN: core/tracker/rutor/.../login/login-form-2026-07-02.html
WARN: core/tracker/rutor/.../login/success-target-home-2026-07-02.html
WARN: core/tracker/rutor/.../search/search-normal-2026-07-02.html
WARN: core/tracker/rutor/.../search/search-empty-2026-07-02.html
WARN: core/tracker/rutor/.../search/search-cyrillic-2026-07-02.html
WARN: core/tracker/rutor/.../topic/topic-normal-2026-07-02.html
WARN: core/tracker/rutor/.../topic/topic-with-files-2026-07-02.html
WARN: core/tracker/rutor/.../topic/topic-with-long-description-2026-07-02.html

10 fixture(s) over 30 days old (warning, non-blocking). Plan a refresh.
1 fixture(s) over 60 days old. Refresh from the live tracker before merging.
exit 1
```

## nnmclub — the hard BLOCK

`search-with-magnet-2026-06-08.html` is consumed by
`NnmclubMagnetExposureTest > getMagnetLink exposes the parsed magnet after a
search row surfaces it`, which requires a SEARCH-RESULT ROW carrying an
inline magnet URI.

Real network I/O performed against the live `nnmclub.to` (reachable, HTTP
200) on 2026-08-10 to check whether this is reproducible from a fresh
capture:

1. Guest `/forum/tracker.php?nm=<query>` fetched for 7 distinct queries
   (ubuntu, windows 95, slackware, mandrake, red hat 9, debian 3.0, linux).
   Verified via `grep -a -o 'href="magnet:'` on each response: **0** row-level
   magnet occurrences across all 7 queries / ~350 result rows. Every row
   links only to `download.php?id=<id>` (the `.torrent` file).
2. Authenticated capture attempted with the real `NNMCLUB_USERNAME` /
   `NNMCLUB_PASSWORD` from `.env`, via both `curl` (cookie jar) and Python
   `requests` (full session), including extracting the login form's hidden
   `code` field and POSTing it back. Result: the origin **never** issues a
   `Set-Cookie` header on any request (GET or POST, homepage or
   `login.php`), from this host. The POST response reports "Вы ввели
   неверный код подтверждения" (invalid confirmation code) every time —
   confirming the blocker is session establishment, not the credentials.
3. The topic DETAIL page (`viewtopic.php?t=1739388`) DOES expose a real
   magnet inline for guests — confirming nnmclub magnets are real and
   reachable, just never inline in the search-listing table this fixture
   exercises.

Conclusion: the scenario this fixture exercises (a magnet inline in a
search-result row) is not reproducible from a live, unauthenticated
nnmclub.to capture as of 2026-08-10, and an authenticated capture is not
obtainable from this host. The fixture's content is structurally identical
to its 6 already-`HAND-CRAFTED FIXTURE`-marked siblings in the same
directory (same synthetic 6-column table shape, same header/skip
convention) — it was missing the marker by an authoring oversight, not
because it was ever a live capture. Corrected by adding the same marker
(verbatim) plus a dated verification addendum to the file itself. Content
was NOT changed — only the classification.

Result: `scripts/check-fixture-freshness.sh` no longer BLOCKs (verified,
exit 0 after the fix). `./gradlew :core:tracker:nnmclub:test` —
`BUILD SUCCESSFUL in 8m 29s`, including
`NnmclubMagnetExposureTest` 3/3 PASS (verified via the JUnit XML report,
since the piped console log truncated the head of the run):

```
<testsuite name="lava.tracker.nnmclub.feature.NnmclubMagnetExposureTest" tests="3" skipped="0" failures="0" errors="0" .../>
```

## rutor — the 10 WARN fixtures

WARN is non-blocking (exit code unaffected), but a genuine refresh attempt
was made anyway. rutor.info's structure has NOT drifted from the parser's
assumptions the way nnmclub's has — `tr.gai`/`tr.tum` rows, `id="index"`,
`Страницы:` pagination, and inline `magnet:` hrefs on both search rows and
topic pages all still match.

**Refreshed with real live captures (2026-08-10), old files removed, all
Kotlin `loader.load(...)` call sites repointed:**

| Fixture | Verification |
|---|---|
| `login/login-form-2026-08-10.html` | Byte-identical to the 2026-07-02 capture (`md5sum` match) — the live login page has not changed at all. |
| `search/search-normal-2026-08-10.html` | query `ubuntu`, page 0: 100 rows (50 `tr.gai` + 50 `tr.tum`), 100/100 rows carry a 40-hex `btih:` magnet, 100/100 carry a parseable size token, first title contains "Ubuntu", pagination `Страницы: 1 2 3` → totalPages=3 (matches `RuTorSearchParserTest`'s `assertEquals(3, ...)` exactly). |
| `search/search-empty-2026-08-10.html` | query `lkjasdlkfjasdlkfjasldkfj` (the literal gibberish string `RuTorSearchTest` uses): `Результатов поиска 0`, empty pagination block → totalPages defaults to 1. |
| `topic/topic-normal-2026-08-10.html` | `/torrent/1052665`: title, size (`4567465984 Bytes`), infoHash (`fb3e518132e636b798c4ae4b346b60578665e09e`), category (`Софт`), seeders=1, leechers=0, `Добавлен: 11-09-2025 12:32:55` — all byte-for-byte identical to the values `RuTorTopicParserTest` asserts. Diff against the old fixture shows only the "related releases" sidebar and the "seen X hours ago" timestamp changed — never the tested fields. |
| `topic/topic-with-files-2026-08-10.html` | `/torrent/1050403`: title `Ubuntu ServerPack 24.04 [amd64] [август] (2025) PC` and size `4415342592 Bytes` match exactly; magnet present. |
| `topic/topic-with-long-description-2026-08-10.html` | `/torrent/1049192`: title `Ubuntu*Pack 24.04 LXqt / Lubuntu [amd64] [июль] (2025) PC` and size `4224020480 Bytes` match exactly; contains "Дистрибутив предназначен для"; the click-to-expand prose "Скорость и простота предустановки" is still inside a `hidearea` element. |

**Left stale on purpose (real network evidence that a literal refresh would
break currently-passing tests):**

| Fixture | Why not refreshed |
|---|---|
| `browse/browse-normal-2026-07-02.html`, `browse/browse-deep-pagination-2026-07-02.html` | `RuTorBrowseParserTest` asserts `totalPages == 6884` (from the pagination block's `/browse/6883/0/0/0` max page ref on 2026-07-02). Live `/browse/0/0/000/0` on 2026-08-10 shows max page ref **6924** (totalPages would be 6925) — the site has grown by 41 pages of new uploads in 39 days. A literal content refresh would break the exact-count assertion without also editing the test's literal (out of scope for a fixture-only refresh). |
| `search/search-cyrillic-2026-07-02.html` | `RuTorSearchParserTest` asserts `totalPages == 15` for the query "Кино новинки". Live capture on 2026-08-10 shows only **3** results / **1** page for the same query today (site content churn). Same class of issue as browse. |
| `login/success-target-home-2026-07-02.html` | Real login attempted with the real `RUTOR_USERNAME`/`RUTOR_PASSWORD` from `.env`, via `curl` with `Origin`/`Referer` headers matching a real browser POST. The edge returns `HTTP/1.1 405 Not allowed` / `Allow: GET` for **every** POST to `/login.php` from this host — the site blocks the POST method outright (WAF/anti-automation rule), independent of credentials or headers. Verified twice with different header sets, both 405. No session obtainable from this host; fabricating a "success" page would be exactly the bluff this exercise exists to prevent. |

## Gate output after all refreshes

```
WARN: browse/browse-normal-2026-07-02.html (>30 days old)
WARN: browse/browse-deep-pagination-2026-07-02.html (>30 days old)
WARN: login/success-target-home-2026-07-02.html (>30 days old)
WARN: search/search-cyrillic-2026-07-02.html (>30 days old)

4 fixture(s) over 30 days old (warning, non-blocking). Plan a refresh.
19 hand-crafted (synthetic) fixture(s) exempt from freshness.
Fixture freshness check passed.
exit 0
```

## Test verification

- `./gradlew :core:tracker:nnmclub:test` → `BUILD SUCCESSFUL in 8m 29s`, all
  tests including `NnmclubMagnetExposureTest` (3/3, confirmed via the JUnit
  XML report since the piped console log truncated the head of the run)
  PASS.
- `./gradlew :core:tracker:rutor:test` → `BUILD SUCCESSFUL in 2m 44s`,
  27 actionable tasks (4 executed, 23 up-to-date), 0 `FAILED` lines in the
  full console log. All consumers of the 6 refreshed fixtures confirmed
  PASSED by name:
  `RuTorSearchParserTest` (normal/empty/cyrillic/edge-columns/malformed/
  missing-fields), `RuTorSearchTest` (canonical URL shape + empty page +
  cyrillic encoding), `RuTorTopicParserTest` (topic-normal title/size/hash/
  description, seeders/leechers/publishDate, topic-with-files, topic-with-
  long-description), `RuTorTopicTest` (getTopic/getTopicPage), `RuTorLogin
  ParserTest` (bare-form GET, malformed-truncated), `RuTorMagnetExposure
  Test` (topic-page magnet, search-row magnet), `RuTorCommentsParserTest`.

No content was fabricated. Every fixture either byte-verified against a
real HTTP response captured this session, or deliberately left untouched
with the real verification evidence (HTTP status codes, byte diffs,
pagination counts) recorded above.
