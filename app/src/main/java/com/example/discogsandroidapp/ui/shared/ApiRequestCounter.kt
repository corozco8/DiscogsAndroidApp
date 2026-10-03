package com.example.discogsandroidapp.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.discogsandroidapp.network.DiscogsApiTraffic
import com.example.discogsandroidapp.pricing.MarketplaceTrafficReport
import com.example.discogsandroidapp.pricing.MarketplaceTraffic
import kotlinx.coroutines.delay

/** Small traffic counter. Its local report never makes a network request. */
@Composable
internal fun ApiRequestCounter(modifier: Modifier = Modifier) {
    val requests = DiscogsApiTraffic.requests
    val count by requests.count.collectWhileStarted()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showReport by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                requests.tick()
                delay(1_000)
            }
        }
    }
    Text(
        text = count.toString(),
        modifier = modifier.semantics {
            contentDescription = "$count API requests from this app in the last 60 seconds"
        }.clickable {
            report = MarketplaceTrafficReport.log.report(requests.count.value, MarketplaceTraffic.policy.automaticChecksSuspended)
            showReport = true
        }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        fontSize = 11.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary
    )
    if (showReport) {
        LaunchedEffect(Unit) {
            while (true) {
                report = MarketplaceTrafficReport.log.report(requests.count.value, MarketplaceTraffic.policy.automaticChecksSuspended)
                delay(1_000)
            }
        }
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Request activity") },
            text = { Text(report, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Request activity", report))
                android.widget.Toast.makeText(context, "Request activity copied", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Copy") } }
        )
    }
}
