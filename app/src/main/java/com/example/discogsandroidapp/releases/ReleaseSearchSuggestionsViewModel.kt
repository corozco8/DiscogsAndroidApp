package com.example.discogsandroidapp.releases

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.discogsandroidapp.data.SearchResult
import com.example.discogsandroidapp.data.DiscogsSearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ReleaseSuggestionsState(
    val query: String = "", val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(), val error: String? = null
)

internal class ReleaseSearchSuggestionsViewModel @JvmOverloads constructor(
    application: Application,
    private val cachedResults: (String, String) -> DiscogsSearchResponse? = ReleaseSearchRepository::peek,
    private val searchReleases: suspend (String, String) -> DiscogsSearchResponse = ReleaseSearchRepository::search
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ReleaseSuggestionsState())
    val state = mutableState.asStateFlow()
    val history = ReleaseSearchHistory.entries.asStateFlow()
    private var job: Job? = null
    private var requestGeneration = 0
    private var requestToken: String? = null
    init { viewModelScope.launch { ReleaseSearchHistory.initialize(application) } }

    fun update(query: String, active: Boolean, token: String) {
        // Enter clears focus while the submitted search joins this same request.
        // Keep a request already on the wire so the cache can serve both consumers.
        if (job?.isActive == true && mutableState.value.loading &&
            mutableState.value.query == query && requestToken == token) return
        job?.cancel()
        requestToken = token
        val generation = ++requestGeneration
        mutableState.value = ReleaseSuggestionsState(query)
        if (!active || normalizedSearchQuery(query).length < 2) return
        cachedResults(query, token)?.let { response ->
            mutableState.value = ReleaseSuggestionsState(query, results = response.suggestionResults())
            return
        }
        job = viewModelScope.launch {
            delay(650)
            mutableState.value = ReleaseSuggestionsState(query, loading = true)
            try {
                val response = searchReleases(query, token)
                if (generation == requestGeneration) mutableState.value = ReleaseSuggestionsState(
                    query, results = response.suggestionResults()
                )
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == requestGeneration) mutableState.value = ReleaseSuggestionsState(
                    query, error = "Suggestions unavailable. You can still submit your search."
                )
            }
        }
    }
    private fun DiscogsSearchResponse.suggestionResults() = results.filter { it.type == "release" }.distinctBy { it.id }.take(8)
    fun selected(result: SearchResult) = viewModelScope.launch {
        ReleaseSearchHistory.remember(getApplication(), release = result)
    }
    fun clearHistory() = viewModelScope.launch { ReleaseSearchHistory.clear(getApplication()) }
}
