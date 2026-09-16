package com.example.discogsandroidapp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SellerLocalRepository(context: Context) {
    private val dao =
        SellerLocalDatabase
            .getInstance(context)
            .sellerDao()

    val inventory = dao.observeInventory()
    val orders = dao.observeOrders()
    val orderItems = dao.observeOrderItems()
    val inventorySyncState = dao.observeSyncState(INVENTORY_SYNC_KEY)
    val orderSyncState = dao.observeSyncState(ORDERS_SYNC_KEY)

    suspend fun syncAll(token: String) {
        syncInventory(token)
        syncRecentOrders(token)
    }

    suspend fun syncInventory(token: String) {
        require(token.isNotBlank()) {
            "Discogs token is missing"
        }

        val authHeader = "Discogs token=$token"
        val identity =
            RetrofitClient.apiService.getIdentity(authHeader)

        val existingById =
            dao.getInventorySnapshot()
                .associateBy { it.listingId }

        val syncStartedAt = System.currentTimeMillis()
        var page = 1
        var totalPages = 1
        var syncedCount = 0

        do {
            val response =
                RetrofitClient.apiService.getInventory(
                    username = identity.username,
                    authHeader = authHeader,
                    status = "For Sale",
                    sort = "listed",
                    sortOrder = "asc",
                    page = page,
                    perPage = 100
                )

            totalPages = response.pagination.pages.coerceAtLeast(1)

            val mapped =
                response.listings.map { listing ->
                    val existing = existingById[listing.id]
                    val postedRaw =
                        listing.posted
                            ?: listing.dateAdded

                    val parsedListedAt =
                        parseDiscogsDate(postedRaw)

                    val firstSeen =
                        existing?.firstSeenAtEpochMs
                            ?: syncStartedAt

                    LocalInventoryListingEntity(
                        listingId = listing.id,
                        releaseId = listing.release.id,
                        artist = listing.release.artist,
                        title = listing.release.title
                            .ifBlank {
                                listing.release.description
                            },
                        thumbnail = listing.release.thumbnail,
                        status = listing.status,
                        mediaCondition = listing.condition,
                        sleeveCondition = listing.sleeve_condition,
                        comments = listing.comments,
                        priceValue = listing.price?.value,
                        currency = listing.price?.currency ?: "USD",
                        postedRaw = postedRaw,
                        listedAtEpochMs =
                            parsedListedAt
                                ?: existing?.listedAtEpochMs
                                ?: firstSeen,
                        listedDateIsExact =
                            parsedListedAt != null ||
                                existing?.listedDateIsExact == true,
                        firstSeenAtEpochMs = firstSeen,
                        lastSeenAtEpochMs = syncStartedAt
                    )
                }

            dao.upsertInventory(mapped)
            syncedCount += mapped.size
            page++
        } while (page <= totalPages)

        dao.deleteInventoryNotSeenInSync(syncStartedAt)
        dao.upsertSyncState(
            LocalSyncStateEntity(
                key = INVENTORY_SYNC_KEY,
                lastSuccessfulSyncAtEpochMs = System.currentTimeMillis(),
                itemCount = syncedCount,
                note = "Active For Sale inventory"
            )
        )
    }

    suspend fun syncRecentOrders(
        token: String,
        recentPages: Int = 5,
        backfillPagesPerRun: Int = 5
    ) {
        require(token.isNotBlank()) {
            "Discogs token is missing"
        }

        val authHeader = "Discogs token=$token"
        var totalPages = 1

        // Always refresh the newest orders first so day-to-day seller data is
        // current even while the older order archive is still being backfilled.
        var recentPage = 1
        do {
            val response =
                RetrofitClient.apiService.getOrders(
                    token = authHeader,
                    status = null,
                    page = recentPage,
                    perPage = 100,
                    sortOrder = "desc"
                )

            totalPages =
                response.pagination?.pages
                    ?.coerceAtLeast(1)
                    ?: 1

            persistOrders(
                response.orders.orEmpty()
            )

            recentPage++
        } while (
            recentPage <= totalPages &&
            recentPage <= recentPages
        )

        // Customer History and analytics become more useful over time without
        // downloading thousands of old orders in one huge burst. Every worker
        // run resumes a small chunk of the older history where the last run left off.
        val previousState =
            dao.getSyncState(ORDERS_SYNC_KEY)

        var backfillPage =
            previousState?.nextBackfillPage
                ?.takeIf { it > recentPages }
                ?: (recentPages + 1)

        var pagesBackfilled = 0

        while (
            backfillPage <= totalPages &&
            pagesBackfilled < backfillPagesPerRun
        ) {
            val response =
                RetrofitClient.apiService.getOrders(
                    token = authHeader,
                    status = null,
                    page = backfillPage,
                    perPage = 100,
                    sortOrder = "desc"
                )

            persistOrders(
                response.orders.orEmpty()
            )

            backfillPage++
            pagesBackfilled++
        }

        val nextBackfillPage =
            if (backfillPage <= totalPages) {
                backfillPage
            } else {
                null
            }

        val cachedOrderCount =
            dao.countOrders()

        dao.upsertSyncState(
            LocalSyncStateEntity(
                key = ORDERS_SYNC_KEY,
                lastSuccessfulSyncAtEpochMs =
                    System.currentTimeMillis(),
                itemCount = cachedOrderCount,
                note =
                    if (nextBackfillPage == null) {
                        "Full order history cached"
                    } else {
                        "Recent orders synced • history backfill page $nextBackfillPage of $totalPages"
                    },
                nextBackfillPage = nextBackfillPage,
                totalPages = totalPages
            )
        )
    }

    private suspend fun persistOrders(
        orders: List<DiscogsOrder>
    ) {
        val now = System.currentTimeMillis()

        orders.forEach { order ->
            val orderId = order.id ?: return@forEach
            val createdAt =
                parseDiscogsDate(order.created)
                    ?: 0L
            val lastActivityAt =
                parseDiscogsDate(order.lastActivity)
                    ?: createdAt

            val currency =
                order.total?.currency
                    ?: order.shipping?.currency
                    ?: order.items
                        .orEmpty()
                        .firstNotNullOfOrNull {
                            it.price?.currency
                        }
                    ?: "USD"

            dao.upsertOrders(
                listOf(
                    LocalOrderEntity(
                        orderId = orderId,
                        status = order.status.orEmpty(),
                        createdRaw = order.created,
                        createdAtEpochMs = createdAt,
                        lastActivityRaw = order.lastActivity,
                        lastActivityAtEpochMs = lastActivityAt,
                        buyerId = order.buyer?.id,
                        buyerUsername =
                            order.buyer?.username
                                ?: "Unknown buyer",
                        shippingValue = order.shipping?.value,
                        feeValue = order.fee?.value,
                        totalValue = order.total?.value,
                        currency = currency,
                        syncedAtEpochMs = now
                    )
                )
            )

            val mappedItems =
                order.items
                    .orEmpty()
                    .mapIndexed { index, item ->
                        val itemKey =
                            item.id?.toString()
                                ?: item.id_string
                                ?: "${item.release?.id ?: 0L}-$index"

                        LocalOrderItemEntity(
                            orderId = orderId,
                            itemKey = itemKey,
                            listingId = item.id,
                            releaseId = item.release?.id,
                            title =
                                item.release?.title
                                    ?: item.release?.description
                                    ?: "Unknown item",
                            description =
                                item.release?.description.orEmpty(),
                            mediaCondition =
                                item.condition
                                    ?: item.media_condition,
                            sleeveCondition = item.sleeve_condition,
                            comments = item.comments,
                            priceValue = item.price?.value,
                            currency =
                                item.price?.currency
                                    ?: currency
                        )
                    }

            dao.replaceOrderItems(
                orderId = orderId,
                items = mappedItems
            )
        }
    }

    companion object {
        const val INVENTORY_SYNC_KEY = "inventory"
        const val ORDERS_SYNC_KEY = "orders"

        fun parseDiscogsDate(value: String?): Long? {
            if (value.isNullOrBlank()) {
                return null
            }

            val formats =
                listOf(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd"
                )

            for (pattern in formats) {
                val parsed =
                    runCatching {
                        SimpleDateFormat(
                            pattern,
                            Locale.US
                        ).apply {
                            isLenient = false
                            timeZone =
                                TimeZone.getTimeZone("UTC")
                        }.parse(value)?.time
                    }.getOrNull()

                if (parsed != null) {
                    return parsed
                }
            }

            return null
        }
    }
}
