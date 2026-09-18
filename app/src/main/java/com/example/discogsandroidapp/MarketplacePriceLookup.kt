package com.example.discogsandroidapp

/**
 * Retired pricing implementation.
 *
 * Marketplace pricing is intentionally handled by the shared parser in
 * MarketplaceConditionPriceProbe.kt and the two active UI screens. Keeping a
 * second WebView scanner here previously created two independent sources of
 * truth, different caching semantics, and harder-to-track pricing failures.
 *
 * This file is kept as a harmless placeholder so it can safely overwrite the
 * older implementation without requiring a manual file deletion.
 */
