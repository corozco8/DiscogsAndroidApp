package com.example.discogsandroidapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MasterVersionsResponse(
    val pagination: DiscogsPagination? = null,
    val versions: List<MasterVersion> = emptyList()
)

@Serializable
data class MasterVersion(
    val id: Long,
    val title: String = "",
    val status: String? = null,
    val thumb: String? = null,
    val format: String? = null,
    val country: String? = null,
    val label: String? = null,
    val released: String? = null,

    @SerialName("major_formats")
    val majorFormats: List<String> = emptyList(),

    val catno: String? = null
)
