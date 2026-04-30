package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
enum class SortField { DATE, SEEDERS, LEECHERS, SIZE, RELEVANCE, TITLE }
