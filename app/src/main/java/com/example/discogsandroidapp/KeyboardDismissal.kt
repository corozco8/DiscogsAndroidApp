package com.example.discogsandroidapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

private class InputArea {
    var bounds = Rect.Zero
    var focused = false
    var dismiss: () -> Unit = {}
}

private class InputWindow(val window: Window, val delegate: Window.Callback) : Window.Callback by delegate {
    val areas = mutableSetOf<InputArea>()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val point = Offset(event.rawX, event.rawY)
            if (areas.none { it.bounds.contains(point) }) {
                areas.firstOrNull { it.focused }?.dismiss?.invoke()
            }
        }
        // Keep the original tap, including clicks on buttons and other text fields.
        return delegate.dispatchTouchEvent(event)
    }
}

private val inputWindows = mutableMapOf<Window, InputWindow>()

private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}

internal fun Modifier.keyboardInputArea(): Modifier = composed {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val area = remember { InputArea() }
    DisposableEffect(view, focusManager, keyboard) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activityWindow()
        val callback = window?.let { target ->
            inputWindows.getOrPut(target) {
                InputWindow(target, target.callback).also { target.callback = it }
            }
        }
        area.dismiss = { focusManager.clearFocus(); keyboard?.hide() }
        callback?.areas?.add(area)
        onDispose {
            area.dismiss = {}
            callback?.areas?.remove(area)
            if (callback != null && callback.areas.isEmpty()) {
                if (window.callback === callback) window.callback = callback.delegate
                inputWindows.remove(window)
            }
        }
    }
    onFocusChanged { area.focused = it.hasFocus }
        .onGloballyPositioned { coordinates ->
            val screen = IntArray(2)
            val inWindow = IntArray(2)
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(inWindow)
            area.bounds = coordinates.boundsInWindow().translate(
                Offset((screen[0] - inWindow[0]).toFloat(), (screen[1] - inWindow[1]).toFloat())
            )
        }
}
