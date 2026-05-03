# Feature Specification: Multi-Provider Extension

**Feature Branch**: `[001-multi-provider-extension]`  
**Created**: 2026-05-02  
**Status**: Draft  
**Input**: User description: Extend the Lava system (Go API and Android client) to support multiple content providers beyond RuTracker and RuTor. Add NNMClub and Kinozal torrent trackers, plus Internet Archive and Project Gutenberg HTTP-based providers. Implement unified search and forums, credentials management, modern UI/UX with Material Design 3, comprehensive error handling, and full anti-bluff testing coverage.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Credentials Management (Priority: P1)

Users can create, edit, and delete authentication credentials (usernames/passwords or tokens/API keys) in a centralized Settings screen. Credentials are encrypted at rest and can be shared across multiple providers. Users assign descriptive labels to credentials for easy identification.

**Why this priority**: Without credentials management, authenticated providers (NNMClub, Kinozal, RuTracker) cannot be accessed. This is the foundational enabler for the entire multi-provider experience.

**Independent Test**: A user can open Settings, create a username/password credential labeled "My Tracker Account", edit the password, and delete the credential. All changes persist across app restarts.

**Acceptance Scenarios**:

1. **Given** the user is on the Credentials Management screen, **When** they tap "Create Credential", enter a label, select "Username + Password" type, fill in username and password, and tap "Save", **Then** the credential appears in the list with the correct label and type badge.
2. **Given** the user has at least one credential, **When** they tap the edit action on a credential, change the label, and save, **Then** the updated label is reflected in the list immediately.
3. **Given** the user has a credential associated with a provider, **When** they delete the credential, **Then** the credential is removed and any associated providers fall back to anonymous mode or show a "credentials required" prompt.
4. **Given** the user has created credentials, **When** they force-stop and reopen the app, **Then** all credentials are still present and their values remain encrypted.

---

### User Story 2 - Provider Login and Anonymous Access (Priority: P1)

Users can select a provider on the Login screen and associate it with existing credentials or choose anonymous access. Multiple providers can share the same credential. If no credentials exist for a provider that requires authentication, the user is guided to create credentials. Each provider configuration persists the chosen authentication mode.

**Why this priority**: This is the entry point for provider interaction. Users must be able to authenticate against any supported provider or explicitly choose anonymous mode.

**Independent Test**: A user selects NNMClub on the Login screen, chooses existing credentials from a dropdown, and successfully authenticates. Another user selects RuTor and checks "Anonymous" to browse without credentials.

**Acceptance Scenarios**:

1. **Given** the user is on the Login screen and has created credentials, **When** they select a provider and choose a credential from the dropdown, **Then** the provider is configured to use those credentials for all subsequent operations.
2. **Given** the user is on the Login screen for a provider that supports anonymous access, **When** they check the "Anonymous" option, **Then** all operations proceed without authentication headers.
3. **Given** the user is configuring a provider that requires authentication and no credentials exist, **When** they open the provider login screen, **Then** they see a message directing them to Settings to create credentials, with a button to navigate there.
4. **Given** the user is on the provider login screen, **When** they tap "Create New Credentials", **Then** a credential creation dialog opens, and after saving, the new credential is pre-selected in the login screen.
5. **Given** the user has associated one credential with both RuTracker and NNMClub, **When** they authenticate on RuTracker, **Then** the same credential is available for NNMClub without re-entry.

---

### User Story 3 - Unified Search Across All Providers (Priority: P1)

Users can search across all enabled providers simultaneously from a single search screen. Results stream in real-time as each provider responds, sorted according to user preference. Each result displays a provider badge indicating its source. Users can filter to search only specific providers. If no providers are selected, the search action is disabled.

**Why this priority**: Unified search is the primary value proposition of the multi-provider extension. It transforms the app from a single-tracker client into a comprehensive content discovery platform.

**Independent Test**: A user enters a search query, sees results from RuTracker, RuTor, and Internet Archive appear progressively with provider badges, sorts by seeders, and taps a result to open its detail screen.

**Acceptance Scenarios**:

1. **Given** the user is on the Search screen with all providers enabled, **When** they enter a query and submit, **Then** results from all providers appear in the list as they arrive, each showing a colored provider badge.
2. **Given** search results are loading, **When** one provider responds faster than others, **Then** the faster provider's results appear immediately without waiting for slower providers.
3. **Given** the user has selected only RuTracker and NNMClub via provider filter chips, **When** they submit a search, **Then** only results from those two providers are displayed.
4. **Given** no providers are selected in the filter, **When** the user tries to submit a search, **Then** the search button is disabled and a message prompts them to select at least one provider.
5. **Given** a provider fails or times out during search, **When** other providers succeed, **Then** successful results are shown with a subtle notification indicating that one provider could not be reached.
6. **Given** the user has set a sort order (e.g., by seeders descending), **When** new results arrive from a provider, **Then** they are inserted into the correct position in the sorted list.

---

### User Story 4 - Unified Forums and Categories (Priority: P1)

Users can browse a unified view of content hierarchies (forums, collections, bookshelves) from all providers that offer browsable structures. Each section is grouped by provider with clear source indicators. Users can enable or disable individual providers from the unified view. At least one provider must remain enabled.

**Why this priority**: Browsing is the second primary discovery mechanism after search. A unified forums view enables users to explore category structures across trackers and digital libraries in one place.

**Independent Test**: A user opens the Forums screen, sees RuTracker's forum tree, Internet Archive's collections, and Project Gutenberg's bookshelves in a unified grouped view, taps a category, and sees mixed results from enabled providers.

**Acceptance Scenarios**:

1. **Given** the user is on the Forums screen, **When** they view the unified categories, **Then** they see provider-grouped sections with expandable hierarchies for each provider that supports browsing.
2. **Given** the user taps a category in the unified view, **When** results load, **Then** items from all enabled providers that have content in that category are displayed with provider badges.
3. **Given** the user disables a provider from the Forums filter, **When** they return to the Forums screen, **Then** that provider's sections are no longer visible.
4. **Given** only one provider is enabled in Forums, **When** the user tries to disable it, **Then** the action is prevented and a message explains that at least one provider must remain enabled.
5. **Given** a provider does not support browsing (e.g., has no forum tree or collections), **When** the user views unified Forums, **Then** that provider is either hidden or shown as "No browsable content".

---

### User Story 5 - Provider Configuration and Persistence (Priority: P1)

Users can configure which providers are enabled for Search and Forums independently. All selections, filters, sort orders, and provider preferences persist across app sessions. Users can reorder providers in settings and set anonymous mode per provider.

**Why this priority**: Persistent configuration ensures a consistent user experience across sessions and allows users to customize which providers participate in each feature.

**Independent Test**: A user disables Internet Archive from Search, enables only Project Gutenberg in Forums, force-stops the app, and reopens it to find the same configuration intact.

**Acceptance Scenarios**:

1. **Given** the user has disabled NNMClub from Search providers, **When** they reopen the app, **Then** NNMClub remains disabled in Search but its Forums status is unaffected.
2. **Given** the user changes the sort order to "Size descending" and selects only HD categories, **When** they return to Search later, **Then** the same sort order and category filters are applied.
3. **Given** the user sets anonymous mode for RuTor in Provider Configuration, **When** they use RuTor in Search or Forums, **Then** no authentication is attempted.
4. **Given** the user reorders providers in Settings (e.g., moves Internet Archive to top), **When** they view Search or Forums, **Then** the provider order reflects their preference.

---

### User Story 6 - Content Download from Any Provider (Priority: P2)

Users can download content from any provider: torrent files or magnet links from trackers, and HTTP files from digital libraries. Download actions are context-aware based on provider capabilities. For HTTP providers, users can select their preferred format when multiple are available.

**Why this priority**: Download is the fulfillment action that completes the discovery workflow. It must work consistently across all provider types.

**Independent Test**: A user finds a torrent on NNMClub and downloads the .torrent file. Another user finds a book on Project Gutenberg and downloads it as EPUB.

**Acceptance Scenarios**:

1. **Given** the user taps a torrent result from a tracker provider, **When** they choose "Download .torrent", **Then** the torrent file is downloaded and saved to the device's download location.
2. **Given** the user taps a result from Project Gutenberg, **When** they choose a format (EPUB with images, EPUB without images, Kindle, HTML, or plain text), **Then** the file is downloaded in the selected format.
3. **Given** the user is not authenticated on a provider that requires authentication for downloads, **When** they attempt to download, **Then** they see an error message explaining that authentication is required, with options to log in or use anonymous mode if supported.
4. **Given** a download fails due to network error, **When** the error occurs, **Then** the user sees a clear error message with a retry option.

---

### User Story 7 - Modern UI/UX with Comprehensive Error Handling (Priority: P1)

The entire app UI follows Material Design 3 guidelines with dynamic color support, proper loading states, skeleton screens, smooth transitions, and accessible touch targets. Every error state has a clear, actionable UI with provider-specific messaging. The interface feels like a single unified service rather than a collection of separate providers.

**Why this priority**: UI/UX quality is a cross-cutting concern that affects every user interaction. Poor error handling or inconsistent design undermines trust in the multi-provider experience.

**Independent Test**: A user navigates through Search, Forums, Settings, and Login screens and observes consistent Material Design 3 styling, proper loading states, and helpful error messages when network issues occur.

**Acceptance Scenarios**:

1. **Given** the user opens any screen that loads data, **When** data is loading, **Then** a skeleton placeholder is shown instead of a blank screen or spinner-only state.
2. **Given** a network error occurs during search, **When** the error is received, **Then** a Snackbar appears with the error message and a "Retry" action.
3. **Given** an authentication error occurs, **When** the error is displayed, **Then** the user sees a dialog with options to "Update Credentials", "Switch Account", or "Use Anonymously" (if supported).
4. **Given** a provider is unavailable, **When** the user attempts to use it, **Then** the provider badge shows an "Unavailable" indicator and a cross-provider fallback suggestion appears if an alternative exists.
5. **Given** the user has enabled dark theme, **When** they view provider badges and search results, **Then** all colors maintain proper contrast ratios and provider badges remain distinguishable.
6. **Given** the user uses a screen reader, **When** they navigate the Search screen, **Then** all interactive elements have content descriptions and the provider badge is announced.

---

### Edge Cases

- **No providers enabled**: If the user disables all providers in Search, the search button is disabled with a clear message. In Forums, the last enabled provider cannot be disabled.
- **All providers fail during search**: If every provider fails or times out, the user sees a full-screen error state with individual failure reasons and retry options per provider.
- **Credential deletion while in use**: If a credential is deleted while actively associated with a provider, the provider falls back to anonymous mode (if supported) or shows a "credentials required" state.
- **Provider website changes**: If a provider's HTML structure changes (for tracker providers), the app gracefully handles parse failures for that provider without crashing or blocking other providers.
- **Rate limiting**: If a provider (e.g., Internet Archive) returns rate-limit errors, the app shows an appropriate message with estimated wait time and respects retry headers.
- **Provider timeout configuration**: If a user has not customized provider timeouts, the default 10-second timeout applies. If a user sets a very short timeout (e.g., 2 seconds) for a slow provider, the app warns that results may be incomplete but respects the user's choice.
- **Timeout inconsistency across providers**: If Provider A is set to 5 seconds and Provider B to 30 seconds, the UI clearly indicates which providers are still loading and which have timed out.
- **Offline access for Gutenberg**: If the device is offline and the user searches Project Gutenberg, results are served from the locally cached catalog if available.
- **Mixed auth requirements**: A user can be authenticated on RuTracker, anonymous on RuTor, and using API keys on Internet Archive simultaneously without conflict.
- **Provider capability mismatch**: If a user taps a UI action that the active provider does not support (e.g., "Add to Favorites" on a provider without FAVORITES capability), the action is gracefully disabled with a tooltip explaining that the feature is not available for this provider.
- **Core capability failure**: If a provider fails to implement any of the four mandatory core capabilities (SEARCH, BROWSE, TOPIC, DOWNLOAD), the provider is marked as incomplete and cannot be enabled until the gap is resolved.
- **Backup restoration mismatch**: If a user restores credentials from backup but a provider's authentication requirements have changed (e.g., now requires 2FA), the app detects the auth failure and prompts the user to update credentials.
- **Backup disabled by user**: If the user has disabled Android backup at the system level, the app shows a one-time notice explaining that credentials will not be restored on a new device, with an option to learn how to enable backup.
- **Deduplication false positive**: If two different torrents share the same title but different info-hashes, the system correctly shows them as separate results because info-hash takes precedence over title matching.
- **Deduplication metadata conflict**: If the same content appears on multiple providers with different metadata (e.g., different seeders counts, different file formats), the expanded view shows all per-provider metadata while the collapsed view shows the aggregate or preferred-provider version.
- **No deduplication key available**: If a result lacks a reliable deduplication key (no info-hash, no ISBN, ambiguous title), it is shown as a standalone result with no multi-provider badge.
- **Offline cache expiration**: If a cached result is older than 24 hours and the device is offline, the result is shown with a "stale content" warning and a note that it may be outdated.
- **Offline cache quota exceeded**: If the offline cache reaches its storage quota, the oldest entries are evicted automatically. The user is notified once with a non-intrusive message explaining that older cached content has been cleared.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Users MUST be able to create, edit, and delete credentials in Settings with three types: Username + Password, Token, and API Key.
- **FR-002**: Credential values (passwords, tokens, API keys) MUST be encrypted at rest using hardware-backed encryption.
- **FR-003**: Users MUST be able to associate one credential with multiple providers, and one provider with one active credential.
- **FR-004**: Users MUST be able to enable anonymous mode per provider, bypassing authentication for providers that support it.
- **FR-005**: The Login screen MUST present a credential selector dropdown and a "Create New Credentials" button.
- **FR-006**: If no credentials exist for a provider that requires them, the user MUST be redirected to the Credentials Management screen with contextual guidance.
- **FR-007**: Search MUST dispatch queries to all enabled providers concurrently and stream results in real-time as they arrive.
- **FR-007a**: Each provider MUST have an individually configurable timeout in Settings, defaulting to 10 seconds. If a provider exceeds its timeout, it is marked as timed out and the user is offered a manual retry for that provider only.
- **FR-008**: Search results MUST display a provider badge on each item indicating its source.
- **FR-009**: Users MUST be able to filter Search to include only specific providers via selectable filter chips.
- **FR-010**: The Search action MUST be disabled when no providers are selected, with a clear explanatory message.
- **FR-011**: Search results MUST be sorted according to user preference and updated incrementally as new results arrive.
- **FR-012**: The Forums screen MUST present a unified view of browsable content hierarchies from all enabled providers.
- **FR-013**: Users MUST be able to enable or disable individual providers for Search and Forums independently.
- **FR-014**: At least one provider MUST remain enabled in Forums at all times.
- **FR-015**: All user selections, filters, sort orders, and provider preferences MUST persist across app sessions.
- **FR-016**: Users MUST be able to download content from any provider: torrent files and magnet links from trackers, HTTP files from digital libraries.
- **FR-017**: For Project Gutenberg, users MUST be able to select their preferred download format (EPUB with/without images, Kindle, HTML, plain text).
- **FR-018**: Every error state MUST have a clear, actionable UI with retry options and provider-specific messaging.
- **FR-019**: The UI MUST follow Material Design 3 guidelines with dynamic color, proper loading skeletons, and accessible touch targets (minimum 48dp).
- **FR-020**: All interactive elements MUST have content descriptions for screen reader accessibility.
- **FR-021**: Every provider MUST implement the four core capabilities: SEARCH, BROWSE, TOPIC, and DOWNLOAD. The provider descriptor MUST declare these capabilities.
- **FR-022**: Extended capabilities (COMMENTS, FAVORITES, MAGNET_LINK, RSS, UPLOAD, USER_PROFILE) are optional per provider. A provider descriptor MUST declare only the extended capabilities it actually implements.
- **FR-023**: The UI MUST dynamically adapt to each provider's capability set — unsupported capabilities are hidden or shown as disabled with an explanatory tooltip, never as "Not implemented" stubs or crashes.
- **FR-024**: Credentials and provider configurations MUST be included in Android's automatic backup flow. When the user restores the app on a new device or after reinstall, all credentials and provider settings are restored automatically.
- **FR-025**: Unified Search MUST deduplicate identical content across providers. Torrent results are matched by info-hash (preferred) or title+size fallback. HTTP-based content is matched by canonical identifier, ISBN, or title+creator fallback.
- **FR-026**: When a deduplicated result is shown, it MUST display a multi-provider badge listing all providers that offer the same content, with the user's preferred provider highlighted.
- **FR-027**: Users MUST be able to expand a deduplicated result to see all individual provider occurrences, including per-provider metadata (seeders, file format, download URL).
- **FR-028**: The app MUST cache the 50 most recent search results and 20 most recently viewed topic details across all providers, retaining them for 24 hours.
- **FR-029**: When the device is offline, cached content MUST remain accessible with a clear "offline mode" indicator. Non-cached content shows an appropriate offline error state.
- **FR-030**: The offline cache MUST respect storage quotas and auto-evict oldest entries when the quota is reached.

### Key Entities

- **Credential**: Represents an authentication record. Attributes: unique ID, user-assigned label, type (USERNAME_PASSWORD, TOKEN, API_KEY), associated provider IDs, username, encrypted password/token/apiKey, creation and update timestamps.
- **Provider**: Represents a content source. Attributes: unique ID, display name, capabilities (search, browse, download, etc.), authentication type (none, form, captcha, OAuth, API key), encoding, enabled status for Search, enabled status for Forums.
- **ProviderConfig**: Represents per-provider user settings. Attributes: provider ID, enabled for Search, enabled for Forums, anonymous mode flag, associated credential ID, display order.
- **SearchResultBatch**: Represents a batch of results from a single provider. Attributes: provider ID, list of results, error state, timestamp, completion flag.
- **UnifiedResult**: Represents a single item in unified Search or Forums. Attributes: title, source provider, size, date, seeders/leechers (for trackers), format info, thumbnail URL, detail navigation target, deduplication key, list of matching provider occurrences.
- **DeduplicationEngine**: Represents the deduplication logic. Attributes: matching rules per provider type (torrent: info-hash primary, title+size fallback; HTTP: identifier/ISBN primary, title+creator fallback), confidence threshold, expanded/collapsed state per result.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can complete a credentials creation flow in under 60 seconds on first attempt.
- **SC-002**: Search results from the first provider appear within 3 seconds of query submission when the device has a stable connection.
- **SC-003**: Users can search across all 6 providers and receive results from at least 4 providers successfully in a single query under normal network conditions.
- **SC-004**: 100% of user selections (enabled providers, filters, sort orders, anonymous modes) persist correctly across app restarts.
- **SC-005**: Users can identify the source provider of any search result within 1 second due to clear provider badges.
- **SC-006**: All error states present a clear, actionable message with a retry path; no user encounters a blank screen or generic "something went wrong" message.
- **SC-007**: Users with screen readers can complete a full search-to-download flow without visual assistance.
- **SC-008**: The app maintains a crash-free rate above 99.5% during multi-provider concurrent operations.
- **SC-009**: Every provider capability declared in its descriptor has at least one real-stack test that verifies the corresponding user-facing flow works on a real device.
- **SC-010**: All 12 Challenge Tests (C1–C12) pass on a real Android device with real providers, and each carries a documented falsifiability rehearsal protocol.
- **SC-011**: Users can access their 10 most recent search queries and results within 2 seconds when offline, with cached content clearly marked as offline.

## Clarifications

### Session 2026-05-02

- **Q1**: How should the system handle providers that take too long to respond during a unified search? → **A**: Configurable per-provider timeout in Settings, defaulting to 10 seconds per provider. Users can adjust individual provider timeouts; if a provider exceeds its timeout, it shows an explicit timeout error with a manual retry button for that provider.
- **Q2**: Should every provider implement all capabilities, or should each provider declare only the capabilities it naturally supports? → **A**: Hybrid model. Core capabilities (SEARCH, BROWSE, TOPIC, DOWNLOAD) are mandatory for all providers. Extended capabilities (COMMENTS, FAVORITES, MAGNET_LINK, RSS, UPLOAD, USER_PROFILE) are optional per provider. The UI dynamically hides or disables unsupported capabilities.
- **Q3**: Should users be able to back up and restore their credentials, or are credentials intentionally non-recoverable outside the device? → **A**: Automatic cloud backup via Android Backup Service. Credentials are included in the standard app backup flow and restore automatically on a new device or after reinstall.
- **Q4**: Should the unified search deduplicate results that appear to be the same content from different providers? → **A**: Full deduplication. The system identifies identical content across providers and shows only one result with a multi-provider badge. The deduplication logic is provider-type-aware (torrents matched by info-hash or title+size; HTTP content matched by identifier or ISBN).
- **Q5**: Should the app cache recent search results or topic details from any provider for offline viewing? → **A**: Recent-content cache. The last 50 search results and last 20 viewed topic details are cached locally for 24 hours, available offline with a clear "offline mode" indicator.

## Assumptions

- Users have stable internet connectivity for initial provider setup and real-time search.
- The Android device supports hardware-backed encryption for credential storage.
- Users accept that credentials are included in Android's automatic cloud backup as part of the standard app backup flow.
- Project Gutenberg's catalog data is available for periodic synchronization and offline search.
- Internet Archive's Advanced Search API and Scrape API remain available and stable during the implementation period.
- NNMClub and Kinozal websites remain accessible and do not fundamentally change their HTML structure before fixtures are captured.
- Users understand that anonymous mode may restrict features (e.g., downloading .torrent files) on providers that require authentication.
- The existing RuTracker and RuTor implementations serve as the reference pattern for new tracker providers.
- The Go API service can be extended with provider-agnostic routing without breaking existing Android client compatibility during a transition period.
- TV support (Leanback launcher, D-pad navigation) is maintained but not the primary focus of UI redesign; mobile UX takes priority.
