package com.example.discogsandroidapp.data

import android.content.Context
import com.example.discogsandroidapp.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ReleaseCardMetadata(
    val id: Long,
    val image: String? = null,
    val genres: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val year: Int? = null,
    val format: String = "",
    val checkedAt: Long = System.currentTimeMillis()
)

/** Shared previews need one metadata read, never marketplace prices or sales statistics. */
internal object ReleaseMetadataCache {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun get(context: Context, id: Long, token: String): ReleaseCardMetadata = mutex.withLock {
        val saved = read(context, id)
        if (saved != null && System.currentTimeMillis() - saved.checkedAt in 0 until 24 * 60 * 60_000L)
            return@withLock saved
        try {
            require(id > 0 && token.isNotBlank()) { "Release information unavailable" }
            val release = RetrofitClient.apiService.getRelease(id, "Discogs token=$token")
            metadata(release).also { save(context, it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { saved ?: throw e }
    }

    suspend fun put(context: Context, release: DiscogsRelease) {
        if (release.id != null) mutex.withLock { save(context, metadata(release)) }
    }

    private fun metadata(release: DiscogsRelease) = ReleaseCardMetadata(
        id = release.id ?: 0,
        image = release.images.orEmpty().firstOrNull()?.uri,
        genres = release.genres.orEmpty(), styles = release.styles.orEmpty(), year = release.year,
        format = release.formats.orEmpty().joinToString(" · ") { format ->
            (listOfNotNull(format.name) + format.descriptions.orEmpty()).joinToString(", ")
        }
    )

    private suspend fun read(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val preferences = context.applicationContext.getSharedPreferences("release_card_metadata_v1", Context.MODE_PRIVATE)
        runCatching { preferences.getString(id.toString(), null)?.let { json.decodeFromString<ReleaseCardMetadata>(it) } }
            .getOrNull()?.takeIf { it.id == id }
    }
    private suspend fun save(context: Context, metadata: ReleaseCardMetadata) = withContext(Dispatchers.IO) {
        val preferences = context.applicationContext.getSharedPreferences("release_card_metadata_v1", Context.MODE_PRIVATE)
        val editor = preferences.edit().putString(metadata.id.toString(), json.encodeToString(metadata))
        val existing = preferences.all.filterKeys { it != metadata.id.toString() }
        if (existing.size >= 128) existing.entries.sortedBy {
            runCatching { json.decodeFromString<ReleaseCardMetadata>(it.value as String).checkedAt }.getOrDefault(0)
        }.take(existing.size - 127).forEach { editor.remove(it.key) }
        editor.apply()
    }
}
