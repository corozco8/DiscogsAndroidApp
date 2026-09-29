package com.example.discogsandroidapp.releases

import com.example.discogsandroidapp.data.Price
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
