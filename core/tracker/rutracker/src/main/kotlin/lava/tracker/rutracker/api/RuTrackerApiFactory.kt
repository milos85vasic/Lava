package lava.tracker.rutracker.api

import io.ktor.client.HttpClient
import lava.network.api.NetworkApi
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.domain.GetCurrentProfileUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.domain.GetProfileUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import lava.tracker.rutracker.domain.GetTorrentUseCase
import lava.tracker.rutracker.domain.LoginUseCase
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
import lava.tracker.rutracker.impl.RuTrackerNetworkApi

object RuTrackerApiFactory {
    fun create(httpClient: HttpClient): NetworkApi {
        val api = RuTrackerInnerApiImpl(httpClient)
        val withTokenVerification = WithTokenVerificationUseCase(VerifyTokenUseCase)
        val withAuthorisedCheck = WithAuthorisedCheckUseCase(VerifyAuthorisedUseCase)
        return RuTrackerNetworkApi(
            AddCommentUseCase(api, withTokenVerification, withAuthorisedCheck, WithFormTokenUseCase),
            AddFavoriteUseCase(api, withTokenVerification, withAuthorisedCheck, WithFormTokenUseCase),
            CheckAuthorisedUseCase(api, VerifyAuthorisedUseCase),
            GetCategoryPageUseCase(api),
            GetCommentsPageUseCase(api, ParseCommentsPageUseCase),
            GetFavoritesUseCase(api, withTokenVerification, withAuthorisedCheck),
            GetForumUseCase(api),
            GetSearchPageUseCase(api, withTokenVerification, withAuthorisedCheck),
            GetTopicUseCase(api, ParseTorrentUseCase, ParseCommentsPageUseCase),
            GetTopicPageUseCase(api, ParseTopicPageUseCase),
            GetTorrentFileUseCase(api, withTokenVerification),
            GetTorrentUseCase(api, ParseTorrentUseCase),
            LoginUseCase(api, GetCurrentProfileUseCase(api, GetProfileUseCase(api))),
            RemoveFavoriteUseCase(api, withTokenVerification, withAuthorisedCheck, WithFormTokenUseCase),
        )
    }
}
