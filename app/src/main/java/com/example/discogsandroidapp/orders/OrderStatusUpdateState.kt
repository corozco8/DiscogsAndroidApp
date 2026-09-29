package com.example.discogsandroidapp.orders

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OrderStatusUpdateState(val settingStatus: String? = null, val error: String? = null)

/** Busy state outlives recompositions and order-detail refresh responses. */
internal class OrderStatusUpdateTracker {
    private val mutable = MutableStateFlow<Map<String, OrderStatusUpdateState>>(emptyMap())
    val states = mutable.asStateFlow()
    fun begin(orderId: String, status: String): Boolean {
        if (mutable.value[orderId]?.settingStatus != null) return false
        mutable.value = mutable.value + (orderId to OrderStatusUpdateState(settingStatus = status))
        return true
    }
    fun finish(orderId: String, error: String? = null) {
        mutable.value = if (error == null) mutable.value - orderId
            else mutable.value + (orderId to OrderStatusUpdateState(error = error))
    }
}
