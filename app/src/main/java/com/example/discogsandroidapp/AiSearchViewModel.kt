package com.example.discogsandroidapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState =
        MutableStateFlow<AiSearchUiState>(AiSearchUiState.Idle)

    val uiState: StateFlow<AiSearchUiState> =
        _uiState.asStateFlow()

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }

    fun search() {
        val currentQuery = _query.value.trim()

        if (currentQuery.isEmpty()) {
            return
        }

        viewModelScope.launch {

            _uiState.value = AiSearchUiState.Loading

            try {

                val response =
                    BackendRetrofitClient.apiService.search(
                        AiSearchRequest(
                            query = currentQuery
                        )
                    )

                _uiState.value =
                    AiSearchUiState.Success(response)

            } catch (e: Exception) {

                _uiState.value =
                    AiSearchUiState.Error(
                        e.message ?: "Search failed"
                    )
            }
        }
    }

    fun clearSearch() {
        _query.value = ""
        _uiState.value = AiSearchUiState.Idle
    }
}