# Crashlytics investigation (OPEN, not yet root-caused) — nested-scroll §6.Q FATAL

**Issue ID:** `c7c8cccad09f72bd7bb95455226109b8` · **variant:** `99c63e8de6532cdc8c4300b0f48fc4ce`
**App:** `digital.vasic.lava.client` (release) — `1:815513478335:android:456475e2ef4039d8cfd20a`
**Error type:** FATAL · **firstSeen:** 1.2.3 · **lastSeen:** 1.3.12 · **state:** OPEN
**Status:** UNRESOLVED — root cause not yet pinned. This is a forensic-narrowing note, NOT a closure log.

## Confirmed facts (from sample event `6A40F3FA…_2234833335345584352`, eventTime 2026-06-28T10:14:43Z)

- `java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` — a **vertical LazyColumn** (`LazyListKt$rememberLazyListMeasurePolicy` → `checkScrollableContainerConstraints`) measured with unbounded max height.
- **Version 1.3.12 (1077), release build.** Device: HUAWEI TXZ-W09, Android 12, ARM64, PHONE.
- **`appOrientation: LANDSCAPE` AND `deviceOrientation: LANDSCAPE`** — the crash occurred in landscape.
- **Breadcrumb:** `screen_view { firebase_screen_class: MainActivity }` (all Compose nav lives inside MainActivity, so this does not identify the composable).
- **Last log before crash:** `WARN: credentials key holder locked — observe() emitting empty list` (module=core:credentials, operation=observe). CONFIRMED RED HERRING: this is unrelated §6.AC telemetry from the credentials repository that happened to log ~3s before the layout measure; it does not implicate the credentials screen.
- **The Lava composable frame is in the omitted section of the captured stack** — every visible frame is androidx-internal (owner=SYSTEM). The exact screen is therefore NOT identifiable from this event's visible frames.

## Candidates RULED OUT this session (CONFIRMED by source read)

- `feature/credentials/.../CredentialsScreen.kt` — its `LazyColumn` is inside `Modifier.fillMaxSize()` (bounded); no `verticalScroll`. Not a §6.Q violation.
- `feature/login/.../ProviderLoginScreen.kt` — LazyColumn was REPLACED with a plain `Column` on 2026-05-05 (in-file §6.Q comment). No LazyColumn present.
- `feature/onboarding/steps/ApiSelectionStep.kt` — KDoc-documented §6.Q compliance: uses `Column(verticalScroll)` with plain composables, NOT a LazyColumn.
- **No orientation-conditional layouts exist** anywhere in `feature/`/`app/`/`core:ui` (no `LocalConfiguration`/`ORIENTATION_LANDSCAPE`/`WindowWidthSizeClass`/`BoxWithConstraints`), so it is not an explicit landscape-branch layout.
- **No `IntrinsicSize` usage** anywhere in those trees (a common infinite-constraint trigger) — ruled out.

## Why the §6.Q structural scanner does not catch it

The §6.Q scanner matches `LazyColumn` co-located with `verticalScroll` in the same screen file. This crash is a LazyColumn receiving unbounded height from a parent that does NOT use `verticalScroll` in the same file (e.g. a shared container/component higher in the tree, or a lazy-in-lazy nesting across files). PENDING_FORENSICS: the exact unbounded-height source.

## Next diagnostic step (for the next session / when device gate unblocks)

1. Fetch MORE sample events for this issue (`crashlytics_list_events` with an explicit `intervalStartTime` within 90 days) to obtain one whose stack retains the Lava composable frame (the omitted frames vary per event), OR
2. Run a **landscape-orientation Challenge sweep** across every screen on the containerized emulator (§6.AH device gate — currently blocked on this host) forcing `adb shell settings put system user_rotation 1`, driving each screen, and asserting no `checkScrollableContainerConstraints` crash. The screen that crashes IS the culprit.
3. Once the screen is identified, apply the §6.Q remediation (bound the LazyColumn height via `heightIn(max=…)` or replace with a plain Column for small-N lists) + a reproduce-first structural/Challenge test.

Do NOT ship a speculative §6.Q "fix" to a guessed screen — per §6.J that would be a bluff (a change that passes tests while the real crash persists). The screen must be identified first.
