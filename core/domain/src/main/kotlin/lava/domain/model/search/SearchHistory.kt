package lava.domain.model.search

import lava.models.search.Search

data class SearchHistory(
    val pinned: List<Search>,
    val other: List<Search>,
)

fun SearchHistory.isEmpty() = pinned.isEmpty() && other.isEmpty()
