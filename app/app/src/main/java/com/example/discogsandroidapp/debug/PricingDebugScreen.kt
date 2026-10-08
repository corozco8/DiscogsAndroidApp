package com.example.discogsandroidapp.debug

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEBUG_EMAIL = "corozco5@yahoo.com"

@Composable
fun PricingDebugScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(PricingDebugSnapshot(0, 0L, "Loading…")) }

    suspend fun reload() {
        snapshot = withContext(Dispatchers.IO) { PricingDebugRepository.snapshot(context) }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pricing Debug", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Calibration log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Whenever the app has both an algorithm price and a real live marketplace price for the selected condition, one comparison is stored locally. Only identical observations within 15 seconds are ignored; repeat checks later are kept. The export retains all recorded rows.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "${snapshot.comparisonCount} comparisons · ${formatBytes(snapshot.fileSizeBytes)}",
            style = MaterialTheme.typography.titleMedium
        )

        Button(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { PricingDebugRepository.exportFile(context) }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(DEBUG_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, "Discogs pricing model debug data")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Attached is the pricing model comparison log exported from the Discogs Android app."
                        )
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(Intent.createChooser(intent, "Email pricing debug file"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            "No app is available to send the file.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            enabled = snapshot.comparisonCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Email, contentDescription = null)
            Text("  Email debug file to $DEBUG_EMAIL")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { PricingDebugRepository.clear(context) }
                    reload()
                }
            },
            enabled = snapshot.comparisonCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null)
            Text("  Clear debug data")
        }

        HorizontalDivider()
        Text("Preview (last 100)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            snapshot.preview,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
