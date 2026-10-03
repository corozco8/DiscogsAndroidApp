package com.example.discogsandroidapp.releases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.example.discogsandroidapp.data.SearchResult
import com.example.discogsandroidapp.ui.shared.keyboardInputArea

@Composable
internal fun ReleaseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRelease: (SearchResult) -> Unit,
    suggestions: ReleaseSearchSuggestionsViewModel,
    token: String,
    modifier: Modifier = Modifier,
    storeMode: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var anchorBottom by remember { mutableIntStateOf(0) }
    val state by suggestions.state.collectAsState()
    val history by suggestions.history.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val bottomInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.safeDrawing.getBottom(density))
    val availableHeight = with(density) { (windowSize.height - anchorBottom - bottomInset).coerceAtLeast(0).toDp() }
    LaunchedEffect(value, focused, storeMode, token) {
        suggestions.update(value, focused && !storeMode, token)
    }
    fun dismissSearch() {
        dismissed = true
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    fun submit(query: String) {
        dismissSearch()
        if (query.isNotBlank()) onSearch(query.trim())
    }
    fun select(result: SearchResult) {
        dismissSearch()
        suggestions.selected(result)
        onRelease(result)
    }
    Box(modifier.onGloballyPositioned { anchorBottom = it.boundsInWindow().bottom.toInt() }) {
        OutlinedTextField(
            value = value,
            onValueChange = { dismissed = false; onValueChange(it) },
            label = { Text(if (storeMode) "Search My Store..." else "Search...", maxLines = 1) },
            modifier = Modifier.keyboardInputArea().fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged {
                    if (focused != it.isFocused) {
                        focused = it.isFocused
                        if (focused) dismissed = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) submit(value)
                        true
                    } else false
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { submit(value) },
                onDone = { submit(value) },
                onGo = { submit(value) }
            ),
            trailingIcon = {
                if (value.isNotEmpty()) IconButton(onClick = {
                    dismissed = false; onValueChange(""); focusRequester.requestFocus(); keyboard?.show()
                }) { Icon(Icons.Default.Clear, contentDescription = "Clear Search") }
            }
        )
        val showHistory = value.isBlank() && history.isNotEmpty()
        val showResults = value.isNotBlank() && state.query == value &&
            (state.results.isNotEmpty() || state.loading || state.error != null)
        androidx.activity.compose.BackHandler(focused && !storeMode && !dismissed && (showHistory || showResults)) {
            dismissSearch()
        }
        if (focused && !storeMode && !dismissed && (showHistory || showResults) && availableHeight > 0.dp) {
            Popup(
                popupPositionProvider = SearchSuggestionsPosition,
                onDismissRequest = { dismissed = true },
                properties = PopupProperties(focusable = false, usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .width(with(density) { windowSize.width.toDp() })
                        .then(
                            if (showHistory) Modifier.height(availableHeight)
                            else Modifier.heightIn(max = minOf(340.dp, availableHeight))
                        )
                        .testTag("Search suggestions"),
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        if (showHistory) {
                            DropdownMenuItem(
                                text = { Text("Recent searches", fontWeight = FontWeight.SemiBold) },
                                trailingIcon = { TextButton(onClick = { suggestions.clearHistory() }) { Text("Clear") } },
                                modifier = Modifier.fillMaxWidth(), onClick = {}
                            )
                            history.take(8).forEach { entry ->
                                DropdownMenuItem(
                                    text = { if (entry.release != null) SuggestionLabel(entry.release) else Text(entry.query) },
                                    leadingIcon = entry.release?.let { release -> { SuggestionCover(release) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (entry.release != null) select(entry.release)
                                        else { onValueChange(entry.query); submit(entry.query) }
                                    }
                                )
                            }
                        } else {
                            if (state.loading) DropdownMenuItem(
                                text = { Text("Finding releases…") }, modifier = Modifier.fillMaxWidth(),
                                onClick = {}, enabled = false
                            )
                            state.results.forEach { result ->
                                DropdownMenuItem(
                                    text = { SuggestionLabel(result) },
                                    leadingIcon = { SuggestionCover(result) },
                                    modifier = Modifier.fillMaxWidth(), onClick = { select(result) }
                                )
                            }
                            state.error?.let { error -> DropdownMenuItem(
                                text = { Text(error) }, modifier = Modifier.fillMaxWidth(),
                                onClick = {}, enabled = false
                            ) }
                        }
                    }
                }
            }
        }
    }
}

private object SearchSuggestionsPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize
    ): IntOffset =
        IntOffset((windowSize.width - popupContentSize.width).coerceAtLeast(0) / 2, anchorBounds.bottom)
}

@Composable
private fun SuggestionCover(result: SearchResult) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = result.thumb?.takeIf { it.isNotBlank() } ?: result.coverImage,
            contentDescription = "Cover for ${result.suggestionText().title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SuggestionLabel(result: SearchResult) {
    val text = result.suggestionText()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text.edition, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
