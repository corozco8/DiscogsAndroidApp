package com.example.discogsandroidapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class SellerInsightsViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository =
        SellerLocalRepository(application)

    val inventory =
        repository.inventory.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val orders =
        repository.orders.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val orderItems =
        repository.orderItems.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val inventorySyncState =
        repository.inventorySyncState.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )

    val orderSyncState =
        repository.orderSyncState.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )

    fun refreshNow() {
        SellerSyncScheduler.enqueueNow(
            getApplication()
        )
    }
}
