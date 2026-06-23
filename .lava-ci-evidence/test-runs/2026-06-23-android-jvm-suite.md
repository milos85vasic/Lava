# JVM Unit Test Attestation — 2026-06-23

## Run metadata

- **HEAD commit:** `fa8916277035c9041d3b91c01b1b6974c19c9165`
- **Command:** `nice -n 10 ./gradlew testDebugUnitTest --continue --console=plain --max-workers=3`
- **Date:** 2026-06-23
- **Result line:** `BUILD SUCCESSFUL in 16s` (1261 actionable tasks: 5 executed, 1256 up-to-date)
- **Verdict:** ALL GREEN

---

## Fix applied before run

**File:** `app/src/test/kotlin/digital/vasic/lava/client/handoff/ApiKeyClientTest.kt`

**Root cause:** `ApiKeyClient` constructor gained an `analytics: AnalyticsTracker` parameter
(§6.AC telemetry mandate) but the test file was not updated, causing 3 Kotlin compilation
errors (`No value passed for parameter 'analytics'` at lines 59, 73, 86).

**Fix:** Added `import lava.common.analytics.AnalyticsTracker`, an inline no-op
`AnalyticsTracker` object field (`noOpAnalytics`) with a `// no-telemetry: test scope`
comment per §6.AC opt-out pattern, and passed it to all three `ApiKeyClient(...)` constructor
calls. This stubs the external telemetry boundary — it does NOT mock the SUT.
No production code was touched.

---

## Total results

| Metric   | Count |
|----------|-------|
| Test suites (XML files) | 162 |
| **Total tests** | **860** |
| **Failures** | **0** |
| **Errors** | **0** |
| Skipped  | 0 |

---

## Session-touched tests — individual verification

| Test class | Module | tests | failures | errors |
|------------|--------|-------|----------|--------|
| `AuthInterceptorHandoffKeyTest` | `core/network/impl` | 2 | 0 | 0 |
| `AuthInterceptorTest` | `core/network/impl` | 3 | 0 | 0 |
| `SearchResultViewModelStreamingTest` | `feature/search_result` | 3 | 0 | 0 |
| `SearchResultViewModelRetryTest` | `feature/search_result` | 1 | 0 | 0 |
| `ApiBackedTrackerClientTest` | `core/tracker/client` | 8 | 0 | 0 |
| `ApiBackedTrackerClientAuthFailureTest` | `core/tracker/client` | 3 | 0 | 0 |
| `ApiBackedTrackerClientStressChaosTest` | `core/tracker/client` | 6 | 0 | 0 |
| `FirebaseAnalyticsTrackerTest` | `core/analytics-firebase` | 7 | 0 | 0 |
| `EndpointConverterTest` | `core/preferences` | 17 | 0 | 0 |
| `ApiKeyClientTest` | `app` | 3 | 0 | 0 |

---

## Per-module tally (all modules)

| Module | Test class | Tests |
|--------|-----------|-------|
| `api-app` | `lava.api.app.auth.ApiKeyStoreTest` | 5 |
| `api-app` | `lava.api.app.control.ApiControlAutoStartTest` | 2 |
| `api-app` | `lava.api.app.control.ApiControlViewModelTest` | 5 |
| `api-app` | `lava.api.app.control.ApiEngineControllerTest` | 8 |
| `api-app` | `lava.api.app.control.OpenClientDecisionTest` | 4 |
| `api-app` | `lava.api.app.handoff.ApiKeyProviderRealHolderTest` | 2 |
| `api-app` | `lava.api.app.handoff.ApiKeyProviderTest` | 4 |
| `api-app` | `lava.api.app.service.MdnsAdvertiserTxtTest` | 5 |
| `api-app` | `lava.api.app.ui.ApiStatusLabelTest` | 4 |
| `app` | `digital.vasic.lava.client.crash.NavTeardownCrashReporterTest` | 6 |
| `app` | `digital.vasic.lava.client.handoff.ApiKeyClientTest` | 3 |
| `core/analytics-firebase` | `lava.analytics.firebase.FirebaseAnalyticsTrackerTest` | 7 |
| `core/analytics-firebase` | `lava.analytics.firebase.FirebaseInitializerTest` | 5 |
| `core/analytics-firebase` | `lava.analytics.firebase.FirebaseProvidesModuleTest` | 2 |
| `core/apiengine` | `lava.apiengine.ConfigSerializationTest` | 4 |
| `core/apiengine` | `lava.apiengine.FakeApiEngineTest` | 9 |
| `core/apiengine` | `lava.apiengine.NativeApiEngineContractTest` | 6 |
| `core/applink` | `lava.applink.SiblingAppLauncherTest` | 5 |
| `core/auth/impl` | `lava.auth.impl.AuthServiceImplLoginTest` | 8 |
| `core/auth/impl` | `lava.auth.impl.AuthServiceImplPersistenceTest` | 6 |
| `core/credentials` | `lava.credentials.CredentialEncryptorTest` | 7 |
| `core/credentials` | `lava.credentials.CredentialsEntryRepositoryImplTest` | 4 |
| `core/credentials` | `lava.credentials.CredentialsEntryRepositorySoftDeleteTest` | 1 |
| `core/credentials` | `lava.credentials.CredentialsRepositoryTest` | 7 |
| `core/credentials` | `lava.credentials.crypto.CredentialsCryptoTest` | 5 |
| `core/credentials` | `lava.credentials.PassphraseManagerTest` | 3 |
| `core/credentials` | `lava.credentials.ProviderCredentialBindingImplTest` | 2 |
| `core/credentials` | `lava.credentials.ProviderCredentialManagerTest` | 11 |
| `core/credentials` | `lava.credentials.session.CredentialsKeyHolderTest` | 3 |
| `core/data` | `lava.data.api.service.DiscoveredApiLabelTest` | 6 |
| `core/data` | `lava.data.converters.EndpointEntityConverterTest` | 7 |
| `core/data` | `lava.data.converters.EndpointEntityGoApiFieldsTest` | 6 |
| `core/data` | `lava.data.converters.ForumConverterTest` | 10 |
| `core/data` | `lava.data.converters.PostConvertersRemoveLastTest` | 2 |
| `core/data` | `lava.data.converters.PostConverterTest` | 18 |
| `core/data` | `lava.data.converters.SearchConverterTest` | 7 |
| `core/data` | `lava.data.converters.TopicConverterTest` | 18 |
| `core/data` | `lava.data.database.FavoriteVisitedProviderIdMigrationTest` | 3 |
| `core/data` | `lava.data.database.TopicEntityPersistenceIntegrityTest` | 6 |
| `core/data` | `lava.data.impl.repository.EndpointsRepositoryImplFilterTest` | 5 |
| `core/data` | `lava.data.impl.service.ConnectionTargetTest` | 8 |
| `core/data` | `lava.data.impl.service.LocalNetworkDiscoveryServiceTypeTest` | 5 |
| `core/data` | `lava.data.provider.ProviderCatalogRepositoryTest` | 6 |
| `core/data` | `lava.data.repository.FavoritesVisitedProviderIdRoundTripTest` | 4 |
| `core/designsystem` | `lava.designsystem.component.A11yContentDescriptionTest` | 4 |
| `core/designsystem` | `lava.designsystem.drawables.LavaIconsAppIconColorRegressionTest` | 4 |
| `core/designsystem` | `lava.designsystem.theme.PaletteContractTest` | 6 |
| `core/domain` | `lava.domain.contract.LocalNetworkDiscoveryContractTest` | 7 |
| `core/domain` | `lava.domain.model.PagingDataLoaderTest` | 4 |
| `core/domain` | `lava.domain.usecase.AddCommentUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.AddLocalFavoriteUseCaseTest` | 2 |
| `core/domain` | `lava.domain.usecase.AddRemoteFavoriteUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.ClearHistoryUseCaseTest` | 1 |
| `core/domain` | `lava.domain.usecase.CloneProviderUseCaseTest` | 1 |
| `core/domain` | `lava.domain.usecase.DiscoverLocalEndpointsUseCaseTest` | 11 |
| `core/domain` | `lava.domain.usecase.DownloadTorrentUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.EndpointSettingsUseCasesTest` | 5 |
| `core/domain` | `lava.domain.usecase.EnrichFilterUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.EnrichTopicsUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.EnrichTopicUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.EnsureForumLoadUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.GetCategoryUseCaseTest` | 2 |
| `core/domain` | `lava.domain.usecase.GetTopicUseCaseTest` | 3 |
| `core/domain` | `lava.domain.usecase.LoadFavoritesUseCaseTest` | 2 |
| `core/domain` | `lava.domain.usecase.ObserveSearchHistoryUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.ProbeMirrorUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.ProviderIdThreadingUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.RemoveClonedProviderUseCaseTest` | 2 |
| `core/domain` | `lava.domain.usecase.RepopulateProvidersOnStartupUseCaseTest` | 6 |
| `core/domain` | `lava.domain.usecase.ResolveProviderDownloadKindUseCaseTest` | 4 |
| `core/domain` | `lava.domain.usecase.VisitTopicUseCaseTest` | 2 |
| `core/domain` | `lava.testing.contract.TestInfrastructureContractTest` | 8 |
| `core/downloads` | `lava.downloads.impl.DownloadServiceCancellationTest` | 1 |
| `core/downloads` | `lava.downloads.impl.DownloadServiceImplHttpEmptyBytesTest` | 1 |
| `core/downloads` | `lava.downloads.impl.DownloadUriCacheConcurrencyTest` | 3 |
| `core/downloads` | `lava.downloads.impl.TorrentDownloadGuardTest` | 4 |
| `core/navigation` | `lava.navigation.model.NavigationArgumentTest` | 13 |
| `core/network/impl` | `lava.network.data.NetworkApiRepositoryKeyOverrideTest` | 2 |
| `core/network/impl` | `lava.network.data.NetworkLoggerRedactionTest` | 3 |
| `core/network/impl` | `lava.network.di.LanTlsContractTest` | 4 |
| `core/network/impl` | `lava.network.impl.AesGcmTest` | 7 |
| `core/network/impl` | `lava.network.impl.AuthInterceptorHandoffKeyTest` | 2 |
| `core/network/impl` | `lava.network.impl.AuthInterceptorTest` | 3 |
| `core/network/impl` | `lava.network.impl.DelegatingProxySelectorTest` | 6 |
| `core/network/impl` | `lava.network.impl.HKDFTest` | 3 |
| `core/network/impl` | `lava.network.impl.SigningCertProviderTest` | 3 |
| `core/network/impl` | `lava.network.impl.SwitchingNetworkApiAuthTest` | 5 |
| `core/network/impl` | `lava.network.impl.SwitchingNetworkApiBrowseTest` | 7 |
| `core/network/impl` | `lava.network.impl.SwitchingNetworkApiParityTest` | 16 |
| `core/network/impl` | `lava.network.impl.SwitchingNetworkApiTopicTest` | 8 |
| `core/preferences` | `lava.securestorage.EndpointConverterMalformedTest` | 6 |
| `core/preferences` | `lava.securestorage.EndpointConverterTest` | 17 |
| `core/sync` | `lava.sync.SyncOutboxImplTest` | 3 |
| `core/testing` | `lava.testing.repository.TestBookmarksRepositoryEquivalenceTest` | 4 |
| `core/testing` | `lava.testing.repository.TestEndpointsRepositoryEquivalenceTest` | 3 |
| `core/testing` | `lava.testing.repository.TestFavoritesRepositoryEquivalenceTest` | 6 |
| `core/testing` | `lava.testing.repository.TestSearchHistoryRepositoryTest` | 6 |
| `core/testing` | `lava.testing.repository.TestSuggestsRepositoryTest` | 4 |
| `core/testing` | `lava.testing.repository.TestVisitedRepositoryEquivalenceTest` | 3 |
| `core/testing` | `lava.testing.service.TestAuthServicePersistenceEquivalenceTest` | 5 |
| `core/tracker/client` | `lava.tracker.client.ApiAuthKeyEndToEndWiringTest` | 3 |
| `core/tracker/client` | `lava.tracker.client.ApiBackedTrackerClientAuthFailureTest` | 3 |
| `core/tracker/client` | `lava.tracker.client.ApiBackedTrackerClientStressChaosTest` | 6 |
| `core/tracker/client` | `lava.tracker.client.ApiBackedTrackerClientTest` | 8 |
| `core/tracker/client` | `lava.tracker.client.CapabilityHonestyContractTest` | 7 |
| `core/tracker/client` | `lava.tracker.client.CrossTrackerFallbackPolicyTest` | 6 |
| `core/tracker/client` | `lava.tracker.client.dedup.DeduplicationEngineTest` | 9 |
| `core/tracker/client` | `lava.tracker.client.DynamicRegistryRealClientTest` | 3 |
| `core/tracker/client` | `lava.tracker.client.LanHttpClientWiringContractTest` | 2 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkClonedProvidersTest` | 4 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkCloneSearchTest` | 1 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkCloneUrlInjectionTest` | 2 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkCrossTrackerFallbackTest` | 4 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkHttpDownloadRealStackTest` | 3 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkMirrorHealthTest` | 5 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkParallelSearchTest` | 4 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkRealStackTest` | 4 |
| `core/tracker/client` | `lava.tracker.client.LavaTrackerSdkTest` | 6 |
| `core/tracker/client` | `lava.tracker.client.persistence.MirrorConfigLoaderTest` | 7 |
| `core/tracker/client` | `lava.tracker.client.persistence.MirrorHealthRepositoryTest` | 7 |
| `core/tracker/client` | `lava.tracker.client.persistence.UserMirrorRepositoryTest` | 8 |
| `core/tracker/client` | `lava.tracker.client.ProviderSessionTokenEndToEndWiringTest` | 3 |
| `core/tracker/client` | `lava.tracker.client.ProviderVerifiedContractTest` | 8 |
| `core/tracker/client` | `lava.tracker.client.work.MirrorHealthCheckWorkerTest` | 5 |
| `core/work/impl` | `lava.work.impl.SyncPeriodIntervalsTest` | 3 |
| `feature/account` | `lava.account.AccountViewModelTest` | 4 |
| `feature/bookmarks` | `lava.forum.bookmarks.BookmarksViewModelTest` | 5 |
| `feature/category` | `lava.forum.category.CategoryViewModelTest` | 5 |
| `feature/connection` | `lava.connection.ConnectionsViewModelTest` | 11 |
| `feature/credentials` | `lava.feature.credentials.CredentialsViewModelTest` | 11 |
| `feature/credentials_manager` | `lava.credentials.manager.CredentialsManagerViewModelTest` | 8 |
| `feature/favorites` | `lava.favorites.FavoritesViewModelTest` | 7 |
| `feature/forum` | `lava.forum.ForumViewModelTest` | 6 |
| `feature/login` | `lava.login.LoginResultMapperTest` | 6 |
| `feature/login` | `lava.login.LoginViewModelTest` | 3 |
| `feature/login` | `lava.login.ProviderLoginAuthUiTest` | 4 |
| `feature/login` | `lava.login.ProviderLoginViewModelTest` | 13 |
| `feature/menu` | `lava.menu.apiapp.SiblingAppLauncherMenuTest` | 5 |
| `feature/menu` | `lava.menu.MenuViewModelObserveSettingsTest` | 1 |
| `feature/menu` | `lava.menu.MenuViewModelTest` | 12 |
| `feature/onboarding` | `lava.onboarding.CloudApiDefaultsTest` | 14 |
| `feature/onboarding` | `lava.onboarding.OnboardingInsetRegressionTest` | 1 |
| `feature/onboarding` | `lava.onboarding.OnboardingViewModelApiSelectionFlowTest` | 10 |
| `feature/onboarding` | `lava.onboarding.OnboardingViewModelDynamicProvidersTest` | 6 |
| `feature/onboarding` | `lava.onboarding.OnboardingViewModelTest` | 17 |
| `feature/onboarding` | `lava.onboarding.OnDeviceApiFlowTest` | 3 |
| `feature/onboarding` | `lava.onboarding.util.AuthTypeDisplayTest` | 6 |
| `feature/provider_config` | `lava.provider.config.ProviderConfigViewModelTest` | 3 |
| `feature/rating` | `lava.rating.RatingViewModelTest` | 7 |
| `feature/search` | `lava.search.SearchViewModelTest` | 10 |
| `feature/search_input` | `lava.search.input.SearchInputNavigationRoundtripTest` | 3 |
| `feature/search_input` | `lava.search.input.SearchInputViewModelTest` | 9 |
| `feature/search_result` | `lava.search.result.ApplyMultiSearchEventTest` | 6 |
| `feature/search_result` | `lava.search.result.categories.CategorySelectionViewModelTest` | 7 |
| `feature/search_result` | `lava.search.result.SearchResultNavigationProviderIdsRoundtripTest` | 3 |
| `feature/search_result` | `lava.search.result.SearchResultViewModelFallbackTest` | 3 |
| `feature/search_result` | `lava.search.result.SearchResultViewModelRetryTest` | 1 |
| `feature/search_result` | `lava.search.result.SearchResultViewModelStreamingTest` | 3 |
| `feature/topic` | `lava.topic.TopicViewModelHttpDownloadTest` | 2 |
| `feature/topic` | `lava.topic.TopicViewModelLoadContentTest` | 2 |
| `feature/topic` | `lava.topic.TopicViewModelTest` | 4 |
| `feature/visited` | `lava.visited.VisitedViewModelTest` | 7 |

---

## Verbatim result line

```
BUILD SUCCESSFUL in 16s
1261 actionable tasks: 5 executed, 1256 up-to-date
```
