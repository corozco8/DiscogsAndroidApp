package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.InventoryListing
import com.example.discogsandroidapp.data.DiscogsOrder
import com.example.discogsandroidapp.data.ListingRelease
import com.example.discogsandroidapp.data.OrderItem
import com.example.discogsandroidapp.data.Price

internal fun currentOrderPreviewItem(order: DiscogsOrder, selected: OrderItem): OrderItem {
    val listingId = selected.id ?: selected.id_string?.toLongOrNull() ?: return selected
    return order.items.orEmpty().firstOrNull { (it.id ?: it.id_string?.toLongOrNull()) == listingId } ?: selected
}

/** Use this order's paid price, description and original listing date, even after it sold. */
internal fun OrderItem.previewListing(): InventoryListing = InventoryListing(
    id = id ?: id_string?.toLongOrNull() ?: 0,
    status = "Sold",
    condition = media_condition?.takeIf { it.isNotBlank() } ?: condition.orEmpty(),
    sleeve_condition = sleeve_condition.orEmpty(), comments = comments.orEmpty(),
    posted = posted, dateAdded = date_added,
    price = price?.let { value -> value.value?.let { Price(it, value.currency ?: "USD") } },
    release = ListingRelease(
        id = release?.id ?: 0,
        description = release?.description?.takeIf { it.isNotBlank() } ?: release?.title.orEmpty(),
        thumbnail = release?.thumbnail.orEmpty()
    )
)
