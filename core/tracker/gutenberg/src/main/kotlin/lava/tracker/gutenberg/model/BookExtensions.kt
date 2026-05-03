package lava.tracker.gutenberg.model

import lava.tracker.api.model.TorrentItem

internal fun Book.toTorrentItem(): TorrentItem {
    val creator = authors.firstOrNull()?.name ?: ""
    return TorrentItem(
        trackerId = "gutenberg",
        torrentId = id.toString(),
        title = title,
        category = subjects.firstOrNull(),
        downloadUrl = pickBestDownloadUrl(formats),
        metadata = mapOf(
            "creator" to creator,
            "format" to bestFormatLabel(formats),
            "downloads" to download_count.toString(),
        ),
    )
}

internal fun pickBestDownloadUrl(formats: Map<String, String>): String? {
    val preferred = listOf(
        "application/epub+zip",
        "text/plain",
        "text/html",
    )
    for (mime in preferred) {
        formats[mime]?.let { return it }
    }
    return formats.values.firstOrNull()
}

internal fun bestFormatLabel(formats: Map<String, String>): String {
    return when {
        "application/epub+zip" in formats -> "EPUB"
        "text/plain" in formats -> "Text"
        "text/html" in formats -> "HTML"
        else -> "Unknown"
    }
}

internal fun estimateTotalPages(count: Int): Int {
    if (count <= 0) return 1
    val pages = count / 32 + if (count % 32 > 0) 1 else 0
    return pages.coerceAtLeast(1)
}
