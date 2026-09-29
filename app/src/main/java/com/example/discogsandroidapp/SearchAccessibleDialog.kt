package com.example.discogsandroidapp

import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

/** The search field can overlap the dialog when the IME resizes its window. */
internal fun shouldFocusSearchBehindDialog(
    point: Offset,
    searchBounds: Rect?,
    dialogBounds: Rect?,
    allowSearchFocus: Boolean
): Boolean = allowSearchFocus &&
    searchBounds != null && dialogBounds != null && !dialogBounds.isEmpty &&
    searchBounds.contains(point) && !dialogBounds.contains(point)

@Composable
internal fun SearchAccessibleDialog(
    onDismissRequest: () -> Unit,
    allowSearchFocus: Boolean = true,
    content: @Composable () -> Unit
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val searchFocusAllowed by rememberUpdatedState(allowSearchFocus)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current.density
    val headerBottom = SearchDialogBridge.searchBounds?.bottom?.div(density) ?: 160f
    val maxHeight = (screenHeight - 2 * (headerBottom + 12)).coerceIn(160f, 640f).dp
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)) {
        val view = LocalView.current
        var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val original = window?.callback
            val callback = original?.let { delegate ->
                object : Window.Callback by delegate {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            // Resolve the window's current screen position at touch time:
                            // showing/hiding the IME can move it without a Compose relayout.
                            val dialogBounds = contentCoordinates?.takeIf { it.isAttached }?.let { coordinates ->
                                val screen = IntArray(2)
                                val inWindow = IntArray(2)
                                view.getLocationOnScreen(screen)
                                view.getLocationInWindow(inWindow)
                                coordinates.boundsInWindow().translate(
                                    Offset((screen[0] - inWindow[0]).toFloat(), (screen[1] - inWindow[1]).toFloat())
                                )
                            }
                            val focus = SearchDialogBridge.focusSearch
                            if (focus != null && shouldFocusSearchBehindDialog(
                                    point = Offset(event.rawX, event.rawY),
                                    searchBounds = SearchDialogBridge.searchBounds,
                                    dialogBounds = dialogBounds,
                                    allowSearchFocus = searchFocusAllowed
                                )) {
                                dismiss()
                                focus()
                                return true
                            }
                        }
                        return delegate.dispatchTouchEvent(event)
                    }
                }
            }
            if (callback != null) window?.callback = callback
            onDispose { if (window?.callback === callback) window?.callback = original }
        }
        Box(Modifier.heightIn(max = maxHeight).onGloballyPositioned { contentCoordinates = it }) {
            content()
        }
    }
}
