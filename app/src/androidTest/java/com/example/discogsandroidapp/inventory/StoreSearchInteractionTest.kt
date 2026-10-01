package com.example.discogsandroidapp.inventory

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discogsandroidapp.data.*
import com.example.discogsandroidapp.releases.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreSearchInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val modelStore = ViewModelStore()
    private lateinit var application: Application
    private lateinit var database: SellerLocalDatabase
    private lateinit var model: ReleaseViewModel
    private var originalInventory = emptyList<LocalInventoryListingEntity>()
    private var originalSync: LocalSyncStateEntity? = null

    @Before fun seedSavedInventory(): Unit = runBlocking {
        application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        database = SellerLocalDatabase.getInstance(application)
        val dao = database.sellerDao()
        originalInventory = dao.getInventorySnapshot()
        originalSync = dao.getSyncState(SellerLocalRepository.INVENTORY_SYNC_KEY)
        val now = System.currentTimeMillis()
        val rows = (1..3_000).map { index ->
            val artist = if (index <= 2) "Fleetwood Mac" else "Artist $index"
            val title = when (index) { 1 -> "Rumours"; 2 -> "Tusk"; else -> "Album $index" }
            LocalInventoryListingEntity(8_700_000L + index, 50_000L + index, artist, title, "", "For Sale",
                "Very Good Plus (VG+)", "Very Good Plus (VG+)", if (index == 1) "BSK 3010" else "",
                index.toDouble(), "USD", "2024-01-02", index.toLong(), true, now, now)
        }
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("DELETE FROM seller_inventory")
            dao.upsertInventory(rows)
            dao.upsertSyncState(LocalSyncStateEntity(SellerLocalRepository.INVENTORY_SYNC_KEY, now, 3_000))
        }
        model = ReleaseViewModel(application)
        modelStore.put("store", model)
    }

    @After fun restoreSavedInventory(): Unit = runBlocking {
        modelStore.clear()
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("DELETE FROM seller_inventory")
            database.sellerDao().upsertInventory(originalInventory)
            database.openHelper.writableDatabase.execSQL("DELETE FROM seller_sync_state WHERE `key` = 'inventory'")
            originalSync?.let { database.sellerDao().upsertSyncState(it) }
        }
    }

    private fun openStore() {
        compose.setContent {
            val state by model.uiState.collectAsState()
            var query by remember { mutableStateOf("") }
            val suggestions = remember { ReleaseSearchSuggestionsViewModel(application) { _, _ ->
                error("My Store must not request database suggestions")
            } }
            val listState = rememberLazyListState()
            LaunchedEffect(query) {
                if (model.setStoreQuery(query)) listState.scrollToItem(0)
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    ReleaseSearchField(query, { query = it }, {}, {}, suggestions, "offline-store-test",
                        storeMode = true)
                    (state as? ReleaseUiState.StoreSuccess)?.let { store ->
                        Box(Modifier.weight(1f)) {
                            StoreScreen(store.listings, "offline-store-test", listState, store.totalItems,
                                syncMessage = store.syncMessage, isRefreshing = store.isRefreshing,
                                searchQuery = query, onRefresh = model::refreshStore,
                                onSortChanged = model::setStoreSort,
                                onDeleteListing = {}, onDeleteSelected = {}, onEditListing = { _, _, _, _, _ -> })
                        }
                    }
                }
            }
        }
        compose.runOnIdle { model.clearStoreSearch("offline-store-test") }
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 3_000 }
    }

    @Test fun typingFindsOlderListingsAndClearRestoresNewestFirstWithoutNetwork() {
        openStore()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("fleetwood")
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 2 }
        compose.onNodeWithText("Fleetwood Mac - Rumours").assertIsDisplayed()
        compose.onNodeWithText("Fleetwood Mac - Tusk").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { assertEquals(listOf(8_700_002L, 8_700_001L),
            (model.uiState.value as ReleaseUiState.StoreSuccess).listings.map { it.id }) }
        compose.onNodeWithContentDescription("Clear Search").performClick()
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 3_000 }
        compose.runOnIdle { assertEquals(8_703_000L, (model.uiState.value as ReleaseUiState.StoreSuccess).listings.first().id) }
    }

    @Test fun sortingAndNoMatchesKeepTheActiveSearch() {
        openStore()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("fleetwood")
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 2 }
        compose.onNodeWithContentDescription("Sort Options").performClick()
        compose.onNodeWithText("Title: A-Z").performClick()
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.firstOrNull()?.id == 8_700_001L }
        compose.onNode(hasSetTextAction()).assertTextContains("fleetwood")
        compose.onNode(hasSetTextAction()).performTextReplacement("no such album")
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.isEmpty() == true }
        compose.onNodeWithText("No inventory results found.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("no such album")
    }

    @Test fun aConfirmedNewListingAppearsWhileItsSearchIsOpen() {
        openStore()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Rumours")
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 1 }
        val repository = SellerLocalRepository(application)
        runBlocking { repository.cacheCreatedListing(InventoryListing(id = 8_710_001L,
            status = "For Sale",
            condition = "Very Good Plus (VG+)", sleeve_condition = "Very Good Plus (VG+)",
            comments = "New copy", posted = "2026-09-30", price = Price(19.5, "USD"),
            release = ListingRelease(id = 50_001, artist = "Fleetwood Mac", title = "Rumours",
                description = "Fleetwood Mac - Rumours", thumbnail = ""))) }
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 2 }
        compose.runOnIdle { assertEquals(8_710_001L, (model.uiState.value as ReleaseUiState.StoreSuccess).listings.first().id) }
        runBlocking { repository.removeLocalListing(8_710_001L) }
        compose.waitUntil(5_000) { (model.uiState.value as? ReleaseUiState.StoreSuccess)?.listings?.size == 1 }
    }
}
