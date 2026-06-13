# Crashlytics issue 6519b490 — rutracker parseUserId "user-id not found" (investigation + canary)

**Issue ID:** `6519b490…`
**Title / Subtitle:** `IllegalStateException: rutracker logged-in user-id not found — page may be guest, or selectors stale`
**Type:** NON_FATAL
**Version:** 1.2.22
**Source:** `GetCurrentProfileUseCase.parseUserId`
**State at this entry:** OPEN — VERDICT: working-as-designed telemetry (no production code change)

## Investigation question

Is this non-fatal working-as-designed (a genuine guest / expired-session page
legitimately has no logged-in user-id) OR are the four production CSS selectors
in `GetCurrentProfileUseCase.LOGGED_IN_SELECTORS` stale (rutracker changed its
markup so a real logged-in page no longer matches)?

## Verdict (evidence-based, per §11.4.6 no-guessing)

**Working-as-designed telemetry.** Evidence from the new real-stack test
`core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/domain/GetCurrentProfileUseCaseUserIdTest.kt`:

- `loggedInPage_parsesUserId` — feeds a representative logged-in main-page HTML
  through the REAL production path (`GetCurrentProfileUseCase` → real private
  `parseUserId` → real Jsoup → real `GetProfileUseCase`; only the network
  boundary `RuTrackerInnerApi` faked). The four production selectors correctly
  extract `u=12345`. GREEN ⇒ the selectors are NOT stale against this markup
  shape — the broad fallback `a[href*='profile.php?u=']` matches any logged-in
  page's own profile link.
- `guestPage_throwsUserIdNotFound` — feeds a page with NO `profile.php?u=` link
  (the guest / expired-session shape) and asserts the production code throws
  the exact "user-id not found" `IllegalStateException`. ⇒ the non-fatal fires
  ONLY when there genuinely is no user-id, which is correct behaviour.

No production fix is warranted. The error message already names both
possibilities ("page may be guest, or selectors stale"); the non-fatal is the
operator's signal that a session probe hit a guest/expired page.

## Canary

`loggedInPage_parsesUserId` is the CANARY: if rutracker later changes its real
logged-in markup so none of the four selectors match, this test turns RED and
reclassifies the issue as "selectors stale" — converting the future guess into
evidence.

## Falsifiability rehearsal (Bluff-Audit)

- Mutation: reduced `LOGGED_IN_SELECTORS` to a single `"#nonexistent-mutation-selector"`.
- Observed: `loggedInPage_parsesUserId` FAILED at GetCurrentProfileUseCaseUserIdTest.kt:100
  (the logged-in page no longer parses; `invoke` throws the "user-id not found"
  IllegalStateException). The guest test stayed green.
- Reverted: yes — `:core:tracker:rutracker:test --tests "*GetCurrentProfileUseCaseUserIdTest"` BUILD SUCCESSFUL after revert.
