package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.Price
import com.example.discogsandroidapp.data.InventoryListing
import com.example.discogsandroidapp.data.ListingRelease
import com.example.discogsandroidapp.inventory.EditListingDialog
import com.example.discogsandroidapp.data.ReleasePriceSummary
import com.example.discogsandroidapp.pricing.ListingPricingInfo
import com.example.discogsandroidapp.pricing.MarketplaceUiPriceStatus
import com.example.discogsandroidapp.ui.shared.SearchAccessibleDialog
import com.example.discogsandroidapp.ui.shared.SearchDialogBridge

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListingDialogInteractionTest {
    private val mintDescription = "This is a sealed record, cannot be returned once opened. Sold as a factory sealed collectible"
    private val nearMintDescription = "A nearly perfect record with no obvious signs of wear"

    @Test fun mediaPresetsAreEditableAndSleeveChangesDoNotReplaceThem() {
        var savedDescription: String? = null
        compose.setContent {
            MaterialTheme {
                AddListingDialog(onDismiss = {}, onSave = { _, _, _, comments -> savedDescription = comments })
            }
        }
        val description = compose.onNode(hasSetTextAction() and hasText("Description / Comments"))
        compose.onAllNodesWithText("M")[0].performClick()
        description.assertTextContains(mintDescription)
        compose.onAllNodesWithText("NM")[1].performClick()
        description.assertTextContains(mintDescription)
        compose.onAllNodesWithText("NM")[0].performClick()
        description.assertTextContains(nearMintDescription)
        val addition = ". Includes original inner sleeve."
        description.performScrollTo().performClick().performTextInputSelection(TextRange(nearMintDescription.length))
        description.performTextInput(addition)
        compose.onAllNodesWithText("VG")[0].performScrollTo().performClick()
        description.assertTextContains(nearMintDescription + addition)
        compose.onNode(hasSetTextAction() and hasText("Price (USD)")).performScrollTo().performTextInput("12.50")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(nearMintDescription + addition, savedDescription) }
    }

    @Test fun editingABlankDescriptionUsesTheSelectedMediaPreset() {
        var savedDescription: String? = null
        compose.setContent {
            MaterialTheme {
                EditListingDialog(
                    listing = InventoryListing(123, "For Sale", "Very Good (VG)", comments = "",
                        price = Price(12.50, "USD"), release = ListingRelease(id = 456)),
                    onDismiss = {}, onSave = { _, _, _, comments -> savedDescription = comments }
                )
            }
        }
        val description = compose.onNode(hasSetTextAction() and hasText("Description / Comments"))
        compose.onAllNodesWithText("M")[0].performClick()
        description.assertTextContains(mintDescription)
        compose.onAllNodesWithText("NM")[0].performClick()
        description.assertTextContains(nearMintDescription)
        compose.onAllNodesWithText("VG")[0].performClick()
        assertEquals("", description.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onAllNodesWithText("M")[0].performClick()
        compose.onNodeWithText("Save Changes").performClick()
        compose.runOnIdle { assertEquals(mintDescription, savedDescription) }
    }

    @Test fun editingMediaGradePreservesAnExistingSellerDescription() {
        var savedDescription: String? = null
        compose.setContent {
            MaterialTheme {
                EditListingDialog(
                    listing = InventoryListing(123, "For Sale", "Very Good (VG)", comments = "Original seller notes",
                        price = Price(12.50, "USD"), release = ListingRelease(id = 456)),
                    onDismiss = {}, onSave = { _, _, _, comments -> savedDescription = comments }
                )
            }
        }
        compose.onAllNodesWithText("M")[0].performClick()
        compose.onAllNodesWithText("NM")[0].performClick()
        compose.onNode(hasSetTextAction() and hasText("Description / Comments")).assertTextContains("Original seller notes")
        compose.onNodeWithText("Save Changes").performClick()
        compose.runOnIdle { assertEquals("Original seller notes", savedDescription) }
    }
    @get:Rule val compose = createComposeRule()

    @Before fun resetBridge() {
        SearchDialogBridge.searchBounds = null
        SearchDialogBridge.focusSearch = null
    }

    @After fun clearBridge() = resetBridge()

    @Test fun nativeSaveTapOverSearchReachesSaveWithoutClosingDialog() {
        checkNativeTap(overlap = true)
    }

    @Test fun nativeTapOnExposedSearchStillHandsOffFocus() {
        checkNativeTap(overlap = false)
    }

    private fun checkNativeTap(overlap: Boolean) {
        var saves = 0
        var dismissals = 0
        var searchFocusRequests = 0
        lateinit var dialogView: View
        lateinit var dialogWindow: Window
        var buttonBounds = Rect.Zero
        compose.setContent {
            MaterialTheme {
                SearchAccessibleDialog(onDismissRequest = { dismissals++ }) {
                    val view = LocalView.current
                    SideEffect {
                        dialogView = view
                        dialogWindow = (view.parent as DialogWindowProvider).window
                    }
                    Button(
                        onClick = { saves++ },
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                            buttonBounds = it.boundsInWindow()
                        }
                    ) { Text("Save") }
                }
            }
        }
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.runOnIdle {
            val screen = IntArray(2)
            val inWindow = IntArray(2)
            dialogView.getLocationOnScreen(screen)
            dialogView.getLocationInWindow(inWindow)
            val bounds = buttonBounds.translate(Offset(
                (screen[0] - inWindow[0]).toFloat(), (screen[1] - inWindow[1]).toFloat()
            ))
            // Model either the IME moving Save over Search or Search remaining exposed.
            val point = if (overlap) bounds.center else Offset(bounds.center.x, bounds.top - 20f)
            SearchDialogBridge.searchBounds = Rect(point.x - 10f, point.y - 10f, point.x + 10f, point.y + 10f)
            SearchDialogBridge.focusSearch = { searchFocusRequests++ }

            // Go through Window.Callback: a semantics-only performClick skips the bug.
            val origin = IntArray(2)
            dialogWindow.decorView.getLocationOnScreen(origin)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                event.setLocation(point.x - origin[0], point.y - origin[1])
                try { dialogWindow.callback.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        compose.runOnIdle {
            assertEquals(if (overlap) 1 else 0, saves)
            assertEquals(if (overlap) 0 else 1, dismissals)
            assertEquals(if (overlap) 0 else 1, searchFocusRequests)
        }
    }

    @Test fun rejectedListingKeepsEnteredPriceAndShowsError() {
        val submitting = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var saves = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                AddListingDialog(
                    isSubmitting = submitting.value,
                    submissionError = error.value,
                    onDismiss = { dismissals++ },
                    onSave = { price, condition, _, _ ->
                        assertEquals(12.50, price, 0.0)
                        assertEquals("Very Good (VG)", condition)
                        saves++
                        submitting.value = true
                    }
                )
            }
        }
        compose.onAllNodesWithText("VG")[0].performClick()
        compose.onNode(hasSetTextAction() and hasText("Price (USD)")).performTextInput("12.50")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Listing…").assertIsDisplayed()
        compose.runOnIdle {
            submitting.value = false
            error.value = "Discogs rejected this listing (HTTP 400)."
        }
        compose.onNodeWithText("Discogs rejected this listing (HTTP 400).").assertIsDisplayed()
        compose.onNodeWithText("12.50").assertExists()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(0, dismissals)
        }
    }

    @Test fun verificationAndPriceUpdatesPreserveTheListingDraft() {
        val pricing = mutableStateOf(ListingPricingInfo(MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, "Security check required"))
        val summary = mutableStateOf(ReleasePriceSummary())
        var verifications = 0
        var saves = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                AddListingDialog(
                    priceSummary = summary.value, pricingInfo = pricing.value,
                    onVerifyPricing = { verifications++ }, onDismiss = { dismissals++ },
                    onSave = { price, condition, _, _ ->
                        assertEquals(12.50, price, 0.0)
                        assertEquals("Very Good (VG)", condition)
                        saves++
                    }
                )
            }
        }
        compose.onAllNodesWithText("VG")[0].performClick()
        compose.onNode(hasSetTextAction() and hasText("Price (USD)")).performTextInput("12.50")
        compose.onNodeWithText("Verify Discogs").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, verifications)
            assertEquals(0, saves)
            assertEquals(0, dismissals)
            pricing.value = ListingPricingInfo(MarketplaceUiPriceStatus.FRESH, "Prices checked now")
            summary.value = ReleasePriceSummary(isAlbumRelease = false,
                activeMediaLowestPrices = mapOf("Very Good (VG)" to 50.0),
                activeMediaListingCounts = mapOf("Very Good (VG)" to 2))
        }
        compose.onNodeWithText("Verify Discogs").assertDoesNotExist()
        compose.onNodeWithText("12.50").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun singleListingEstimateIsLabeledAndCanBeAppliedWithoutOverwritingTheDraft() {
        val summary = ReleasePriceSummary(
            activeMediaPriceSamples = mapOf("Very Good (VG)" to listOf(10.0)),
            activeMediaSleevePriceSamples = mapOf("Very Good (VG)||Very Good Plus (VG+)" to listOf(10.0)))
        compose.setContent {
            MaterialTheme {
                AddListingDialog(priceSummary = summary,
                    pricingInfo = ListingPricingInfo(MarketplaceUiPriceStatus.FRESH, "Prices checked now"),
                    onDismiss = {}, onSave = { _, _, _, _ -> })
            }
        }
        val price = compose.onNode(hasSetTextAction() and hasText("Price (USD)"))
        price.performTextInput("12.50")
        compose.onAllNodesWithText("NM")[0].performClick()
        compose.onNodeWithText("Estimated NM: USD 27.78").assertIsDisplayed()
        price.assertTextContains("12.50")
        compose.onNodeWithText("Use").performClick()
        price.assertTextContains("27.78")
        compose.onAllNodesWithText("VG+")[0].performClick()
        compose.onNodeWithText("Estimated VG+: USD 16.67").assertIsDisplayed()
        price.assertTextContains("27.78")
    }

    @Test fun guideUpdatesFromAlgorithmToFirstPageAverage() {
        val prices = mutableStateOf(emptyList<Double>())
        compose.setContent {
            MaterialTheme {
                SalesRangeCard(ReleasePriceSummary(low = 2.0, median = 15.0, high = 60.0), livePrices = prices.value)
            }
        }
        compose.onNodeWithText("Median").assertIsDisplayed()
        compose.runOnIdle { prices.value = listOf(10.0, 20.0, 90.0) }
        compose.onNodeWithText("Average").assertIsDisplayed()
        compose.onNodeWithText("Median").assertDoesNotExist()
        compose.onNodeWithText("Low").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsDisplayed()
    }
}
