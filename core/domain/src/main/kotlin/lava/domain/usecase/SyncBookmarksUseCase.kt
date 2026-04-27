package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.BookmarksRepository
import lava.data.api.service.ForumService
import lava.dispatchers.api.Dispatchers
import lava.notifications.NotificationService
import javax.inject.Inject

class SyncBookmarksUseCase @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    private val forumService: ForumService,
    private val notificationService: NotificationService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            bookmarksRepository.getAllBookmarks().forEach { bookmark ->
                runCatching {
                    val update = forumService.getCategoryPage(bookmark.id, 1)
                    val updateTopics = update.items.topicsIds()
                    val savedTopics = bookmarksRepository.getTopics(bookmark.id)
                    val savedNewTopics = bookmarksRepository.getNewTopics(bookmark.id)
                    val newTopics = updateTopics
                        .subtract(savedTopics.toSet())
                        .plus(savedNewTopics)
                        .distinct()

                    bookmarksRepository.update(
                        id = bookmark.id,
                        topics = updateTopics,
                        newTopics = newTopics,
                    )
                    if (!savedNewTopics.containsAll(newTopics)) {
                        notificationService.showBookmarkUpdateNotification(bookmark)
                    }
                }
            }
        }
    }
}
