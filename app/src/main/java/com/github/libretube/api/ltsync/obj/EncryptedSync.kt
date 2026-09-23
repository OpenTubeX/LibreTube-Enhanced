package com.github.libretube.api.ltsync.obj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val capabilities: SyncCapabilities = SyncCapabilities())

@Serializable
data class SyncCapabilities(
    @SerialName("encrypted_sync") val encryptedSync: Int = 0
)

@Serializable
data class EncryptedSyncManifest(
    val collections: List<EncryptedSyncRevision> = emptyList(),
    @SerialName("legacy_data") val legacyData: Boolean = false,
    @SerialName("legacy_encrypted_data") val legacyEncryptedData: Boolean = false
)

@Serializable
data class EncryptedSyncRevision(val collection: String, val revision: Long)

@Serializable
data class EncryptedSyncCollection(
    val collection: String,
    val revision: Long,
    val payload: String? = null
)

@Serializable
data class PutEncryptedSyncCollection(val revision: Long, val payload: String)
