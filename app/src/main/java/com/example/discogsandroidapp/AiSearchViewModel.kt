package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface AiSearchUiState {

    data object Idle : AiSearchUiState

    data object Loading : AiSearchUiState

    data class Success(
        val response: AiSearchResponse
    ) : AiSearchUiState

    data class Error(
        val message: String
    ) : AiSearchUiState
}

class AiSearchViewModel : ViewModel() {

    private var searchJob: Job? = null

    // Query that produced the currently displayed successful results.
    // Keep this separate from the editable text field so an edit that
    // finishes later cannot accidentally rerun newly typed text.
    private var lastResultQuery: String? = null

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState =
        MutableStateFlow<AiSearchUiState>(AiSearchUiState.Idle)

    val uiState: StateFlow<AiSearchUiState> =
        _uiState.asStateFlow()

    fun updateQuery(newQuery: String) {
        val previousQuery = _query.value

        _query.value = newQuery

        if (newQuery != previousQuery) {
            // If the seller changes the text while an AI request is running,
            // that response no longer belongs to the visible query. Cancel it
            // immediately and clear Loading so the screen cannot spin forever.
            searchJob?.cancel()
            searchJob = null

            if (_uiState.value is AiSearchUiState.Loading) {
                _uiState.value = AiSearchUiState.Idle
            }
        }
    }

    fun search() {
        val currentQuery = _query.value.trim()

        if (currentQuery.isEmpty()) {
            return
        }

        launchSearch(
            queryToRun = currentQuery,
            replaceVisibleQuery = false
        )
    }

    /**
     * Re-run the query that produced the currently displayed results.
     *
     * This is intentionally NOT based on whatever text happens to be in
     * the search field when a listing edit finishes. That prevents an edit
     * started from query A from accidentally submitting partially typed
     * query B when the network request completes.
     */
    fun rerunLastResultQuery() {
        val originalQuery =
            lastResultQuery
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return

        launchSearch(
            queryToRun = originalQuery,
            replaceVisibleQuery = true
        )
    }

    private fun launchSearch(
        queryToRun: String,
        replaceVisibleQuery: Boolean
    ) {
        if (replaceVisibleQuery) {
            _query.value = queryToRun
        }

        searchJob?.cancel()

        searchJob = viewModelScope.launch {
            _uiState.value = AiSearchUiState.Loading

            try {
                // If a prior Discogs mutation succeeded while the optional
                // AI backend/cache update failed, repair the full inventory
                // snapshot before answering another AI question.
                if (AiCacheSyncTracker.needsFullSync()) {
                    BackendRetrofitClient.apiService
                        .syncInventory()

                    AiCacheSyncTracker
                        .markFullSyncComplete()
                }

                val response =
                    BackendRetrofitClient.apiService.search(
                        AiSearchRequest(
                            query = queryToRun
                        )
                    )

                // Ignore a late response if the user has changed/cleared the
                // visible query while this request was running.
                if (_query.value.trim() == queryToRun) {
                    lastResultQuery = queryToRun
                    _uiState.value =
                        AiSearchUiState.Success(response)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_query.value.trim() == queryToRun) {
                    _uiState.value =
                        AiSearchUiState.Error(
                            e.message ?: "Search failed"
                        )
                }
            }
        }
    }

    /**
     * Immediately removes deleted Discogs listings from the currently
     * displayed AI search results without rerunning the search.
     */
    fun removeListings(listingIds: Collection<Long>) {
        if (listingIds.isEmpty()) {
            return
        }

        val currentState = _uiState.value

        if (currentState !is AiSearchUiState.Success) {
            return
        }

        val idsToRemove = listingIds.toSet()

        val updatedResults =
            currentState.response.results.filterNot { result ->
                result.listingId != null &&
                        result.listingId in idsToRemove
            }

        val removedCount =
            currentState.response.results.size - updatedResults.size

        if (removedCount == 0) {
            return
        }

        val updatedTotal =
            (
                currentState.response.totalMatches
                    ?: currentState.response.results.size
            )
                .minus(removedCount)
                .coerceAtLeast(0)

        val countSummary =
            if (
                currentState.response.truncated &&
                updatedTotal > updatedResults.size
            ) {
                "Showing ${updatedResults.size} of " +
                        "$updatedTotal matching records."
            } else {
                "Found $updatedTotal matching records."
            }

        val metadataWarning =
            if (!currentState.response.metadataComplete) {
                val missing =
                    currentState.response.missingMetadata

                " Metadata indexing is still in progress. " +
                        "$missing release(s) are not indexed yet, " +
                        "so metadata-based results may be incomplete."
            } else {
                ""
            }

        val updatedSummary =
            countSummary + metadataWarning

        val updatedResponse =
            currentState.response.copy(
                summary = updatedSummary,
                results = updatedResults,
                totalMatches = updatedTotal
            )

        _uiState.value =
            AiSearchUiState.Success(updatedResponse)
    }

    fun updateListing(
        listingId: Long,
        price: Double,
        condition: String,
        sleeveCondition: String,
        comments: String
    ) {
        val currentState = _uiState.value

        if (currentState !is AiSearchUiState.Success) {
            return
        }

        val updatedResults =
            currentState.response.results.map { result ->
                if (result.listingId == listingId) {
                    result.copy(
                        price = price,
                        condition = condition,
                        sleeveCondition = sleeveCondition,
                        comments = comments
                    )
                } else {
                    result
                }
            }

        _uiState.value =
            AiSearchUiState.Success(
                currentState.response.copy(
                    results = updatedResults
                )
            )
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _query.value = ""
        lastResultQuery = null
        _uiState.value = AiSearchUiState.Idle
    }
}
