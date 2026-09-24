package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.util.Log


fun ReleaseViewModel.search(query: String, token: String) {
    if (query.isBlank()) return

    navigationRequestGeneration++
    val generation = navigationRequestGeneration
    navigationRequestJob?.cancel()

    navigationRequestJob = viewModelScope.launch {
        _uiState.value = ReleaseUiState.Loading

        try {
            val authHeader = "Discogs token=$token"
            val response =
                RetrofitClient.apiService.searchDatabase(
                    query = query,
                    authHeader = authHeader
                )

            if (generation == navigationRequestGeneration) {
                _uiState.value =
                    ReleaseUiState.SearchSuccess(response.results)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == navigationRequestGeneration) {
                _uiState.value =
                    ReleaseUiState.Error(
                        e.localizedMessage ?: "Search failed"
                    )
            }
        }
    }
}

fun ReleaseViewModel.fetchRelease(releaseId: Long, token: String) {
    navigationRequestGeneration++
    val generation = navigationRequestGeneration
    navigationRequestJob?.cancel()

    navigationRequestJob = viewModelScope.launch {
        _uiState.value = ReleaseUiState.Loading

        try {
            val authHeader = "Discogs token=$token"
            currentReleaseId = releaseId

            val releaseResponse = RetrofitClient.apiService.getRelease(
                releaseId = releaseId,
                authHeader = authHeader
            )

            if (generation != navigationRequestGeneration) {
                return@launch
            }

            // Show Release Details immediately. Its marketplace WebView can
            // begin warming while stats and suggestions load in parallel.
            _uiState.value = ReleaseUiState.ReleaseSuccess(
                release = releaseResponse,
                // Publish the format immediately, before optional stats
                // and price suggestions finish loading.
                priceSummary = ReleasePriceSummary(
                    isAlbumRelease = releaseResponse.isAlbumFormat()
                )
            )

            data class StatsResult(
                val numForSale: Int,
                val lowestPrice: Double?,
                val error: String? = null
            )

            val (statsResult, suggestionsResult) = supervisorScope {
                val statsDeferred = async {
                    try {
                        val stats = RetrofitClient.apiService.getMarketplaceStats(
                            releaseId = releaseId,
                            authHeader = authHeader
                        )
                        StatsResult(
                            numForSale = stats.numForSale ?: 0,
                            lowestPrice = stats.lowestPrice?.value
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("DiscogsDebug", "Marketplace Stats Failed!", e)
                        StatsResult(
                            numForSale = 0,
                            lowestPrice = null,
                            error = "StatsErr: ${e.javaClass.simpleName}"
                        )
                    }
                }

                val suggestionsDeferred = async {
                    try {
                        Pair(
                            RetrofitClient.apiService.getPriceSuggestions(
                                releaseId = releaseId,
                                authHeader = authHeader
                            ),
                            null as String?
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("DiscogsDebug", "Price Suggestions Failed!", e)
                        Pair(
                            null,
                            "PriceErr: ${e.javaClass.simpleName}"
                        )
                    }
                }

                statsDeferred.await() to suggestionsDeferred.await()
            }

            if (generation != navigationRequestGeneration) {
                return@launch
            }

            val suggestions = suggestionsResult.first
            val debugMsg =
                listOfNotNull(
                    statsResult.error,
                    suggestionsResult.second
                ).takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ")
                    ?: "No Data"

            val summary = ReleasePriceSummary(
                low = suggestions?.low,
                median = suggestions?.median,
                high = suggestions?.high,
                currency = "USD",
                lastSold = debugMsg,
                numForSale = statsResult.numForSale,
                lowestAskingPrice = statsResult.lowestPrice,
                priceSuggestions = suggestions,
                isAlbumRelease = releaseResponse.isAlbumFormat()
            )

            _uiState.value = ReleaseUiState.ReleaseSuccess(
                release = releaseResponse,
                priceSummary = summary
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == navigationRequestGeneration) {
                _uiState.value = ReleaseUiState.Error(
                    e.localizedMessage ?: "Failed to fetch details"
                )
            }
        }
    }
}

fun ReleaseViewModel.fetchMasterVersions(
    masterId: Long,
    token: String
) {
    invalidateNavigationRequests()
    val currentState = _uiState.value

    if (currentState is ReleaseUiState.ReleaseSuccess) {
        releaseBeforeMasterVersions = currentState
    }

    viewModelScope.launch {
        _uiState.value =
            ReleaseUiState.MasterVersionsLoading(
                masterId = masterId
            )

        try {
            val authHeader =
                "Discogs token=$token"

            val firstPage =
                RetrofitClient.apiService
                    .getMasterVersions(
                        masterId = masterId,
                        authHeader = authHeader,
                        page = 1,
                        perPage = 100
                    )

            val allVersions =
                firstPage.versions.toMutableList()

            val totalPages =
                firstPage.pagination?.pages ?: 1

            if (totalPages > 1) {
                for (page in 2..totalPages) {
                    val response =
                        RetrofitClient.apiService
                            .getMasterVersions(
                                masterId = masterId,
                                authHeader = authHeader,
                                page = page,
                                perPage = 100
                            )

                    allVersions.addAll(
                        response.versions
                    )
                }
            }

            _uiState.value =
                ReleaseUiState.MasterVersionsSuccess(
                    masterId = masterId,
                    versions = allVersions,
                    totalItems =
                        firstPage.pagination?.items
                            ?: allVersions.size
                )

        } catch (e: Exception) {
            Log.e(
                "MASTER_VERSIONS",
                "Failed to load master versions",
                e
            )

            _uiState.value =
                ReleaseUiState.MasterVersionsError(
                    masterId = masterId,
                    message =
                        e.localizedMessage
                            ?: "Failed to load versions"
                )
        }
    }
}

fun ReleaseViewModel.returnFromMasterVersions() {
    _uiState.value =
        releaseBeforeMasterVersions
            ?: ReleaseUiState.Idle
}
