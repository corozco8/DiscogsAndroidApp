package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.DiscogsSearchResponse
import com.example.discogsandroidapp.data.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun historyDeduplicatesQueriesAndKeepsTheLatestSpelling() {
        val old = listOf(ReleaseSearchHistoryEntry("Rumours"), ReleaseSearchHistoryEntry("Alice"))
        val updated = updatedSearchHistory(old, ReleaseSearchHistoryEntry(" rumours "))
        assertEquals(listOf("rumours", "Alice"), updated.map { it.query })
    }
    @Test fun historyDeduplicatesReleasesByIdAndKeepsDifferentPressings() {
        val release = SearchResult(1, "Fleetwood Mac - Rumours")
        val old = listOf(ReleaseSearchHistoryEntry(release = release),
            ReleaseSearchHistoryEntry(release = release.copy(id = 2)))
        val updated = updatedSearchHistory(old, ReleaseSearchHistoryEntry(release = release.copy(year = "1977")))
        assertEquals(listOf(1, 2), updated.map { it.release!!.id })
        assertEquals("1977", updated.first().release!!.year)
    }
    @Test fun blankHistoryEntriesAreIgnoredAndHistoryIsBounded() {
        val history = (1..25).map { ReleaseSearchHistoryEntry("query $it") }
        assertEquals(history, updatedSearchHistory(history, ReleaseSearchHistoryEntry(" ")))
        assertEquals(20, updatedSearchHistory(history, ReleaseSearchHistoryEntry("Newest")).size)
    }
    @Test fun labelsKeepAlbumArtistYearAndEdition() {
        val label = SearchResult(1, "Fleetwood Mac - Rumours", year = "1977",
            format = listOf("Vinyl", "LP", "Album", "Reissue")).suggestionText()
        assertEquals("Rumours", label.title)
        assertEquals("Fleetwood Mac", label.artist)
        assertEquals("1977 · Vinyl · LP · Album · RE", label.edition)
    }
    @Test fun titleHyphensAndUnknownYearsAreHandled() {
        val label = SearchResult(1, "Artist - Title - Part Two", year = "0").suggestionText()
        assertEquals("Title - Part Two", label.title)
        assertEquals("Year unavailable", label.edition)
        assertEquals("Solo Title", SearchResult(2, "Solo Title").suggestionText().title)
    }
    @Test fun submittingASuggestionQueryReusesTheSameRequest() = runBlocking {
        var reads = 0
        val cache = ReleaseSearchCache()
        val response = DiscogsSearchResponse(listOf(SearchResult(1, "Artist - Rumours")))
        assertEquals(response, cache.search("Rumours", "account") { reads++; response })
        assertEquals(response, cache.search(" rumours ", "account") { reads++; response })
        assertEquals(1, reads)
    }
    @Test fun simultaneousConsumersShareOneSearch() = runBlocking {
        val cache = ReleaseSearchCache()
        var reads = 0
        val response = DiscogsSearchResponse(emptyList())
        val first = async { cache.search("Rumours", "account") { reads++; delay(20); response } }
        val second = async { cache.search("Rumours", "account") { reads++; response } }
        first.await(); second.await()
        assertEquals(1, reads)
    }
    @Test fun expiredOrDifferentAccountResultsRequireANewRequest() = runBlocking {
        var clock = 0L
        val cache = ReleaseSearchCache { clock }
        val response = DiscogsSearchResponse(emptyList())
        var reads = 0
        cache.search("Rumours", "one") { reads++; response }
        cache.search("Rumours", "two") { reads++; response }
        clock = 180_000
        cache.search("Rumours", "one") { reads++; response }
        assertEquals(3, reads)
    }
    @Test fun failedReadsAreNotCached() = runBlocking {
        val cache = ReleaseSearchCache()
        try { cache.search("Rumours", "one") { error("offline") } } catch (_: IllegalStateException) { }
        val response = DiscogsSearchResponse(listOf(SearchResult(5, "Artist - Rumours")))
        assertEquals(response, cache.search("Rumours", "one") { response })
    }
}
