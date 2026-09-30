package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.SearchResult
import kotlinx.serialization.Serializable
import java.util.Locale

internal fun normalizedSearchQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

@Serializable
internal data class ReleaseSearchHistoryEntry(val query: String = "", val release: SearchResult? = null)

internal fun updatedSearchHistory(
    history: List<ReleaseSearchHistoryEntry>, entry: ReleaseSearchHistoryEntry
): List<ReleaseSearchHistoryEntry> {
    if (entry.release == null && entry.query.isBlank()) return history
    return (listOf(entry.copy(query = entry.query.trim())) + history.filterNot {
        if (entry.release != null) it.release?.id == entry.release.id
        else it.release == null && normalizedSearchQuery(it.query) == normalizedSearchQuery(entry.query)
    }).take(20)
}

internal data class ReleaseSuggestionText(val title: String, val artist: String, val edition: String)

internal fun SearchResult.suggestionText(): ReleaseSuggestionText {
    val separator = title.indexOf(" - ")
    val artist = if (separator >= 0) title.substring(0, separator).trim() else "Artist unavailable"
    val album = if (separator >= 0) title.substring(separator + 3).trim() else title
    val formats = format.orEmpty().map { if (it.equals("Reissue", true)) "RE" else it }.distinct()
    val edition = (listOf(year.takeIf { it.isNotBlank() && it != "0" } ?: "Year unavailable") + formats).joinToString(" · ")
    return ReleaseSuggestionText(album, artist, edition)
}
