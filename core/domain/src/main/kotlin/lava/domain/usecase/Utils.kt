package lava.domain.usecase

import lava.common.mapInstanceOf
import lava.models.forum.ForumItem
import lava.models.topic.Topic

internal fun List<ForumItem>.topics(): List<Topic> = mapInstanceOf(ForumItem.Topic::topic)
internal fun List<ForumItem>.topicsIds(): List<String> = topics().map(Topic::id)
