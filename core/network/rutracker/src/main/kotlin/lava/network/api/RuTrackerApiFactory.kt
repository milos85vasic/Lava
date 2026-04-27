package lava.network.api

import io.ktor.client.HttpClient
import lava.network.domain.AddCommentUseCase
import lava.network.domain.AddFavoriteUseCase
import lava.network.domain.CheckAuthorisedUseCase
import lava.network.domain.GetCategoryPageUseCase
import lava.network.domain.GetCommentsPageUseCase
import lava.network.domain.GetCurrentProfileUseCase
import lava.network.domain.GetFavoritesUseCase
import lava.network.domain.GetForumUseCase
import lava.network.domain.GetProfileUseCase
import lava.network.domain.GetSearchPageUseCase
import lava.network.domain.GetTopicPageUseCase
import lava.network.domain.GetTopicUseCase
import lava.network.domain.GetTorrentFileUseCase
import lava.network.domain.GetTorrentUseCase
import lava.network.domain.LoginUseCase
import lava.network.domain.ParseCommentsPageUseCase
import lava.network.domain.ParseTopicPageUseCase
import lava.network.domain.ParseTorrentUseCase
import lava.network.domain.RemoveFavoriteUseCase
import lava.network.domain.VerifyAuthorisedUseCase
import lava.network.domain.VerifyTokenUseCase
import lava.network.domain.WithAuthorisedCheckUseCase
import lava.network.domain.WithFormTokenUseCase
import lava.network.domain.WithTokenVerificationUseCase
import lava.network.impl.RuTrackerInnerApiImpl
import lava.network.impl.RuTrackerNetworkApi

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
