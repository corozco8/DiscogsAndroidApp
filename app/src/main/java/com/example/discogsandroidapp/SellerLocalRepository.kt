package com.example.discogsandroidapp

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun syncInventory(token: String) =
        inventorySyncMutex.withLock {
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
            val completeSnapshot =
                mutableListOf<LocalInventoryListingEntity>()

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
                        // Discogs now exposes more than one useful listing-date
                        // field. `posted` can move forward when inventory is expired
                        // and relisted, while `date_added` is the better candidate for
                        // the original age of the listing when Discogs provides it.
                        // Parse every date we have and keep the oldest valid one.
                        val currentDateAddedAt =
                            parseDiscogsDate(listing.dateAdded)
                        val currentPostedAt =
                            parseDiscogsDate(listing.posted)
                        val previousRawAt =
                            parseDiscogsDate(existing?.postedRaw)

                        val firstSeen =
                            existing?.firstSeenAtEpochMs
                                ?: syncStartedAt

                        val exactCandidates =
                            listOfNotNull(
                                currentDateAddedAt,
                                currentPostedAt,
                                previousRawAt,
                                existing
                                    ?.takeIf { it.listedDateIsExact }
                                    ?.listedAtEpochMs
                                    ?.takeIf { it > 0L }
                            )

                        val earliestExactListedAt =
                            exactCandidates.minOrNull()

                        // Never let a refresh make an item younger. If Discogs does
                        // not expose any exact date, retain the earliest local date we
                        // already know. This cannot reconstruct history that Discogs
                        // never returned, but it prevents future relists from erasing
                        // age again.
                        val earliestKnownListedAt =
                            listOfNotNull(
                                earliestExactListedAt,
                                existing?.listedAtEpochMs
                                    ?.takeIf { it > 0L },
                                firstSeen.takeIf { it > 0L }
                            ).minOrNull()
                                ?: syncStartedAt

                        val listedDateIsExact =
                            earliestExactListedAt != null &&
                                    earliestExactListedAt == earliestKnownListedAt

                        // Preserve the raw string that corresponds to the oldest
                        // Discogs-provided timestamp when possible. This gives later
                        // parser improvements another chance to recover the real date.
                        val postedRaw =
                            listOfNotNull(
                                listing.dateAdded,
                                listing.posted,
                                existing?.postedRaw
                            ).minByOrNull { raw ->
                                parseDiscogsDate(raw)
                                    ?: Long.MAX_VALUE
                            }

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
                            listedAtEpochMs = earliestKnownListedAt,
                            listedDateIsExact = listedDateIsExact,
                            firstSeenAtEpochMs = firstSeen,
                            lastSeenAtEpochMs = syncStartedAt
                        )
                    }

                completeSnapshot.addAll(mapped)
                syncedCount += mapped.size
                page++
            } while (page <= totalPages)

            // Publish the complete inventory snapshot atomically only after every
            // network page has succeeded. A failed/partial sync leaves the previous
            // valid local snapshot untouched.
            dao.replaceInventorySnapshot(
                listings = completeSnapshot,
                syncStartedAtEpochMs = syncStartedAt
            )

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
        backfillPagesPerRun: Int = Int.MAX_VALUE
    ) = ordersSyncMutex.withLock {
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

        var backfillPage = nextOrderHistoryPage(
            previousState, recentPages, totalPages
        )

        suspend fun checkpoint(nextPage: Int) {
            val next = nextPage.takeIf { it <= totalPages }
            dao.upsertSyncState(LocalSyncStateEntity(
                key = ORDERS_SYNC_KEY,
                lastSuccessfulSyncAtEpochMs = System.currentTimeMillis(),
                itemCount = dao.countOrders(),
                note = if (next == null) "Full order history cached" else "Downloading order history: page $next of $totalPages",
                nextBackfillPage = next,
                totalPages = totalPages
            ))
        }
        checkpoint(backfillPage)

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
            checkpoint(backfillPage)
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
        private val inventorySyncMutex = Mutex()
        private val ordersSyncMutex = Mutex()

        const val INVENTORY_SYNC_KEY = "inventory"
        const val ORDERS_SYNC_KEY = "orders"

        fun parseDiscogsDate(value: String?): Long? {
            if (value.isNullOrBlank()) {
                return null
            }

            // Discogs normally returns ISO-8601 offsets such as
            // 2017-02-07T23:43:01-08:00. Be tolerant of fractional seconds
            // longer than milliseconds and of offsets without the colon.
            val normalized =
                value.trim().replace(
                    Regex("(\\.\\d{3})\\d+(?=Z$|[+-]\\d{2}:?\\d{2}$)"),
                    "$1"
                )

            val formats =
                listOf(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
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
                        }.parse(normalized)?.time
                    }.getOrNull()

                if (parsed != null) {
                    return parsed
                }
            }

            return null
        }
    }
}
