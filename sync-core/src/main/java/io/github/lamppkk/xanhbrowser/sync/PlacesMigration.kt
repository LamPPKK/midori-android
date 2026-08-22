package io.github.lamppkk.xanhbrowser.sync

import mozilla.appservices.places.BookmarkRoot
import mozilla.appservices.places.PlacesApi
import mozilla.appservices.places.uniffi.VisitObservation
import mozilla.appservices.places.uniffi.VisitType

data class LegacyBookmark(val url: String, val title: String)
data class LegacyHistoryVisit(val url: String, val title: String, val visitedAtMillis: Long)
data class MigrationCounts(val bookmarks: Int, val history: Int)

/** Idempotent importer from the one-release read-only Room compatibility DB. */
internal class PlacesMigration(private val places: PlacesApi) {
    fun import(bookmarks: List<LegacyBookmark>, history: List<LegacyHistoryVisit>): MigrationCounts {
        val writer = places.getWriter()
        var bookmarkCount = 0
        bookmarks.distinctBy(LegacyBookmark::url).forEach { bookmark ->
            if (writer.getBookmarksWithURL(bookmark.url).isEmpty()) {
                writer.createBookmarkItem(
                    BookmarkRoot.Mobile.id,
                    bookmark.url,
                    bookmark.title.ifBlank { bookmark.url },
                )
            }
            bookmarkCount++
        }
        history.distinctBy { it.url to it.visitedAtMillis }.forEach { visit ->
            val exists = writer.getVisitInfos(
                visit.visitedAtMillis,
                visit.visitedAtMillis,
                emptyList(),
            ).any { existing -> existing.url == visit.url }
            if (!exists) {
                writer.noteObservation(
                    VisitObservation(
                        url = visit.url,
                        title = PlacesMutationPolicy.sanitizeTitle(visit.title, visit.url),
                        visitType = VisitType.LINK,
                        at = visit.visitedAtMillis,
                    ),
                )
            }
        }
        return MigrationCounts(bookmarkCount, history.distinctBy { it.url to it.visitedAtMillis }.size)
    }
}
