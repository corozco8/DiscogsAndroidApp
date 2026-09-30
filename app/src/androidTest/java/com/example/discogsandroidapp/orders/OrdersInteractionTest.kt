package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.DiscogsOrder
import com.example.discogsandroidapp.data.OrderItem
import com.example.discogsandroidapp.data.OrderReleaseInfo
import com.example.discogsandroidapp.releases.OrderMessagesUiState

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrdersInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun itemPreviewKeepsTheOrderAndItsListedDateInPlace() {
        var releaseOpens = 0
        compose.setContent {
            MaterialTheme {
                OrderDetailScreen(
                    order = DiscogsOrder(id = "preview-test", status = "Payment Received", items = listOf(
                        OrderItem(release = OrderReleaseInfo(id = 1234, title = "Preview record", thumbnail = ""),
                            posted = "2024-01-02", comments = "Original sleeve")
                    )),
                    messageState = OrderMessagesUiState.Idle,
                    onBackClick = {}, onStatusChange = {}, onItemClick = { releaseOpens++ },
                    onSendMessage = { _, _ -> }, onLeaveBuyerFeedback = {}
                )
            }
        }
        compose.onNodeWithText("Listed Jan 2, 2024").assertExists()
        compose.onNodeWithText("Preview record").performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNodeWithText("Order #preview-test").assertIsDisplayed()
        compose.onNodeWithText("Listed Jan 2, 2024").assertExists()
        compose.runOnIdle { assertEquals(0, releaseOpens) }
    }

    @Test fun inProgressShowsFeedbackDisablesActionsAndAllowsRetryAfterFailure() {
        val update = mutableStateOf<OrderStatusUpdateState?>(null)
        var writes = 0
        compose.setContent {
            MaterialTheme {
                OrderDetailScreen(
                    order = DiscogsOrder(id = "test-order", status = "Payment Received", items = listOf(
                        OrderItem(release = OrderReleaseInfo(title = "Test record", thumbnail = ""))
                    )),
                    messageState = OrderMessagesUiState.Idle, statusUpdate = update.value,
                    onBackClick = {}, onStatusChange = { status ->
                        writes++
                        update.value = OrderStatusUpdateState(settingStatus = status)
                    }, onItemClick = {}, onSendMessage = { _, _ -> }, onLeaveBuyerFeedback = {}
                )
            }
        }
        compose.onNodeWithText("In Progress").performScrollTo().performClick()
        compose.onNodeWithText("Setting In Progress…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Mark as Shipped").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, writes); update.value = OrderStatusUpdateState(error = "Update failed") }
        compose.onNodeWithText("Update failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In Progress").performScrollTo().assertIsEnabled()
    }
}
