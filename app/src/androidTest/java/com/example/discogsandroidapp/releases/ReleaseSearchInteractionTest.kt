package com.example.discogsandroidapp.releases

import android.app.Application
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discogsandroidapp.data.DiscogsSearchResponse
import com.example.discogsandroidapp.data.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RunWith(AndroidJUnit4::class)
class ReleaseSearchInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedSuggestionsAppearWithoutDebounceOrAnotherRequest() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val response = DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours")))
        val reads = AtomicInteger()
        val model = ReleaseSearchSuggestionsViewModel(application, cachedResults = { _, _ -> response }) { _, _ ->
            reads.incrementAndGet(); response
        }
        model.update("Rumours", true, "test")
        assertEquals(54321, model.state.value.results.single().id)
        assertTrue(!model.state.value.loading)
        assertEquals(0, reads.get())
    }

    @Test fun pressingEnterWhileSuggestionsLoadSharesTheRequest() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val cache = ReleaseSearchCache()
        val reads = AtomicInteger()
        val submitted = AtomicInteger()
        val response = DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours")))
        suspend fun load(query: String, token: String) = cache.search(query, token) {
            reads.incrementAndGet(); delay(1_000); response
        }
        val model = ReleaseSearchSuggestionsViewModel(application) { query, token -> load(query, token) }
        compose.setContent {
            var query by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            MaterialTheme {
                ReleaseSearchField(query, { query = it }, { submittedQuery ->
                    scope.launch { submitted.set(load(submittedQuery, "test").results.first().id) }
                }, {}, model, token = "test")
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithTag("Search suggestions").assertDoesNotExist()
        compose.waitUntil(5_000) { submitted.get() != 0 }
        compose.onNodeWithTag("Search suggestions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(54321, submitted.get()); assertEquals(1, reads.get()) }
    }

    @Test fun suggestionsSpanTheWindowAndShowCoverBesideReleaseMetadata() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val reads = AtomicInteger()
        val model = ReleaseSearchSuggestionsViewModel(application) { _, _ ->
            reads.incrementAndGet()
            DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours", year = "1977",
                format = listOf("Vinyl", "LP", "Reissue"),
                coverImage = "android.resource://android/${android.R.drawable.ic_menu_gallery}")))
        }
        compose.setContent {
            var query by remember { mutableStateOf("") }
            MaterialTheme {
                Column(Modifier.fillMaxSize().testTag("Search page")) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Spacer(Modifier.width(48.dp))
                        ReleaseSearchField(query, { query = it }, {}, {}, model, "test", Modifier.weight(1f))
                        Spacer(Modifier.width(62.dp))
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Cover for Rumours").fetchSemanticsNodes().isNotEmpty() }
        val page = compose.onNodeWithTag("Search page").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("Search suggestions").fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithContentDescription("Cover for Rumours", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Rumours") and hasAnyAncestor(hasTestTag("Search suggestions")),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(page.width, panel.width, 1f)
        assertTrue(panel.width > field.width)
        assertTrue(cover.right < title.left)
        compose.onNodeWithText("1977 · Vinyl · LP · RE").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads.get()) }
    }

    @Test fun imeSearchHidesKeyboardAndPopupAcrossPageChanges() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val submits = AtomicInteger()
        val model = ReleaseSearchSuggestionsViewModel(application) { _, _ ->
            DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours")))
        }
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            var query by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (!loading) IconButton(onClick = {}) { Text("Back") }
                        ReleaseSearchField(query, { query = it }, {
                            submits.incrementAndGet()
                            loading = true
                            scope.launch { delay(300); loading = false }
                        }, {}, model, "test", Modifier.weight(1f))
                    }
                    Text(if (loading) "Searching" else "Results")
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fleetwood Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            host.context.getSystemService(InputMethodManager::class.java).showSoftInput(host, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithTag("Search suggestions").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Results").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, submits.get()) }
        compose.onNodeWithTag("Search suggestions").assertDoesNotExist()
        compose.runOnIdle { assertTrue(ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == false) }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("Search suggestions").fetchSemanticsNodes().isNotEmpty() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun hardwareEnterSubmitsOnceAndClosesSuggestions() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val submits = AtomicInteger()
        val model = ReleaseSearchSuggestionsViewModel(application) { _, _ ->
            DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours")))
        }
        compose.setContent {
            var query by remember { mutableStateOf("") }
            MaterialTheme {
                ReleaseSearchField(query, { query = it }, { submits.incrementAndGet() }, {}, model, "test")
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fleetwood Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithTag("Search suggestions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, submits.get()) }
    }

    @Test fun focusingAnEmptySearchShowsHistoryWithoutAnApiRequest() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        runBlocking { ReleaseSearchHistory.initialize(application) }
        val original = ReleaseSearchHistory.entries.value
        val reads = AtomicInteger()
        try {
            ReleaseSearchHistory.entries.value = listOf(ReleaseSearchHistoryEntry("Rumours"))
            val model = ReleaseSearchSuggestionsViewModel(application) { _, _ ->
                reads.incrementAndGet(); DiscogsSearchResponse(emptyList())
            }
            compose.setContent {
                MaterialTheme {
                    ReleaseSearchField("", {}, {}, {}, model, token = "test")
                }
            }
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNodeWithText("Recent searches").assertIsDisplayed()
            compose.onNodeWithText("Rumours").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, reads.get()) }
        } finally { ReleaseSearchHistory.entries.value = original }
    }

    @Test fun suggestionsShowTheEditionAndOpenTheReleaseWithoutSubmittingAnotherSearch() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val reads = AtomicInteger()
        val selected = AtomicInteger()
        val submits = AtomicInteger()
        val model = ReleaseSearchSuggestionsViewModel(application) { _, _ ->
            reads.incrementAndGet()
            DiscogsSearchResponse(listOf(SearchResult(54321, "Fleetwood Mac - Rumours", year = "1977",
                format = listOf("Vinyl", "LP", "Reissue"))))
        }
        compose.setContent {
            var query by remember { mutableStateOf("") }
            MaterialTheme {
                ReleaseSearchField(query, { query = it }, { submits.incrementAndGet() },
                    { selected.set(it.id) }, model, token = "test")
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fleetwood Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1977 · Vinyl · LP · RE").assertIsDisplayed()
        compose.onNodeWithText("Fleetwood Mac").performClick()
        compose.runOnIdle {
            assertEquals(54321, selected.get())
            assertEquals(0, submits.get())
            assertEquals(1, reads.get())
        }
    }
}
