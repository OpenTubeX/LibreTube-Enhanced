package com.github.libretube.repo

import com.github.libretube.api.ltsync.EncryptedSyncCrypto
import com.github.libretube.api.ltsync.LibreTubeSyncServerApi
import com.github.libretube.api.ltsync.obj.PutEncryptedSyncCollection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** The OpenTubeX channelPlaybackSpeeds setting is a JSON string inside the encrypted settings list. */
internal class ChannelPlaybackSpeedSettingsSync(
    private val api: LibreTubeSyncServerApi,
    private val crypto: EncryptedSyncCrypto
) {
    private val settingKey = "channelPlaybackSpeeds"

    suspend fun syncOnLogin(local: Map<String, Float>): Map<String, Float> {
        repeat(5) { attempt ->
            val (revision, settings) = readSettings()
            val remote = settings.find { it.jsonObject["key"]?.jsonPrimitive?.content == settingKey }
            if (remote != null) return parseSpeeds(remote.jsonObject.getValue("value").jsonPrimitive.content)
            if (local.isEmpty()) return local
            settings += newEntry(local)
            try {
                putSettings(revision, settings)
                return local
            } catch (error: HttpException) {
                if (error.code() != 409 || attempt == 4) throw error
            }
        }
        error("Encrypted settings update failed")
    }

    suspend fun update(channelId: String, speed: Float?) {
        repeat(5) { attempt ->
            val (revision, settings) = readSettings()
            val index = settings.indexOfFirst { it.jsonObject["key"]?.jsonPrimitive?.content == settingKey }
            if (index < 0 && speed == null) return
            val speeds = if (index < 0) mutableMapOf() else
                parseSpeeds(settings[index].jsonObject.getValue("value").jsonPrimitive.content).toMutableMap()
            if (speeds[channelId] == speed || (speed == null && channelId !in speeds)) return
            if (speed == null) speeds.remove(channelId) else speeds[channelId] = speed
            if (index < 0) settings += newEntry(speeds) else {
                val current = settings[index].jsonObject
                settings[index] = JsonObject(current + mapOf(
                    "value" to JsonPrimitive(encodeSpeeds(speeds)),
                    "updatedAt" to JsonPrimitive(System.currentTimeMillis())
                ))
            }
            try {
                putSettings(revision, settings)
                return
            } catch (error: HttpException) {
                if (error.code() != 409 || attempt == 4) throw error
            }
        }
        error("Encrypted settings update failed")
    }

    private suspend fun readSettings(): Pair<Long, MutableList<JsonElement>> {
        val response = api.getEncryptedSyncCollection("settings")
        val settings = response.payload?.let { crypto.decrypt(it).jsonArray.toMutableList() }
            ?: mutableListOf()
        return response.revision to settings
    }

    private suspend fun putSettings(revision: Long, settings: List<JsonElement>) {
        api.putEncryptedSyncCollection(
            "settings", PutEncryptedSyncCollection(revision, crypto.encrypt(JsonArray(settings)))
        )
    }

    private fun newEntry(speeds: Map<String, Float>) = JsonObject(mapOf(
        "key" to JsonPrimitive(settingKey),
        "value" to JsonPrimitive(encodeSpeeds(speeds)),
        "updatedAt" to JsonPrimitive(System.currentTimeMillis())
    ))

    private fun encodeSpeeds(speeds: Map<String, Float>) =
        JsonObject(speeds.mapValues { JsonPrimitive(it.value) }).toString()

    private fun parseSpeeds(value: String): Map<String, Float> =
        Json.parseToJsonElement(value).jsonObject.mapNotNull { (channelId, speed) ->
            speed.jsonPrimitive.content.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
                ?.let { channelId to it }
        }.toMap()
}
