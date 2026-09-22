package com.example.discogsandroidapp

internal fun nextOrderHistoryPage(previous: LocalSyncStateEntity?, recentPages: Int, totalPages: Int): Int = when {
    // A completed archive needs only the recent-order refresh, not another full download.
    previous != null && previous.nextBackfillPage == null && previous.totalPages != null -> totalPages + 1
    // Overlap the last archived page since new orders shift descending pagination.
    previous?.nextBackfillPage != null -> maxOf(recentPages + 1, previous.nextBackfillPage - 1)
    else -> recentPages + 1
}
