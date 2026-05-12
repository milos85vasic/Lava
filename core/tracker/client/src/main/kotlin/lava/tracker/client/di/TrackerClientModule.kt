package lava.tracker.client.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import lava.auth.api.TokenProvider
import lava.tracker.archiveorg.ArchiveOrgClientFactory
import lava.tracker.gutenberg.GutenbergClientFactory
import lava.tracker.kinozal.KinozalClientFactory
import lava.tracker.nnmclub.NnmclubClientFactory
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerRegistry
import lava.tracker.rutor.RuTorClientFactory
import lava.tracker.rutracker.RuTrackerClientFactory
import lava.tracker.rutracker.api.RuTrackerInnerApi
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.domain.GetCurrentProfileUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.domain.GetMagnetLinkUseCase
import lava.tracker.rutracker.domain.GetProfileUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.LogoutUseCase
import lava.tracker.rutracker.domain.ParseCommentsPageUseCase
import lava.tracker.rutracker.domain.ParseTopicPageUseCase
import lava.tracker.rutracker.domain.ParseTorrentUseCase
import lava.tracker.rutracker.domain.RemoveFavoriteUseCase
import lava.tracker.rutracker.domain.VerifyAuthorisedUseCase
import lava.tracker.rutracker.domain.VerifyTokenUseCase
import lava.tracker.rutracker.domain.WithAuthorisedCheckUseCase
import lava.tracker.rutracker.domain.WithFormTokenUseCase
import lava.tracker.rutracker.domain.WithTokenVerificationUseCase
import lava.tracker.rutracker.impl.RuTrackerInnerApiImpl
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt graph for the new SP-3a tracker SDK path.
 *
 * Topology (Section F, Task 2.29):
 *   - HttpClient pinned to `https://rutracker.org/forum/`. The legacy per-endpoint
 *     switching path in `:core:network:impl` (`NetworkApiRepositoryImpl.rutrackerApi`)
 *     is intentionally NOT replicated here — Section G's SwitchingNetworkApi rewire
 *     is the bridge between the legacy endpoint switcher and this graph.
 *   - [RuTrackerInnerApi] constructed directly via [RuTrackerInnerApiImpl] (visibility
 *     bumped from `internal` to public in the same task; see KDoc on the class).
 *   - 14 feature-public UseCases plus their helper UseCases (`WithToken*`,
 *     `WithAuthorised*`). Helpers like `VerifyTokenUseCase`, `VerifyAuthorisedUseCase`,
 *     `WithFormTokenUseCase`, and the three Parse*UseCase singletons are Kotlin
 *     `object`s — they're referenced directly, not provided.
 *   - [TrackerRegistry] populated with [RuTrackerClientFactory]. Each call to
 *     `registry.get("rutracker", config)` returns the same singleton client because
 *     Hilt scopes [lava.tracker.rutracker.RuTrackerClient] as @Singleton transitively.
 *
 * Mappers, feature classes, [lava.tracker.rutracker.RuTrackerClient], and
 * [RuTrackerClientFactory] all carry `@Inject` constructors — Hilt processes them
 * automatically and they are NOT @Provides'd here.
 */
@Module
@InstallIn(SingletonComponent::class)
object TrackerClientModule {

    @Provides
    @Singleton
    @Named(RUTRACKER_HTTP_CLIENT)
    fun provideRuTrackerHttpClient(): HttpClient = HttpClient(OkHttp) {
        defaultRequest { url("https://rutracker.org/forum/") }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        install(UserAgent) {
            agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile"
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    android.util.Log.i("RuTrackerHttp", message)
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideRuTrackerInnerApi(
        @Named(RUTRACKER_HTTP_CLIENT) httpClient: HttpClient,
    ): RuTrackerInnerApi = RuTrackerInnerApiImpl(httpClient)

    // -----------------------------------------------------------------
    // Token-verification helpers (the three Verify*/WithFormToken classes
    // are Kotlin `object`s — not provided; referenced inline below).
    // -----------------------------------------------------------------

    @Provides
    @Singleton
    fun provideWithTokenVerificationUseCase(): WithTokenVerificationUseCase =
        WithTokenVerificationUseCase(VerifyTokenUseCase)

    @Provides
    @Singleton
    fun provideWithAuthorisedCheckUseCase(): WithAuthorisedCheckUseCase =
        WithAuthorisedCheckUseCase(VerifyAuthorisedUseCase)

    // -----------------------------------------------------------------
    // Feature-public UseCases (signatures verified against domain/*.kt).
    // -----------------------------------------------------------------

    @Provides
    @Singleton
    fun provideGetSearchPageUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
        withAuth: WithAuthorisedCheckUseCase,
    ): GetSearchPageUseCase = GetSearchPageUseCase(api, withToken, withAuth)

    @Provides
    @Singleton
    fun provideGetForumUseCase(api: RuTrackerInnerApi): GetForumUseCase =
        GetForumUseCase(api)

    @Provides
    @Singleton
    fun provideGetCategoryPageUseCase(api: RuTrackerInnerApi): GetCategoryPageUseCase =
        GetCategoryPageUseCase(api)

    @Provides
    @Singleton
    fun provideGetTopicUseCase(api: RuTrackerInnerApi): GetTopicUseCase =
        GetTopicUseCase(api, ParseTorrentUseCase, ParseCommentsPageUseCase)

    @Provides
    @Singleton
    fun provideGetTopicPageUseCase(api: RuTrackerInnerApi): GetTopicPageUseCase =
        GetTopicPageUseCase(api, ParseTopicPageUseCase)

    @Provides
    @Singleton
    fun provideGetCommentsPageUseCase(api: RuTrackerInnerApi): GetCommentsPageUseCase =
        GetCommentsPageUseCase(api, ParseCommentsPageUseCase)

    @Provides
    @Singleton
    fun provideAddCommentUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
        withAuth: WithAuthorisedCheckUseCase,
    ): AddCommentUseCase = AddCommentUseCase(api, withToken, withAuth, WithFormTokenUseCase)

    @Provides
    @Singleton
    fun provideGetFavoritesUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
        withAuth: WithAuthorisedCheckUseCase,
    ): GetFavoritesUseCase = GetFavoritesUseCase(api, withToken, withAuth)

    @Provides
    @Singleton
    fun provideAddFavoriteUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
        withAuth: WithAuthorisedCheckUseCase,
    ): AddFavoriteUseCase = AddFavoriteUseCase(api, withToken, withAuth, WithFormTokenUseCase)

    @Provides
    @Singleton
    fun provideRemoveFavoriteUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
        withAuth: WithAuthorisedCheckUseCase,
    ): RemoveFavoriteUseCase = RemoveFavoriteUseCase(api, withToken, withAuth, WithFormTokenUseCase)

    @Provides
    @Singleton
    fun provideGetProfileUseCase(api: RuTrackerInnerApi): GetProfileUseCase =
        GetProfileUseCase(api)

    @Provides
    @Singleton
    fun provideGetCurrentProfileUseCase(
        api: RuTrackerInnerApi,
        getProfile: GetProfileUseCase,
    ): GetCurrentProfileUseCase = GetCurrentProfileUseCase(api, getProfile)

    @Provides
    @Singleton
    fun provideLoginUseCase(
        api: RuTrackerInnerApi,
        getCurrentProfile: GetCurrentProfileUseCase,
    ): LoginUseCase = LoginUseCase(api, getCurrentProfile)

    @Provides
    @Singleton
    fun provideLogoutUseCase(tokenProvider: TokenProvider): LogoutUseCase =
        LogoutUseCase(tokenProvider)

    @Provides
    @Singleton
    fun provideCheckAuthorisedUseCase(api: RuTrackerInnerApi): CheckAuthorisedUseCase =
        CheckAuthorisedUseCase(api, VerifyAuthorisedUseCase)

    @Provides
    @Singleton
    fun provideGetTorrentFileUseCase(
        api: RuTrackerInnerApi,
        withToken: WithTokenVerificationUseCase,
    ): GetTorrentFileUseCase = GetTorrentFileUseCase(api, withToken)

    @Provides
    @Singleton
    fun provideGetMagnetLinkUseCase(): GetMagnetLinkUseCase = GetMagnetLinkUseCase()

    // -----------------------------------------------------------------
    // Registry — populated with both the RuTracker and RuTor factories
    // (SP-3a Task 3.40, Section J). The RuTor plugin's @Inject-constructor
    // chain (RuTorClient → RuTorSearch/Browse/Topic/Comments/Auth/Download
    // → RuTorHttpClient + parsers) is processed transitively by Hilt — no
    // explicit @Provides for any RuTor class is necessary because every
    // constructor in that subgraph is @Inject-annotated.
    // -----------------------------------------------------------------

    @Provides
    @Singleton
    fun provideTrackerRegistry(
        rutrackerFactory: RuTrackerClientFactory,
        rutorFactory: RuTorClientFactory,
        nnmclubFactory: NnmclubClientFactory,
        kinozalFactory: KinozalClientFactory,
        archiveOrgFactory: ArchiveOrgClientFactory,
        gutenbergFactory: GutenbergClientFactory,
    ): TrackerRegistry = DefaultTrackerRegistry().apply {
        register(rutrackerFactory)
        register(rutorFactory)
        register(nnmclubFactory)
        register(kinozalFactory)
        register(archiveOrgFactory)
        register(gutenbergFactory)
    }

    private const val RUTRACKER_HTTP_CLIENT = "rutracker"
}
