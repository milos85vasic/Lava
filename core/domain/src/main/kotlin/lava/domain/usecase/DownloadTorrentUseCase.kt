package lava.domain.usecase

import lava.auth.api.TokenProvider
import lava.dispatchers.api.Dispatchers
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.network.data.NetworkApiRepository
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DownloadTorrentUseCase @Inject constructor(
    private val networkApiRepository: NetworkApiRepository,
    private val downloadService: DownloadService,
    private val tokenProvider: TokenProvider,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String, title: String): String? {
        return withContext(dispatchers.default) {
            val token = tokenProvider.getToken()
            downloadService.downloadTorrentFile(
                DownloadRequest(
                    id = id,
                    title = title,
                    uri = networkApiRepository.getDownloadUri(id),
                    headers = listOf(networkApiRepository.getAuthHeader(token)),
                ),
            )
        }
    }
}
