package io.github.lamppkk.xanhbrowser.sync

data class PlacesHistoryRecord(
    val url: String,
    val title: String,
    val visitedAt: Long,
)

data class PlacesBookmarkRecord(
    val url: String,
    val title: String,
    val createdAt: Long,
)

data class PlacesMirror(
    val history: List<PlacesHistoryRecord>,
    val bookmarks: List<PlacesBookmarkRecord>,
)
