package com.example.discogsandroidapp

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "seller_inventory")
data class LocalInventoryListingEntity(
    @PrimaryKey val listingId: Long,
    val releaseId: Long,
    val artist: String,
    val title: String,
    val thumbnail: String,
    val status: String,
    val mediaCondition: String,
    val sleeveCondition: String,
    val comments: String,
    val priceValue: Double?,
    val currency: String,
    val postedRaw: String?,
    val listedAtEpochMs: Long,
    val listedDateIsExact: Boolean,
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long
)

@Entity(tableName = "seller_orders")
data class LocalOrderEntity(
    @PrimaryKey val orderId: String,
    val status: String,
    val createdRaw: String?,
    val createdAtEpochMs: Long,
    val lastActivityRaw: String?,
    val lastActivityAtEpochMs: Long,
    val buyerId: Long?,
    val buyerUsername: String,
    val shippingValue: Double?,
    val feeValue: Double?,
    val totalValue: Double?,
    val currency: String,
    val syncedAtEpochMs: Long,
    val buyerCountryCode: String? = null
)

@Entity(
    tableName = "seller_order_items",
    primaryKeys = ["orderId", "itemKey"]
)
data class LocalOrderItemEntity(
    val orderId: String,
    val itemKey: String,
    val listingId: Long?,
    val releaseId: Long?,
    val title: String,
    val description: String,
    val mediaCondition: String?,
    val sleeveCondition: String?,
    val comments: String?,
    val priceValue: Double?,
    val currency: String,
    val listedAtEpochMs: Long? = null
)

@Entity(tableName = "seller_sync_state")
data class LocalSyncStateEntity(
    @PrimaryKey val key: String,
    val lastSuccessfulSyncAtEpochMs: Long,
    val itemCount: Int,
    val note: String? = null,
    val nextBackfillPage: Int? = null,
    val totalPages: Int? = null
)

@Dao
interface SellerLocalDao {
    @Query("SELECT * FROM seller_inventory ORDER BY listedAtEpochMs ASC, listingId ASC")
    fun observeInventory(): Flow<List<LocalInventoryListingEntity>>

    @Query("DELETE FROM seller_inventory WHERE listingId = :id")
    suspend fun deleteInventoryListing(id: Long)

    @Query("UPDATE seller_inventory SET priceValue = :price, mediaCondition = :media, sleeveCondition = :sleeve, comments = :comments WHERE listingId = :id")
    suspend fun updateInventoryListing(id: Long, price: Double, media: String, sleeve: String, comments: String)

    @Query("SELECT * FROM seller_inventory")
    suspend fun getInventorySnapshot(): List<LocalInventoryListingEntity>

    @Upsert
    suspend fun upsertInventory(listings: List<LocalInventoryListingEntity>)

    @Query("DELETE FROM seller_inventory WHERE lastSeenAtEpochMs < :syncStartedAtEpochMs")
    suspend fun deleteInventoryNotSeenInSync(syncStartedAtEpochMs: Long)

    @Transaction
    suspend fun replaceInventorySnapshot(
        listings: List<LocalInventoryListingEntity>,
        syncStartedAtEpochMs: Long
    ) {
        if (listings.isNotEmpty()) {
            upsertInventory(listings)
        }
        deleteInventoryNotSeenInSync(syncStartedAtEpochMs)
    }

    @Query("SELECT * FROM seller_orders ORDER BY createdAtEpochMs DESC")
    fun observeOrders(): Flow<List<LocalOrderEntity>>

    @Query("SELECT * FROM seller_orders WHERE orderId IN (:orderIds)")
    suspend fun getOrderSnapshots(orderIds: List<String>): List<LocalOrderEntity>

    @Query("SELECT * FROM seller_order_items WHERE orderId IN (:orderIds)")
    suspend fun getOrderItemsSnapshots(orderIds: List<String>): List<LocalOrderItemEntity>

    @Transaction
    suspend fun saveOrderSnapshot(order: LocalOrderEntity, items: List<LocalOrderItemEntity>) {
        upsertOrders(listOf(order))
        replaceOrderItems(order.orderId, items)
    }

    @Upsert
    suspend fun upsertOrders(orders: List<LocalOrderEntity>)

    @Query("SELECT * FROM seller_order_items")
    fun observeOrderItems(): Flow<List<LocalOrderItemEntity>>

    @Query("DELETE FROM seller_order_items WHERE orderId = :orderId")
    suspend fun deleteItemsForOrder(orderId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<LocalOrderItemEntity>)

    @Transaction
    suspend fun replaceOrderItems(
        orderId: String,
        items: List<LocalOrderItemEntity>
    ) {
        deleteItemsForOrder(orderId)
        if (items.isNotEmpty()) {
            insertOrderItems(items)
        }
    }

    @Query("SELECT * FROM seller_sync_state WHERE `key` = :key LIMIT 1")
    fun observeSyncState(key: String): Flow<LocalSyncStateEntity?>

    @Query("SELECT * FROM seller_sync_state WHERE `key` = :key LIMIT 1")
    suspend fun getSyncState(key: String): LocalSyncStateEntity?

    @Query("SELECT COUNT(*) FROM seller_orders")
    suspend fun countOrders(): Int

    @Upsert
    suspend fun upsertSyncState(state: LocalSyncStateEntity)
}

@Database(
    entities = [
        LocalInventoryListingEntity::class,
        LocalOrderEntity::class,
        LocalOrderItemEntity::class,
        LocalSyncStateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SellerLocalDatabase : RoomDatabase() {
    abstract fun sellerDao(): SellerLocalDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE seller_orders ADD COLUMN buyerCountryCode TEXT")
                db.execSQL("ALTER TABLE seller_order_items ADD COLUMN listedAtEpochMs INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: SellerLocalDatabase? = null

        fun getInstance(context: Context): SellerLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SellerLocalDatabase::class.java,
                    "discogs_seller_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
