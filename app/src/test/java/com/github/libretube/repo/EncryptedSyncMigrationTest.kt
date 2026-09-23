package com.github.libretube.repo

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.ltsync.EncryptedSyncCrypto
import com.github.libretube.api.ltsync.LibreTubeSyncServerApi
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.net.InetSocketAddress

class EncryptedSyncMigrationTest {
    @Test
    fun copiesLegacyPlaintextCollectionsBeforeTheyAreRemoved() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val uploaded = mutableMapOf<String, String>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val collection = path.removePrefix("/v1/encrypted_sync/")
            val body = when {
                path == "/v1/encrypted_sync" ->
                    """{"collections":[],"legacy_data":true,"legacy_encrypted_data":false}"""
                path.startsWith("/v1/encrypted_sync/") && exchange.requestMethod == "GET" ->
                    """{"collection":"$collection","revision":0,"payload":null}"""
                path.startsWith("/v1/encrypted_sync/") && exchange.requestMethod == "PUT" -> {
                    uploaded[collection] = JsonHelper.json.parseToJsonElement(
                        exchange.requestBody.bufferedReader().readText()
                    ).jsonObject.getValue("payload").jsonPrimitive.content
                    """{"collection":"$collection","revision":1,"payload":null}"""
                }
                path == "/v1/subscriptions/" ->
                    """[{"id":"UC123","name":"Channel","avatar":null,"verified":false}]"""
                path == "/v1/watch_history/" -> {
                    assertTrue(exchange.requestURI.query.contains("page_size=100"))
                    assertTrue(exchange.requestURI.query.contains("pageSize=100"))
                    "[]"
                }
                path in setOf(
                    "/v1/playlists/", "/v1/subscriptions/groups/", "/v1/playlist_bookmarks/"
                ) -> "[]"
                else -> error("Unexpected request: $path")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl("http://127.0.0.1:${server.address.port}/")
                .addConverterFactory(JsonHelper.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create<LibreTubeSyncServerApi>()
            val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)

            EncryptedSyncServerUserDataRepository(api, crypto).migrateLegacyData()

            assertEquals(
                setOf("subscriptions", "playlists", "history", "profiles", "playlistBookmarks"),
                uploaded.keys
            )
            val subscriptions = crypto.decrypt(uploaded.getValue("subscriptions")).jsonArray
            assertEquals("UC123", subscriptions[0].jsonObject.getValue("id").jsonPrimitive.content)
            assertTrue(crypto.decrypt(uploaded.getValue("playlists")).jsonArray.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun retriesAConflictingUpdateWithoutDroppingAnotherDeviceSubscription() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        var revision = 1L
        var payload = crypto.encrypt(JsonHelper.json.parseToJsonElement(
            """[{"id":"base","name":"Base","avatar":null,"verified":false}]"""
        ))
        var conflicted = false
        server.createContext("/v1/encrypted_sync/subscriptions") { exchange ->
            val body: String
            val status: Int
            if (exchange.requestMethod == "GET") {
                body = """{"collection":"subscriptions","revision":$revision,"payload":${JsonHelper.json.encodeToString(payload)}}"""
                status = 200
            } else if (!conflicted) {
                conflicted = true
                revision++
                payload = crypto.encrypt(JsonHelper.json.parseToJsonElement(
                    """[{"id":"base","name":"Base","avatar":null,"verified":false},{"id":"other","name":"Other","avatar":null,"verified":false}]"""
                ))
                body = "conflict"
                status = 409
            } else {
                val request = JsonHelper.json.parseToJsonElement(
                    exchange.requestBody.bufferedReader().readText()
                ).jsonObject
                assertEquals(revision.toString(), request.getValue("revision").jsonPrimitive.content)
                payload = request.getValue("payload").jsonPrimitive.content
                revision++
                body = """{"collection":"subscriptions","revision":$revision,"payload":null}"""
                status = 200
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl("http://127.0.0.1:${server.address.port}/")
                .addConverterFactory(JsonHelper.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create<LibreTubeSyncServerApi>()

            EncryptedSyncServerUserDataRepository(api, crypto)
                .subscribe("new", "New", null, false)

            assertTrue(conflicted)
            val ids = crypto.decrypt(payload).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(setOf("base", "other", "new"), ids.toSet())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun keepsHistoryDateAndValidPositionsWhenMarkingWatched() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        var revision = 1L
        var payload = crypto.encrypt(JsonHelper.json.parseToJsonElement(
            """[{"metadata":{"added_date":123456,"watched_state":"watching","position_millis":1000},"video":{"duration":300,"id":"video-1","thumbnail_url":"","title":"Video","upload_date":0,"uploader":{"avatar":null,"id":"channel-1","name":"Channel","verified":false}}}]"""
        ))
        server.createContext("/v1/encrypted_sync/history") { exchange ->
            val body = if (exchange.requestMethod == "GET") {
                """{"collection":"history","revision":$revision,"payload":${JsonHelper.json.encodeToString(payload)}}"""
            } else {
                val request = JsonHelper.json.parseToJsonElement(
                    exchange.requestBody.bufferedReader().readText()
                ).jsonObject
                assertEquals(revision.toString(), request.getValue("revision").jsonPrimitive.content)
                payload = request.getValue("payload").jsonPrimitive.content
                revision++
                """{"collection":"history","revision":$revision,"payload":null}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl("http://127.0.0.1:${server.address.port}/")
                .addConverterFactory(JsonHelper.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create<LibreTubeSyncServerApi>()
            val repository = EncryptedSyncServerUserDataRepository(api, crypto)

            repository.updateWatchHistoryEntry(
                WatchHistoryEntryMetadata("video-1", -1, true, Long.MAX_VALUE)
            )
            val watched = crypto.decrypt(payload).jsonArray.single().jsonObject.getValue("metadata").jsonObject
            assertEquals("123456", watched.getValue("added_date").jsonPrimitive.content)
            assertEquals("completed", watched.getValue("watched_state").jsonPrimitive.content)
            assertEquals("0", watched.getValue("position_millis").jsonPrimitive.content)

            val entry = requireNotNull(repository.getFromWatchHistory("video-1"))
            repository.addToWatchHistory(entry.copy(
                metadata = entry.metadata.copy(positionMillis = 4_294_967_296L)
            ))
            val updated = crypto.decrypt(payload).jsonArray.single().jsonObject.getValue("metadata").jsonObject
            assertEquals("4294967296", updated.getValue("position_millis").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }
}
