# :core:tracker:rutor

RuTor (rutor.info / rutor.is) tracker plugin. Implements `TrackerClient`
+ feature interfaces from `:core:tracker:api`.

## Capabilities

SEARCH, BROWSE, TOPIC, COMMENTS, TORRENT_DOWNLOAD, MAGNET_LINK, RSS,
AUTH_REQUIRED. **Not implemented:** FORUM (RuTor has categories only),
FAVORITES (no list endpoint comparable to RuTracker).

## Encoding

UTF-8 throughout. No charset transcoding (unlike RuTracker which is
Windows-1251).

## Login policy (decision 7b-ii)

Anonymous by default. Login is invoked only when the SDK consumer calls
`CommentsTracker.addComment()` (or any other authenticated operation)
and `checkAuth()` returns Unauthenticated.

## Mirrors

See `app/src/main/assets/mirrors.json` for the bundled list. Health
probe checks for substring "RuTor" in response body.

## Adding a parser fixture

Save HTML to `src/test/resources/fixtures/rutor/<scope>/<name>-<date>.html`.
Refresh older than 30 days warns; older than 60 blocks tag. See
`docs/refactoring/decoupling/refresh-fixtures.md`.
