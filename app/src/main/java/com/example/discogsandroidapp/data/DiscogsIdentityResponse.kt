package com.example.discogsandroidapp.data

import kotlinx.serialization.Serializable

@Serializable
data class DiscogsIdentityResponse(
    val id: Int,
    val username: String,
    val resource_url: String,
    val consumer_name: String
)