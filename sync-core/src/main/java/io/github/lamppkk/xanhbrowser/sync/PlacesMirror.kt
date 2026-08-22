package io.github.lamppkk.xanhbrowser.sync

data class PlacesHistoryRecord(
    val url: String,
    val title: String,
    val visitedAt: Long,
    val isRemote: Boolean,
)

data class PlacesBookmarkRecord(
    val guid: String,
    val url: String,
    val title: String,
    val createdAt: Long,
)

data class PlacesMirror(
    val history: List<PlacesHistoryRecord>,
    val bookmarks: List<PlacesBookmarkRecord>,
)

object PlacesMutationPolicy {
    const val MAX_TITLE_BYTES = 4_096
    private val syncGuid = Regex("^[A-Za-z0-9_-]{12}$")

    fun isSyncGuid(value: String?): Boolean = value != null && syncGuid.matches(value)

    fun requireSyncGuid(value: String): String = value.also {
        require(isSyncGuid(it)) { "Places bookmark GUID is invalid" }
    }

    fun sanitizeTitle(value: String?, fallback: String = "Untitled"): String {
        val sanitized = sanitizeCandidate(value)
        return sanitized.ifEmpty { sanitizeCandidate(fallback).ifEmpty { "Untitled" } }
    }

    private fun sanitizeCandidate(value: String?): String {
        if (value == null) return ""
        val output = StringBuilder(minOf(value.length, MAX_TITLE_BYTES))
        var bytes = 0
        var pendingSpace = false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) {
                if (output.isNotEmpty()) pendingSpace = true
                continue
            }
            if (Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt()
            ) {
                continue
            }
            val encoded = String(Character.toChars(codePoint))
            val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size
            val separatorBytes = if (pendingSpace) 1 else 0
            if (bytes + separatorBytes + encodedBytes > MAX_TITLE_BYTES) break
            if (pendingSpace) {
                output.append(' ')
                bytes++
            }
            output.append(encoded)
            bytes += encodedBytes
            pendingSpace = false
        }
        return output.toString()
    }
}
