package lava.tracker.gutenberg.model

import kotlinx.serialization.Serializable

@Serializable
data class BookList(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Book> = emptyList(),
)

@Serializable
data class Book(
    val id: Int,
    val title: String,
    val authors: List<Author> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
    val download_count: Int = 0,
    val subjects: List<String> = emptyList(),
)

@Serializable
data class Author(
    val name: String,
)
