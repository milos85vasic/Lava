# Phase 3 — First-Run Onboarding Wizard Design

**Date:** 2026-05-07
**Status:** Approved (post-brainstorm)
**Parent decomposition:** Phase 3 of 6 (see `docs/CONTINUATION.md` §4.2)
**Constitutional bindings:** §6.J, §6.L, §6.R, §6.Q, §6.S, §7

---

## 1. Problem Statement

The current onboarding is a single flat screen (`OnboardingScreen` wrapping `ProviderLoginScreen`). Users pick one provider, configure it, and proceed. There is no multi-step wizard, no connection testing per provider, and no summary step. The operator's TODO says: "Leaving wizard without configured providers should close the app" and "user MUST execute connection tests with provided credentials with success."

Phase 3 builds a proper multi-step onboarding wizard: welcome → pick providers → configure each → summary.

---

## 2. Design Decisions (from brainstorm)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Wizard layout | **Single screen with phased content** | One ViewModel, `AnimatedContent` transitions. Cleaner for repeated per-provider config steps. |
| Provider requirement | **Close app on back press** | `finish()` at welcome step. Operator spec: "Leaving wizard without configured providers should close the app." |
| Connection test | **Auto-test on credential submit** | Spinner + auto-advance on success, error + retry on failure. No separate test step. |

---

## 3. Wizard Step Flow

```
Welcome ──► Pick Providers ──► Configure Provider ──► (repeat for each) ──► Summary
   │               │                    │                                        │
   │◄── back:      │◄── back:          │◄── back:                               │
   │    finish()    │    back to        │    back to                              │
   │                │    Welcome        │    Providers                            │
```

### Step 1: Welcome
- Brief intro: "Welcome to Lava. Let's set up your content providers."
- Shows provider count: "6 providers available"
- Single "Get Started" button → advances to Providers

### Step 2: Pick Providers
- Checklist of all `apiSupported && verified` providers (6 today)
- Each item: provider name, auth type badge (Form Login / Anonymous), color dot
- Multi-select: user can pick any combination
- "Next" enabled when ≥1 provider selected
- Pre-selected: all 6 providers checked by default

### Step 3: Configure Provider (repeated per selected provider)
- **Anonymous path** (`AuthType.NONE`): shows provider name + "Continue as Anonymous" button. Tapping it runs health check, on success advances. No credential form.
- **Auth path**: username + password fields (reuse `LoginScreenCommon` components). "Test & Continue" button.
- **Auto-test**: button tap triggers `sdk.checkAuth()` or `sdk.healthCheck()`. Shows spinner "Testing connection to RuTracker...". On success auto-advances. On failure shows error + retry.

### Step 4: Summary
- "All set!" header
- List of connected providers with green checkmarks
- "Start Exploring" button → writes `onboardingComplete = true`, signals auth for each provider, exits to main screen

---

## 4. State Model

```kotlin
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val availableProviders: List<ProviderOnboardingItem> = emptyList(),
    val selectedProviderIds: Set<String> = emptySet(),
    val currentProviderIndex: Int = 0,
    val providerConfigs: Map<String, ProviderConfigState> = emptyMap(),
    val connectionTestRunning: Boolean = false,
    val connectionTestError: String? = null,
)
```

Where:
- `OnboardingStep`: enum `Welcome, Providers, Configure, Summary`
- `ProviderOnboardingItem`: wraps `TrackerDescriptor` with selection state
- `ProviderConfigState`: per-provider auth fields + test status

---

## 5. Architecture

### New module: `feature:onboarding`

```
feature/onboarding/
├── src/main/kotlin/lava/onboarding/
│   ├── OnboardingScreen.kt        # Main composable with AnimatedContent
│   ├── OnboardingViewModel.kt     # @HiltViewModel, Orbit MVI
│   ├── OnboardingState.kt         # State + step enum
│   ├── OnboardingAction.kt        # Actions: NextStep, BackStep, SelectProvider, ...
│   ├── OnboardingSideEffect.kt    # Side effects: Finish, ShowError
│   ├── steps/
│   │   ├── WelcomeStep.kt         # Welcome screen composable
│   │   ├── ProvidersStep.kt       # Provider selection list
│   │   ├── ConfigureStep.kt       # Per-provider credential form
│   │   └── SummaryStep.kt         # Completion summary
│   └── navigation/
│       └── OnboardingNavigation.kt # Navigation extension for MainActivity
```

### Changes to existing modules:
- **`:app`**: Replace `OnboardingScreen` usage in `MainActivity.kt` with new `feature:onboarding` composable
- **`:feature:login`**: Deprecate `OnboardingScreen.kt` (thin wrapper, no longer needed)
- **`settings.gradle.kts`**: Register `:feature:onboarding`

### No changes needed:
- `ProviderLoginViewModel` + `ProviderLoginScreen` — kept for Settings → Credentials flow (post-onboarding credential editing)
- `core/preferences` — `isOnboardingComplete()` flag reused
- `core/auth` — `signalAuthorized()` reused for each provider

---

## 6. Connection Test Logic

For `AuthType.NONE` providers:
```
1. Call provider.HealthCheck() via Go API
2. 200 → advance. Error → show "Cannot reach Archive.org. Check your network."
```

For auth-requiring providers:
```
1. Call sdk.login(providerId, username, password)
2. Success → save credentials → advance
3. Wrong credentials → "Invalid username or password. Try again."
4. Network error → "Cannot reach NNM-Club. Check your network and try again."
```

---

## 7. Back Press Behavior

| Current step | Back press action |
|---|---|
| Welcome | `finish()` — close the app |
| Providers | Go back to Welcome (which then closes on next back) |
| Configure | Go back to Providers (deselect current provider if config not committed) |
| Summary | Ignored — user must tap "Start Exploring" |

---

## 8. Files Affected

### New files:
| File | Responsibility |
|------|---------------|
| `feature/onboarding/build.gradle.kts` | Module config |
| `feature/onboarding/src/main/.../OnboardingScreen.kt` | Main wizard composable |
| `feature/onboarding/src/main/.../OnboardingViewModel.kt` | Step orchestration, connection testing |
| `feature/onboarding/src/main/.../OnboardingState.kt` | State + step enum |
| `feature/onboarding/src/main/.../OnboardingAction.kt` | User actions |
| `feature/onboarding/src/main/.../OnboardingSideEffect.kt` | One-time effects |
| `feature/onboarding/src/main/.../steps/WelcomeStep.kt` | Welcome UI |
| `feature/onboarding/src/main/.../steps/ProvidersStep.kt` | Provider picker |
| `feature/onboarding/src/main/.../steps/ConfigureStep.kt` | Credential form |
| `feature/onboarding/src/main/.../steps/SummaryStep.kt` | Completion summary |

### Modified files:
| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `:feature:onboarding` |
| `app/.../MainActivity.kt` | Replace `OnboardingScreen` with new wizard |
| `feature/onboarding/src/main/AndroidManifest.xml` | Standard manifest |

---

## 9. Testing Strategy

| Test | Type | Location |
|------|------|----------|
| `OnboardingViewModelTest` | Unit | `feature/onboarding/src/test/` — step transitions, provider selection, back press behavior |
| `OnboardingStepsTest` | Structure | `feature/onboarding/src/test/` — Compose layout regressions per §6.Q |
| Challenge C20 | Compose UI | `app/src/androidTest/` — full wizard flow: Welcome → pick providers → configure → finish → verify main screen appears |
| Challenge C21 | Compose UI | `app/src/androidTest/` — back press at Welcome closes app |
| Challenge C22 | Compose UI | `app/src/androidTest/` — anonymous provider: pick Archive.org → Continue → auto-advances |

---

## 10. Visual Style

- **Welcome step**: Centered logo/text, large "Get Started" button
- **Providers step**: Cards with checkbox, colored provider dot, auth type badge (AssistChip), Anonymous toggle if supported
- **Configure step**: Clean form with provider color header, `LoginScreenCommon` fields, full-width "Test & Continue" button with built-in spinner
- **Summary step**: Green checkmark list, "Start Exploring" CTA button
- **Transitions**: `AnimatedContent` with `fadeIn + slideInHorizontally / fadeOut + slideOutHorizontally`

All visual decisions auto-approved per operator directive.
