# Phase 4 — Sync Expansion Implementation Plan

> **For agentic workers:** Use subagent-driven-development.

**Goal:** Add Sync Now buttons, device identity, history + credentials sync categories, expand Menu sync settings.

**Architecture:** Extend existing `Settings`, `SyncPeriod`, `BackgroundService`, WorkManager workers. Add generated UUID for device identity. No cloud backend needed.

**Tech Stack:** WorkManager, Room, Orbit MVI, SharedPreferences

---

### Task 1: Add device identity to Settings

**Files:**
- Modify: `core/models/src/main/kotlin/lava/models/settings/Settings.kt`
- Modify: `core/preferences/src/main/kotlin/lava/securestorage/PreferencesStorageImpl.kt`
- Modify: `core/preferences/src/main/kotlin/lava/securestorage/PreferencesStorage.kt`
- Modify: `core/data/src/main/kotlin/lava/data/impl/repository/SettingsRepositoryImpl.kt`

**Steps:**
1. Add `val deviceId: String = ""` to `Settings` data class
2. In `PreferencesStorageImpl`, add key `"device_id"` — on first read, if empty/null, generate and persist `UUID.randomUUID().toString()`
3. Add `getDeviceId(): String` to `PreferencesStorage` interface
4. In `SettingsRepositoryImpl`, include `deviceId` in the settings flow
5. Compile, spotless, commit

---

### Task 2: Add history + credentials sync periods to Settings

**Files:**
- Modify: `core/models/src/main/kotlin/lava/models/settings/Settings.kt`
- Modify: `core/models/src/main/kotlin/lava/models/settings/SyncPeriod.kt` (add `THIRTY_MINUTES`)
- Modify: `core/preferences/.../PreferencesStorageImpl.kt`
- Modify: `core/preferences/.../PreferencesStorage.kt`
- Modify: `core/data/.../SettingsRepositoryImpl.kt`

**Steps:**
1. Add `val historySyncPeriod: SyncPeriod = SyncPeriod.OFF` and `val credentialsSyncPeriod: SyncPeriod = SyncPeriod.OFF` to `Settings`
2. Add `THIRTY_MINUTES` to `SyncPeriod` enum
3. Persist via SharedPreferences keys `"history_sync_period"` and `"credentials_sync_period"`
4. Add repository methods `setHistorySyncPeriod()` and `setCredentialsSyncPeriod()`
5. Compile, spotless, commit

---

### Task 3: Add Sync Now button to Favorites + Bookmarks

**Files:**
- Modify: `feature/favorites/src/main/kotlin/lava/favorites/FavoritesScreen.kt`
- Modify: `feature/favorites/src/main/kotlin/lava/favorites/FavoritesViewModel.kt`
- Modify: `feature/favorites/src/main/kotlin/lava/favorites/FavoritesAction.kt`
- Modify: `feature/bookmarks/src/main/kotlin/lava/forum/bookmarks/BookmarksScreen.kt`
- Modify: `feature/bookmarks/src/main/kotlin/lava/forum/bookmarks/BookmarksViewModel.kt`
- Modify: `feature/bookmarks/src/main/kotlin/lava/forum/bookmarks/BookmarksAction.kt`

**Steps:**
1. Add `SyncNowClick` action to both FavoritesAction and BookmarksAction
2. Add `isSyncing: Boolean` to both states
3. In ViewModels, `SyncNowClick` triggers refresh + sync use cases via viewModelScope, toggles `isSyncing`
4. In Screens, add a small sync icon button in the top bar (next to back button or as a trailing icon). Shows spinner when syncing
5. Compile, spotless, commit

---

### Task 4: Create SyncHistoryWorker + SetHistorySyncPeriodUseCase

**Files:**
- Create: `core/work/impl/.../workers/SyncHistoryWorker.kt`
- Create: `core/domain/.../SetHistorySyncPeriodUseCase.kt`
- Modify: `core/work/api/.../BackgroundService.kt`
- Modify: `core/work/impl/.../WorkBackgroundService.kt`

**Steps:**
1. Add `syncHistory(period)` and `syncCredentials(period)` to `BackgroundService` interface
2. In `WorkBackgroundService`, implement both methods (schedule/cancel `PeriodicWorkRequest`)
3. Create `SyncHistoryWorker` as `@HiltWorker` — currently a no-op (history is local-only, worker placeholder for future backend)
4. Create `SetHistorySyncPeriodUseCase` — persists setting + schedules worker
5. Compile, spotless, commit

---

### Task 5: Create SyncCredentialsWorker + SetCredentialsSyncPeriodUseCase

**Files:**
- Create: `core/work/impl/.../workers/SyncCredentialsWorker.kt`
- Create: `core/domain/.../SetCredentialsSyncPeriodUseCase.kt`

**Steps:**
1. Create `SyncCredentialsWorker` as `@HiltWorker` — iterates providers via `LavaTrackerSdk`, calls `checkAuth()` for each, reports failures via `NotificationService`
2. Create `SetCredentialsSyncPeriodUseCase` — persists setting + schedules worker
3. Compile, spotless, commit

---

### Task 6: Expand Menu sync settings UI

**Files:**
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuScreen.kt`
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuViewModel.kt`
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuAction.kt`

**Steps:**
1. Add `SetHistorySyncPeriod` and `SetCredentialsSyncPeriod` actions
2. Add `historySyncPeriod` and `credentialsSyncPeriod` to MenuState
3. Wire `SetHistorySyncPeriodUseCase` and `SetCredentialsSyncPeriodUseCase` into ViewModel
4. In MenuScreen, expand the sync section from 2 to 4 rows: Favorites, Bookmarks, History, Credentials — each with sync period dropdown
5. Compile, spotless, commit

---

### Task 7: CI gate + CONTINUATION.md

Run spotless, constitution check, Go tests, update docs, push to mirrors.
