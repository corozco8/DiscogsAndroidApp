package com.example.discogsandroidapp

import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal object SearchDialogBridge {
    var searchBounds: Rect? = null
    var focusSearch: (() -> Unit)? = null
}

@Composable
internal fun SearchAccessibleDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current.density
    val headerBottom = SearchDialogBridge.searchBounds?.bottom?.div(density) ?: 160f
    val maxHeight = (screenHeight - 2 * (headerBottom + 12)).coerceIn(160f, 640f).dp
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val original = window?.callback
            val callback = original?.let { delegate ->
                object : Window.Callback by delegate {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                            SearchDialogBridge.searchBounds?.contains(Offset(event.rawX, event.rawY)) == true) {
                            val focus = SearchDialogBridge.focusSearch
                            dismiss()
                            focus?.invoke()
                            return true
                        }
                        return delegate.dispatchTouchEvent(event)
                    }
                }
            }
            if (callback != null) window?.callback = callback
            onDispose { if (window?.callback === callback) window?.callback = original }
        }
        Box(Modifier.heightIn(max = maxHeight)) { content() }
    }
}
