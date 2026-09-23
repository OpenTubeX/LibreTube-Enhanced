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
    fun playlistCountUsesSyncedVideosWhenHeaderOmitsCount() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        val video = """{"duration":100,"id":"video-1","thumbnail_url":"","title":"Video","upload_date":0,"uploader":{"id":"channel-1","name":"Channel","verified":false}}"""
        val playlist = """[
            {"playlist":{"id":"playlist-1","title":"Favorites","description":""},"videos":[$video,${video.replace("video-1", "video-2")}]},
            {"playlist":{"id":"playlist-2","title":"Saved","description":"","video_count":3},"videos":[$video]}
        ]"""
        val payload = crypto.encrypt(JsonHelper.json.parseToJsonElement(playlist))
        server.createContext("/v1/encrypted_sync/playlists") { exchange ->
            val body = """{"collection":"playlists","revision":1,"payload":${JsonHelper.json.encodeToString(payload)}}"""
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

            assertEquals(listOf(2L, 3L), repository.getPlaylists().map { it.videos })
            assertEquals(2, repository.getPlaylist("playlist-1").videos)
            assertEquals(3, repository.getPlaylist("playlist-2").videos)
        } finally {
            server.stop(0)
        }
    }

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
                path == "/v1/playlists/" ->
                    """[{"id":"playlist-1","title":"Favorites","description":""}]"""
                path == "/v1/playlists/playlist-1" ->
                    """{"playlist":{"id":"playlist-1","title":"Favorites","description":""},"videos":[]}"""
                path == "/v1/watch_history/" -> {
                    assertTrue(exchange.requestURI.query.contains("page_size=100"))
                    assertTrue(exchange.requestURI.query.contains("pageSize=100"))
                    """[{"metadata":{"added_date":123456,"watched_state":"watching","position_millis":1000},"video":{"duration":300,"id":"video-1","thumbnail_url":"","title":"Video","upload_date":0,"uploader":{"avatar":null,"id":"channel-1","name":"Channel","verified":false}}}]"""
                }
                path == "/v1/subscriptions/groups/" ->
                    """[{"group":{"id":"group-1","title":"Group"},"channels":[]}]"""
                path == "/v1/playlist_bookmarks/" ->
                    """[{"playlist":{"id":"bookmark-1","title":"Bookmark","description":""},"uploader":{"id":"channel-1","name":"Channel"}}]"""
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
            assertEquals("playlist-1", crypto.decrypt(uploaded.getValue("playlists")).jsonArray[0]
                .jsonObject.getValue("playlist").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("video-1", crypto.decrypt(uploaded.getValue("history")).jsonArray[0]
                .jsonObject.getValue("video").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("group-1", crypto.decrypt(uploaded.getValue("profiles")).jsonArray[0]
                .jsonObject.getValue("group").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("bookmark-1", crypto.decrypt(uploaded.getValue("playlistBookmarks")).jsonArray[0]
                .jsonObject.getValue("playlist").jsonObject.getValue("id").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun leavesLegacyPlaybackSpeedsUntouchedWithoutAccessingDeprecatedCollection() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        val legacyDocument = JsonHelper.json.parseToJsonElement(
            """{"playbackSpeeds":[{"channel_id":"UC123","playback_speed":1.25}]}"""
        )
        val legacyPayload = crypto.encrypt(legacyDocument)
        var storedLegacyPayload = legacyPayload
        val requests = mutableListOf<Pair<String, String>>()
        server.createContext("/v1/encrypted_sync") { exchange ->
            requests += exchange.requestMethod to exchange.requestURI.path
            val (status, body) = when (exchange.requestURI.path) {
                "/v1/encrypted_sync" -> 200 to
                    """{"collections":[{"collection":"subscriptions","revision":1},{"collection":"playlists","revision":1},{"collection":"history","revision":1},{"collection":"profiles","revision":1},{"collection":"playlistBookmarks","revision":1}],"legacy_data":false,"legacy_encrypted_data":true}"""
                "/v1/encrypted_sync/legacy" -> 200 to
                    """{"collection":"legacy","revision":1,"payload":${JsonHelper.json.encodeToString(storedLegacyPayload)}}"""
                else -> error("Unexpected request: ${exchange.requestURI.path}")
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

            EncryptedSyncServerUserDataRepository(api, crypto).migrateLegacyData()

            assertTrue(requests.none { (_, path) -> path == "/v1/encrypted_sync/playbackSpeeds" })
            assertTrue(requests.none { (method, path) -> method == "PUT" && path == "/v1/encrypted_sync/legacy" })
            assertEquals(legacyPayload, storedLegacyPayload)
            assertEquals(legacyDocument, crypto.decrypt(storedLegacyPayload))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun syncsChannelPlaybackSpeedsThroughSettingsWithoutChangingOtherSettings() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        val initialSettings = JsonHelper.json.parseToJsonElement(
            """[{"key":"theme","value":"dark","updatedAt":10},{"key":"channelPlaybackSpeeds","value":"{\"UC123\":1.25}","updatedAt":20}]"""
        )
        var payload = crypto.encrypt(initialSettings)
        var revision = 1L
        val requests = mutableListOf<Pair<String, String>>()
        server.createContext("/v1/encrypted_sync") { exchange ->
            requests += exchange.requestMethod to exchange.requestURI.path
            assertEquals("/v1/encrypted_sync/settings", exchange.requestURI.path)
            val body = if (exchange.requestMethod == "GET") {
                """{"collection":"settings","revision":$revision,"payload":${JsonHelper.json.encodeToString(payload)}}"""
            } else {
                val request = JsonHelper.json.parseToJsonElement(
                    exchange.requestBody.bufferedReader().readText()
                ).jsonObject
                assertEquals(revision.toString(), request.getValue("revision").jsonPrimitive.content)
                payload = request.getValue("payload").jsonPrimitive.content
                revision++
                """{"collection":"settings","revision":$revision,"payload":null}"""
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
            val sync = ChannelPlaybackSpeedSettingsSync(api, crypto)

            assertEquals(mapOf("UC123" to 1.25f), sync.syncOnLogin(mapOf("local" to 2f)))
            assertEquals(initialSettings, crypto.decrypt(payload))

            sync.update("UC456", 1.5f)
            sync.update("UC123", null)

            val settings = crypto.decrypt(payload).jsonArray
            assertEquals(initialSettings.jsonArray[0], settings[0])
            val value = settings[1].jsonObject.getValue("value").jsonPrimitive.content
            assertEquals(
                JsonHelper.json.parseToJsonElement("""{"UC456":1.5}"""),
                JsonHelper.json.parseToJsonElement(value)
            )
            assertTrue(requests.all { (_, path) -> path == "/v1/encrypted_sync/settings" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun uploadsLocalChannelSpeedsOnlyWhenSettingsEntryIsMissing() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        var payload = crypto.encrypt(JsonHelper.json.parseToJsonElement(
            """[{"key":"theme","value":"dark","updatedAt":10}]"""
        ))
        var revision = 1L
        server.createContext("/v1/encrypted_sync/settings") { exchange ->
            val body = if (exchange.requestMethod == "GET") {
                """{"collection":"settings","revision":$revision,"payload":${JsonHelper.json.encodeToString(payload)}}"""
            } else {
                val request = JsonHelper.json.parseToJsonElement(
                    exchange.requestBody.bufferedReader().readText()
                ).jsonObject
                payload = request.getValue("payload").jsonPrimitive.content
                revision++
                """{"collection":"settings","revision":$revision,"payload":null}"""
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
            val local = mapOf("UC123" to 1.5f)

            assertEquals(local, ChannelPlaybackSpeedSettingsSync(api, crypto).syncOnLogin(local))

            val settings = crypto.decrypt(payload).jsonArray
            assertEquals("theme", settings[0].jsonObject.getValue("key").jsonPrimitive.content)
            assertEquals("channelPlaybackSpeeds", settings[1].jsonObject.getValue("key").jsonPrimitive.content)
            assertEquals(
                JsonHelper.json.parseToJsonElement("""{"UC123":1.5}"""),
                JsonHelper.json.parseToJsonElement(settings[1].jsonObject.getValue("value").jsonPrimitive.content)
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun malformedChannelSpeedSettingDoesNotBlockLoginOrOverwriteSettings() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", null)
        var payload = ""
        var puts = 0
        server.createContext("/v1/encrypted_sync/settings") { exchange ->
            if (exchange.requestMethod == "PUT") puts++
            val body = """{"collection":"settings","revision":1,"payload":${JsonHelper.json.encodeToString(payload)}}"""
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
            val sync = ChannelPlaybackSpeedSettingsSync(api, crypto)
            val local = mapOf("UC123" to 1.5f)
            val malformed = listOf(
                """{"key":"channelPlaybackSpeeds"}""",
                """{"key":"channelPlaybackSpeeds","value":"not-json"}""",
                """{"key":"channelPlaybackSpeeds","value":"{\"UC123\":{}}"}"""
            )
            for (entry in malformed) {
                payload = crypto.encrypt(JsonHelper.json.parseToJsonElement("""[$entry]"""))
                val original = payload

                assertEquals(local, sync.syncOnLogin(local))
                assertTrue(runCatching { sync.update("UC456", 2f) }.isFailure)
                assertEquals(original, payload)
            }
            assertEquals(0, puts)
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
