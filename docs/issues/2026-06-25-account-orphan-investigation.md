# Dead-Code Investigation — `feature/account` `AccountItem` orphan (§11.4.124)

- **Date:** 2026-06-25
- **Investigator scope:** READ-ONLY (source + git history). No code changed, no removal, no wiring, no commit.
- **Constitutional basis:** §11.4.124 (dead-code investigate-before-remove), §11.4.122 (operator-confirm before removing a user-facing capability), Anti-Bluff (cite git history as FACT; UNCONFIRMED where ambiguous).

---

## 1. Orphan confirmation — CONFIRMED (yes)

`feature/account`'s `AccountItem()` composable is **NOT mounted by any production screen**. It is reachable in-app via **no** navigation path today.

### Production source references to `AccountItem` / `AccountViewModel` / `Account*`
All production references are **self-contained within `feature/account/` itself** — nothing in `:app`, `feature/menu`, any nav graph, or any other feature calls it:

- `feature/account/src/main/kotlin/lava/account/AccountItem.kt` — definition only (public `AccountItem(onLoginClick)` overload + internal overloads + `@Preview`).
- `feature/account/src/main/kotlin/lava/account/AccountViewModel.kt` / `AccountAction.kt` / `AccountSideEffect.kt` — the feature's own MVI parts.
- `feature/account/src/test/kotlin/lava/account/AccountViewModelTest.kt` — unit test (in-module).

### The ONLY external references are test-only, added the same day as this investigation
- `app/src/androidTest/kotlin/lava/app/AccountTestHostActivity.kt` — a **test-only** host activity whose sole job is `setContent { AccountItem(...) }`.
- `app/src/androidTest/kotlin/lava/app/challenges/Challenge57AccountItemRendersAuthStateTest.kt` — Challenge C57, which **explicitly documents** the orphan ("As of 2026-06-25 the production `AccountItem` composable is NOT mounted by any production screen … not referenced by MenuScreen or any nav graph") and drives it through the test-only host precisely because it is unreachable in-app.

### Nav extensions: absent
`grep` for `addAccount` / `openAccount` across all `*.kt` / `*.kts` returns **zero hits**. There is no `addAccount()`/`openAccount()` navigation DSL pair (the standard Lava per-feature nav contract) for account — confirming there is no nav route to it.

### Dangling Gradle dependency (the corroborating fingerprint)
`feature/menu/build.gradle.kts:27` still declares `implementation(project(":feature:account"))`, **but no `feature/menu` source file imports `lava.account` or calls `AccountItem`** (grep of `feature/menu/src` for `lava.account` / `AccountItem` / `account` → zero hits). A declared-but-unused module dependency is the classic signature of a **deleted call-site whose build wiring was left behind**. (`:feature:account` is also still wired in `settings.gradle.kts:61` and `app/build.gradle.kts:304`.)

---

## 2. Git-history facts (§11.4.124 core) — the "when/how it was wired" and "when/how it died"

### How it was ORIGINALLY wired (FACT)
`AccountItem` was the **account row at the top of the Menu/Settings screen**, mounted inside the Menu's `LazyColumn` as a list item. The deleted wiring (recovered verbatim from the diff of the commit that removed it — see below) was:

```kotlin
import lava.account.AccountItem
// …inside MenuScreen's LazyListScope:
menuAccountItem { onAction(LoginClick) }
// …extension:
private fun LazyListScope.menuAccountItem(onLoginClick: () -> Unit) =
    item { AccountItem(onLoginClick = onLoginClick) }
```

So the in-app path was: **open Menu/Settings → top account row → tap "Login" (→ login screen) OR see the signed-in user name + logout button.** This account row existed in the menu module since modularization and predates it:

- `6664b163` — "Rewritten in Kotlin and Jetpack Compose" (2021-09-01) — earliest pickaxe hit for `AccountItem(`.
- `91ebf6a0` — "Modularization" (2022-11-17) — `AccountItem` present in the extracted menu module.
- (also touched by `120abce5` Bugfixes #14, `90747279` Fix db migration #34, `5e65fbe0` Flow→Lava rebrand 2026-04-27.)

### When/how it became DEAD (FACT — deliberate supersession, NOT a regression)
**Commit `8cd070e0` — "feat(menu): multi-provider header showing all signed-in providers"**
- **SHA:** `8cd070e02cba4094e1805598022f8ab9cda95377`
- **Author:** Милош Васић
- **Date:** 2026-05-07
- **Commit body (verbatim, load-bearing):**
  > Add ProviderMenuItem model to MenuState, load all registered trackers with auth status and stored credentials in MenuViewModel, render a provider list with colored dots, username/Anonymous label, and sign-out button per authenticated provider.
  > - Added logout(trackerId) to LavaTrackerSdk for per-provider sign-out
  > - Added SignOut(providerId) action to MenuAction
  > - **Replaced single AccountItem with provider list in MenuScreen**
  > - Each provider row: colored dot, name, username/status, sign-out

- **What the diff did to `feature/menu/src/main/kotlin/lava/menu/MenuScreen.kt`:** removed `import lava.account.AccountItem`, removed the `menuAccountItem { onAction(LoginClick) }` call-site, and removed the `private fun LazyListScope.menuAccountItem(...) = item { AccountItem(onLoginClick = onLoginClick) }` extension — replacing them with an inline multi-provider header.

**Conclusion (stated as FACT, not conjecture):** `AccountItem` became dead by a **deliberate feature refactor / supersession** on 2026-05-07. The single-account row was intentionally replaced by a richer **multi-provider header** that renders every registered tracker with per-provider auth status and per-provider sign-out. This was **not** a mistake, **not** a regression, and **not** never-completed work. The only oversight was hygiene: the now-unused `feature/menu → :feature:account` Gradle dependency (`feature/menu/build.gradle.kts:27`) was not removed when the call-site was deleted.

> The C57 Challenge + `AccountTestHostActivity` were added later in `45360bd4` (2026-06-25) — they did not wire the feature back into the app; they test the orphan in isolation via a test-only host and explicitly record its orphan status.

---

## 3. Hidden-reference check — NONE found

Checked every dynamic mounting vector the static call-site grep cannot see:

- **Reflection / `Class.forName` / `Class<…>`:** zero account hits.
- **Deep-link / `intent-filter` / `scheme` / nav route:** zero. `feature/account/src/main/AndroidManifest.xml` is an **empty `<manifest/>`** — no activity, no deep-link.
- **DI / Hilt provision:** `AccountViewModel` is `@HiltViewModel internal class AccountViewModel @Inject constructor(...)`. Hilt makes it *resolvable*, but a `@HiltViewModel` is inert unless a composable/owner mounts it via `hiltViewModel()`/`viewModel()`. The only `viewModel()` call is inside `AccountItem.kt` itself — which nothing mounts. No `@Module`/`@Provides`/`@Binds` references account.
- **Feature-flag gate:** none. No flag conditionally renders an account screen.

**Result:** the account feature is wired **nowhere dynamically**. The orphan is genuine and total.

---

## 4. Evidence-backed recommendation — (c) operator-confirmed REMOVE

The right action is **(c) operator-confirmed REMOVE**, NOT (a) wire-back and NOT (b) finish.

**Why not (a) wire-back:** the call-site was not deleted by mistake — it was deliberately superseded (commit `8cd070e0`). Re-mounting `AccountItem` in `MenuScreen` would create a **duplicate, conflicting account UI** sitting alongside the multi-provider header that already owns login/logout. The user-facing capability `AccountItem` once provided (sign in, show signed-in user, sign out) is **fully preserved and improved** in the current menu: `MenuAction.LoginClick` (unauthenticated), `MenuAction.SignOut(providerId)` / `ConfirmSignOut(providerId)` (per-provider sign-out), `OpenProviderConfig(providerId)`, rendered by the `ProviderMenuItem` list in `MenuScreen`. **No capability is lost by removal** — that is the captured proof §11.4.124/§11.4.122 require.

**Why not (b) finish:** nothing is unfinished. `AccountItem` was complete and reachable for years before being intentionally replaced.

**Why (c) and not (d) keep-as-is:** keeping a fully-orphaned module is dead weight (a whole Gradle module + a test-only host + Challenge whose only purpose is to exercise unreachable code). There is no documented intentional reason to retain it; the feature it represented now lives in `feature/menu`.

### Operator-gated removal scope (for the operator's decision — do NOT execute without confirmation per §11.4.122, since account/login was a user-facing capability)
If the operator confirms removal, the genuinely-dead set is:
1. The `feature/account` module (`AccountItem.kt`, `AccountViewModel.kt`, `AccountAction.kt`, `AccountSideEffect.kt`, `AccountViewModelTest.kt`, manifest, strings).
2. The dangling wiring: `feature/menu/build.gradle.kts:27`, `app/build.gradle.kts:304`, `settings.gradle.kts:61`.
3. The test-only fixtures that exist **only** to exercise the orphan: `app/src/androidTest/.../AccountTestHostActivity.kt` and `Challenge57AccountItemRendersAuthStateTest.kt`. (Per anti-bluff: C57 tests unreachable code, so it provides no end-user guarantee — it must go with the module, not be left asserting on deleted code.)

**Captured proof it is no longer needed (the §11.4.124 bar):** commit `8cd070e0` (2026-05-07) deliberately replaced it AND the superseding multi-provider header demonstrably covers login + per-provider logout in `MenuScreen` / `MenuAction` today (verified in §2/§4 above).

**UNCONFIRMED:** nothing material is unconfirmed. The "when/how it died" is established as fact from the commit body + diff; the only non-code residue (the dangling Gradle dep) is consistent with that fact.

---

## 5. Report metadata
- **Report path:** `docs/issues/2026-06-25-account-orphan-investigation.md`
- **Not committed** (per task constraints — main stream commits).
- **Key cited commits:** `8cd070e0` (death, 2026-05-07), `91ebf6a0` (2022-11-17) + `6664b163` (2021-09-01) (origin), `45360bd4` (2026-06-25, test-only C57 added).
