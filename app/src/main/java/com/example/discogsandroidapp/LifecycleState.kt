package com.example.discogsandroidapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun <T> StateFlow<T>.collectWhileStarted(): State<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activeFlow = remember(this, lifecycle) { flowWithLifecycle(lifecycle, Lifecycle.State.STARTED) }
    return activeFlow.collectAsState(initial = value)
}
