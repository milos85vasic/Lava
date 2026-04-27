package lava.data.api.service

import lava.models.Page
import lava.models.search.Filter
import lava.models.topic.Torrent

interface SearchService {
    suspend fun search(filter: Filter, page: Int): Page<Torrent>
}
