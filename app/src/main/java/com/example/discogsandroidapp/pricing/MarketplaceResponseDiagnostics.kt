package com.example.discogsandroidapp.pricing

/** Keep only support diagnostics, never cookies, tokens or full response bodies. */
internal fun marketplaceErrorDetails(
    url: String,
    status: Int,
    headers: Map<String, String>,
    timestamp: String = java.time.Instant.now().toString()
): String = buildString {
    appendLine("Marketplace response at $timestamp")
    appendLine("URL: $url")
    appendLine("HTTP status: $status")
    listOf("server", "cf-ray", "cf-mitigated", "retry-after", "date").forEach { name ->
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let {
            appendLine("$name: ${it.value}")
        }
    }
    append("A Cloudflare header alone does not identify who issued the rejection.")
}
