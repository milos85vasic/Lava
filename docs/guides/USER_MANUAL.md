# Lava Client — User Manual

> A task-by-task guide to the Lava Android client, derived from the actual
> feature modules and screens in the repository. UI labels quoted below are
> taken from the real Compose screens / string resources (source files named
> per section). Anything not verifiable from the repo is marked
> `UNCONFIRMED:`.
>
> `Classification:` project-specific.

## What Lava is

Lava is an unofficial Android client for popular Russian torrent trackers.
It ships with a pluggable Tracker SDK that supports multiple content
providers (see [Supported providers](#supported-providers)). You search and
browse trackers, view topic detail and comments, download `.torrent` files or
open magnet links, and manage favorites, bookmarks, and history — all from one
app. (Source: [`README.md`](../../README.md).)

A companion **Lava API** service does the actual scraping and exposes a JSON
API to the app. You can connect the client to a Lava API on your local network,
a cloud/remote server, or one running **on this very device** — see
[On-device API](#on-device-api).

## Supported providers

Six providers are wired through the Tracker SDK. Verified from each provider's
`TrackerDescriptor` under `core/tracker/<id>/`:

| Provider (display name) | Tracker id | Auth type | Notes |
|---|---|---|---|
| RuTracker.org | `rutracker` | `CAPTCHA_LOGIN` | Login + captcha (full capability set incl. forum, favorites, upload) |
| RuTor.info | `rutor` | `FORM_LOGIN` | Anonymous by default |
| Internet Archive | `archiveorg` | `NONE` | Anonymous — no credentials needed |
| Project Gutenberg | `gutenberg` | `NONE` | Anonymous — no credentials needed |
| Kinozal.tv | `kinozal` | `FORM_LOGIN` | Credentials |
| NNM-Club | `nnmclub` | `FORM_LOGIN` | Credentials |

Auth types come from `AuthType` (`core/tracker/api/.../AuthType.kt`):
`NONE`, `FORM_LOGIN`, `CAPTCHA_LOGIN`, `OAUTH`, `API_KEY`. A `NONE` provider is
always anonymous; a `FORM_LOGIN`/`CAPTCHA_LOGIN` provider that
`supportsAnonymous` shows an anonymous toggle, otherwise it requires
credentials (`TrackerDescriptor.kt` KDoc table).

> `UNCONFIRMED:` the `README.md` "Supported trackers" table lists only
> RuTracker + RuTor (it dates to 1.2.0). The six descriptors above are present
> in the source tree today; the README table is stale relative to them.

## Install / setup

Lava is distributed to testers via **Firebase App Distribution** (the
project's distribution channel per the §6.P / §6.Z release process and the
distribute logs under `.lava-ci-evidence/distribute-changelog/`). You receive a
Firebase invitation, accept it, and install the APK from the link.

> `UNCONFIRMED:` `README.md` also shows Google Play / GitHub / RuStore badges
> for the client app. The active, repo-verified distribution path is Firebase
> App Distribution; whether the public store listings are live is not
> determinable from the repository.

The client comes in two variants you can install side by side: a dev/debug
build with the `.dev` application-id suffix and a release build. (Source: root
`CLAUDE.md` commands section.)

### On-device API install (no Play Store)

If you choose to run the API **on this device**, the client links to the
separate **Lava API app**. From onboarding's "On this device" section (or
**Settings → Run the API on this device**), the button reads **"Install Lava
API app"** when it is not installed and **"Open Lava API app"** when it is
(source:
[`feature/onboarding/.../steps/ApiSelectionStep.kt`](../../feature/onboarding/src/main/kotlin/lava/onboarding/steps/ApiSelectionStep.kt),
lines 204-210). When not installed, the client uses a **Firebase download
fallback — there are no dead Play-Store links** (source:
[`docs/CONTINUATION.md`](../CONTINUATION.md) linking-feature entry, 2026-06-04).

## Onboarding

The onboarding flow is a stepped wizard (source:
[`feature/onboarding/.../OnboardingScreen.kt`](../../feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingScreen.kt),
steps `Welcome → ApiSelection → Providers → Configure → Summary`).

1. **Welcome** — brand logo, the heading **"Welcome to Lava"**, a
   "*N* providers available" line, and a **"Get Started"** button. (Source:
   `WelcomeStep.kt`.)
2. **Choose your API** (`ApiSelectionStep`) — heading **"Choose your API"**
   with three sections (source: `ApiSelectionStep.kt`):
   - **On your network** — auto-discovers Lava APIs over mDNS. Shows
     "Searching for APIs on your network…" while scanning, then "Found *N*
     API(s):" or "No APIs discovered on your network." Tap a discovered entry
     to run a connectivity probe; on success onboarding advances, on failure it
     shows "Could not reach this API: …" with a **"Try again"** button. A
     **"Search again"** button rescans.
   - **On this device** — **"Install Lava API app"** / **"Open Lava API app"**
     (see above).
   - **Cloud / remote server** — pick a preset or type a server address into the
     **"Server address (https://host:port)"** field and tap **"Add server"**.
3. **Pick your providers** (`ProvidersStep`) — heading **"Pick your providers"**,
   "Select one or more content providers to configure." Each provider row shows
   a colored dot, its display name, its auth type (e.g. "Form Login"), and a
   checkbox. The **"Next"** button is enabled once at least one is selected.
   (Source: `ProvidersStep.kt`.)
4. **Configure** — per-provider configuration (credentials / anonymous toggle).
   See [Provider configuration](#provider-configuration).
5. **Summary** — review and finish.

## Provider configuration

Source:
[`feature/provider_config/.../ProviderConfigScreen.kt`](../../feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigScreen.kt).

The provider-config screen composes sections including a **Sync** section and —
only when `descriptor.supportsAnonymous == true` — an **Anonymous** section
toggle. Credentials for login-based providers are entered through the
credentials / login screens (see [Login](#login)).

## Search

Source:
[`feature/search_input/.../SearchInputScreen.kt`](../../feature/search_input/src/main/kotlin/lava/search/input/SearchInputScreen.kt),
[`feature/search_result/.../SearchResultScreen.kt`](../../feature/search_result/src/main/kotlin/lava/search/result/SearchResultScreen.kt).

1. Open the search input. It shows a search field (the hint comes from the
   designsystem `designsystem_hint_search` string) with a clear (×) action.
2. A **provider chip bar** (`ProviderChipBar`) lets you toggle which configured
   providers the search runs against. (Source: `SearchInputScreen.kt`
   lines 92-94.)
3. Submit the query to see results in `SearchResultScreen`. Results carry a
   **filter** affordance and a category row that starts with **"All"** followed
   by per-category chips. (Source: `SearchResultScreen.kt`.)
4. If a provider requires login for search, instead of a misleading "Nothing
   found" the results show a **Login required** empty-state with a **Login**
   button (SP-3.2 change; source comment in `SearchResultScreen.kt` lines
   280-293).

## Browse / categories

Source:
[`feature/category/.../CategoryScreen.kt`](../../feature/category/src/main/kotlin/lava/forum/category/CategoryScreen.kt),
[`feature/forum/.../ForumScreen.kt`](../../feature/forum/src/main/kotlin/lava/forum/ForumScreen.kt),
[`feature/search_result/.../categories/CategorySelectionScreen.kt`](../../feature/search_result/src/main/kotlin/lava/search/result/categories/CategorySelectionScreen.kt).

Browse the tracker's category / forum tree. Category and forum entries render
by name. If a forum requires login to browse/search, a **"Login required"**
dialog appears with **Login** / cancel actions (source: `CategoryScreen.kt`
lines 258-271).

## View a topic + comments + download

Source:
[`feature/topic/.../TopicScreen.kt`](../../feature/topic/src/main/kotlin/lava/topic/TopicScreen.kt)
and `feature/topic/src/main/res/values/strings.xml`.

The topic screen shows the topic title, poster image, and content, with a
share action and a paginated comments list. Verified actions:

- **Magnet** button (`topic_action_magnet` = "Magnet") — opens a magnet dialog
  with share / open / cancel actions. Shown only when the topic carries a
  non-blank magnet link.
- **Torrent** button (`topic_action_torrent` = "Torrent") — downloads the
  `.torrent` file. If you are not authorized, a **"Login required"** dialog
  appears: *"To download the torrent file you need to be authorized. If you do
  not have an account you can use a magnet link."* (`topics_login_required_for_download`).
- **Download** requires storage permission; if not granted, a rationale dialog
  **"Grant access to storage?"** is shown (`permission_write_storage_rationale_title`).
- **Add a comment** — opens an "Add a comment" dialog with a "Write your
  message…" placeholder and send / cancel actions
  (`topic_add_comment_title` / `topic_add_comment_placeholder`). Writing
  comments requires authorization.
- A favorite toggle is available on the topic.

## Login

Source:
[`feature/login/.../LoginScreen.kt`](../../feature/login/src/main/kotlin/lava/login/LoginScreen.kt),
[`feature/login/.../ProviderLoginScreen.kt`](../../feature/login/src/main/kotlin/lava/login/ProviderLoginScreen.kt).

For providers with `FORM_LOGIN` or `CAPTCHA_LOGIN` auth, the login screens let
you enter credentials (and, for RuTracker, solve a captcha). Anonymous-capable
providers can be used without credentials. Credentials are stored securely and
never logged (per §6.H — see [`docs/security/README.md`](../security/README.md)).

> `UNCONFIRMED:` exact on-screen field labels for the login screens were not
> read line-by-line in this pass; the behavioral contract (form login vs.
> captcha vs. anonymous) is derived from the descriptors and `AuthType`.

## Favorites, bookmarks, history

Sources:
[`feature/favorites/.../FavoritesScreen.kt`](../../feature/favorites/src/main/kotlin/lava/favorites/FavoritesScreen.kt),
[`feature/bookmarks/.../BookmarksScreen.kt`](../../feature/bookmarks/src/main/kotlin/lava/forum/bookmarks/BookmarksScreen.kt),
`feature/visited/`.

- **Favorites** — topics you have favorited (toggled from the topic screen).
- **Bookmarks** — bookmarked forums/categories.
- **History (visited)** — recently visited items.

Each of these has its own periodic **sync** setting in the menu (see below),
with configurable sync periods.

## Settings menu

Source:
[`feature/menu/.../MenuScreen.kt`](../../feature/menu/src/main/kotlin/lava/menu/MenuScreen.kt)
+ `feature/menu/src/main/res/values/strings.xml`. Verified rows include:

- **Settings** section: **Theme** selector; the connection/endpoint selection
  item; **"Run the API on this device"** row; **"Provider Credentials"**;
  per-feature sync periods (Favorites / Bookmarks / History / Credentials).
- **Data** section: clear history, clear bookmarks, clear favorites.
- **Misc** section: rights, privacy, contacts, **About**.
- A **Sign out** confirmation flow.

## On-device API

The client can connect to a Lava API running on your own phone/tablet. See the
dedicated guide:
[`docs/guides/ON_DEVICE_API_USER_GUIDE.md`](ON_DEVICE_API_USER_GUIDE.md)
(start/stop/restart, the access key/pairing, mDNS discovery, the persistent
notification, privacy notes, and its own troubleshooting table). Not duplicated
here.

## TV support

Lava ships a `TvActivity` (leanback) entry point alongside the phone/tablet
`MainActivity` (source: `app/src/main/AndroidManifest.xml`, `<activity
android:name=".TvActivity">` with `LEANBACK_LAUNCHER`). The manifest declares
`android.software.leanback` and `android.hardware.touchscreen` as **not
required**, so the app installs on TV-class devices.

## Deep links

The manifest registers `rutracker.org` deep links for `viewtopic.php`,
`viewforum.php`, and `tracker.php` (both `http` and `https`), so tapping a
RuTracker URL can open it directly in Lava (source:
`app/src/main/AndroidManifest.xml` `MainActivity` intent filter).

## Troubleshooting

| Symptom | What to check |
|---|---|
| Onboarding can't find an API ("No APIs discovered") | Ensure a Lava API is running on the same Wi-Fi and your router allows multicast; tap **"Search again"**. Or use **Cloud / remote server** (enter `https://host:port`) or **On this device**. (Source: `ApiSelectionStep.kt`.) |
| "Could not reach this API" after selecting one | The connectivity probe failed; tap **"Try again"** or pick a different API. |
| Search shows **Login required** | The selected provider needs credentials for search/download — tap **Login** and sign in (RuTracker also needs a captcha). |
| `.torrent` download blocked / "Login required" | Authorize the provider, or use the **Magnet** link instead (RuTracker download requires login). |
| "Grant access to storage?" before download | Grant the storage permission so the `.torrent` can be written to your download folder. |
| Provider missing from the search chip bar | Configure/enable it in onboarding or **Provider Credentials**; only configured providers appear. |
| Cloud API returns 401 | The cloud server must register your client build's identity. (Source: `docs/CONTINUATION.md` "cloud-search auth lag" entry — a known operator-side step.) |

## See also

- Security posture: [`docs/security/README.md`](../security/README.md)
- On-device API user guide: [`docs/guides/ON_DEVICE_API_USER_GUIDE.md`](ON_DEVICE_API_USER_GUIDE.md)
- SDK developer guide (adding a provider): [`docs/sdk-developer-guide.md`](../sdk-developer-guide.md)
- Architecture: [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)
- Local network discovery: [`docs/LOCAL_NETWORK_DISCOVERY.md`](../LOCAL_NETWORK_DISCOVERY.md)
