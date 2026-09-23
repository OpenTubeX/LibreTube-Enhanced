package com.github.libretube.repo

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.ltsync.LibreTubeSyncServerApi
import com.github.libretube.api.ltsync.EncryptedSyncCrypto
import com.github.libretube.api.ltsync.obj.Channel
import com.github.libretube.api.ltsync.obj.CreateVideo
import com.github.libretube.api.ltsync.obj.ExtendedPlaylist
import com.github.libretube.api.ltsync.obj.ExtendedPublicPlaylist
import com.github.libretube.api.ltsync.obj.ExtendedSubscriptionGroup
import com.github.libretube.api.ltsync.obj.ExtendedWatchHistoryItem
import com.github.libretube.api.ltsync.obj.Playlist as SyncPlaylist
import com.github.libretube.api.ltsync.obj.PlaylistResponse
import com.github.libretube.api.ltsync.obj.PutEncryptedSyncCollection
import com.github.libretube.api.ltsync.obj.SubscriptionGroup as SyncGroup
import com.github.libretube.api.ltsync.obj.WatchHistoryItem
import com.github.libretube.api.ltsync.obj.WatchedState
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.enums.WatchHistoryStatus
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.util.UUID

/** OpenTubeX collection sync for the data types exposed by LibreTube's repository. */
class EncryptedSyncServerUserDataRepository internal constructor(
    private val authenticatedApi: LibreTubeSyncServerApi? = null,
    private val privacy: EncryptedSyncCrypto? = null
) : UserDataRepository {
    override var requiresLogin = true

    private val api get() = authenticatedApi ?: RetrofitInstance.libretubeSyncServerApi
    private val crypto get() = privacy ?: EncryptedSyncCrypto.fromStoredKey(
        PreferenceHelper.getSyncPrivacyKey(), PreferenceHelper.getSyncPrivacySalt()
    )
    private val account = LibreTubeSyncServerUserDataRepository()
    private val syncJson = Json(JsonHelper.json) { encodeDefaults = true }

    internal suspend fun migrateLegacyData() {
        val manifest = api.getEncryptedSyncManifest()
        if (!manifest.legacyData && !manifest.legacyEncryptedData) return
        val migrated = manifest.collections.map { it.collection }.toSet()
        if ("subscriptions" !in migrated) {
            change("subscriptions", Channel.serializer(), ::legacySubscriptions) { }
        }
        if ("playlists" !in migrated) {
            change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { }
        }
        if ("history" !in migrated) {
            change("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory) { }
        }
        if ("profiles" !in migrated) {
            change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { }
        }
        if ("playlistBookmarks" !in migrated) {
            change("playlistBookmarks", ExtendedPublicPlaylist.serializer(), ::legacyBookmarks) { }
        }
        if (manifest.legacyEncryptedData && "playbackSpeeds" !in migrated) {
            val payload = api.getLegacyEncryptedSync().payload
            val speeds = payload?.let { crypto.decrypt(it).jsonObject["playbackSpeeds"] }
                ?: JsonArray(emptyList())
            val remote = api.getEncryptedSyncCollection("playbackSpeeds")
            if (remote.payload == null) {
                try {
                    api.putEncryptedSyncCollection(
                        "playbackSpeeds",
                        PutEncryptedSyncCollection(remote.revision, crypto.encrypt(speeds))
                    )
                } catch (error: HttpException) {
                    if (error.code() != 409) throw error
                }
            }
        }
    }

    override suspend fun login(username: String, password: String) = account.login(username, password)
    override suspend fun register(username: String, password: String) = account.register(username, password)
    override suspend fun prepareSync(token: String, password: String, privacyPassphrase: String) =
        account.prepareSync(token, password, privacyPassphrase)
    override suspend fun deleteAccount(password: String) = account.deleteAccount(password)

    private suspend fun <T> read(
        name: String,
        serializer: KSerializer<T>,
        legacy: suspend () -> List<T>
    ): List<T> = decode(name, serializer, legacy).second

    private suspend fun <T> decode(
        name: String,
        serializer: KSerializer<T>,
        legacy: suspend () -> List<T>
    ): Pair<Long, List<T>> {
        val response = api.getEncryptedSyncCollection(name)
        val payload = response.payload
        if (payload != null) {
            val data = crypto.decrypt(payload)
            return response.revision to JsonHelper.json.decodeFromJsonElement(ListSerializer(serializer), data)
        }
        check(response.revision == 0L) { "Encrypted sync collection has no data" }
        val manifest = api.getEncryptedSyncManifest()
        if (manifest.legacyEncryptedData) {
            val legacyPayload = api.getLegacyEncryptedSync().payload
            if (legacyPayload != null) {
                val document = crypto.decrypt(legacyPayload).jsonObject
                val field = document[name] ?: if (name == "profiles") document["subscriptionGroups"] else null
                if (field != null) {
                    return response.revision to JsonHelper.json.decodeFromJsonElement(
                        ListSerializer(serializer), field
                    )
                }
            }
        }
        return response.revision to if (manifest.legacyData) legacy() else emptyList()
    }

    private suspend fun <T, R> change(
        name: String,
        serializer: KSerializer<T>,
        legacy: suspend () -> List<T>,
        update: (MutableList<T>) -> R
    ): R = writeMutex.withLock {
        repeat(5) { attempt ->
            val (revision, current) = decode(name, serializer, legacy)
            val next = current.toMutableList()
            val result = update(next)
            val data: JsonElement = syncJson.encodeToJsonElement(
                ListSerializer(serializer), next
            )
            try {
                api.putEncryptedSyncCollection(
                    name, PutEncryptedSyncCollection(revision, crypto.encrypt(data))
                )
                return@withLock result
            } catch (error: HttpException) {
                if (error.code() != 409 || attempt == 4) throw error
            }
        }
        error("Encrypted sync update failed")
    }

    private suspend fun legacySubscriptions() = api.getSubscriptions()
    private suspend fun legacyPlaylists(): List<PlaylistResponse> =
        api.getPlaylists().map { api.getPlaylist(it.id) }
    private suspend fun legacyHistory(): List<ExtendedWatchHistoryItem> {
        val entries = mutableListOf<ExtendedWatchHistoryItem>()
        var page = 1
        do {
            val batch = api.getWatchHistory(page++, 100, null)
            entries += batch
        } while (batch.size == 100)
        return entries
    }
    private suspend fun legacyProfiles() = api.getSubscriptionGroups()
    private suspend fun legacyBookmarks() = api.getPlaylistBookmarks()

    override suspend fun getSubscriptions(): List<Subscription> =
        read("subscriptions", Channel.serializer(), ::legacySubscriptions).map {
            Subscription(url = it.id, name = it.name, avatar = it.avatar, verified = it.verified)
        }

    override suspend fun getSubscriptionChannelIds(): List<String> =
        read("subscriptions", Channel.serializer(), ::legacySubscriptions).map { it.id }

    override suspend fun isSubscribed(channelId: String): Boolean =
        getSubscriptionChannelIds().contains(channelId)

    override suspend fun subscribe(channelId: String, name: String, uploaderAvatar: String?, verified: Boolean) {
        change("subscriptions", Channel.serializer(), ::legacySubscriptions) { channels ->
            channels.removeAll { it.id == channelId }
            channels += Channel(id = channelId, name = name, avatar = uploaderAvatar, verified = verified)
        }
    }

    override suspend fun unsubscribe(channelId: String) {
        change("subscriptions", Channel.serializer(), ::legacySubscriptions) { channels ->
            channels.removeAll { it.id == channelId }
        }
    }

    private fun StreamItem.toCreateVideo() = CreateVideo(
        id = requireNotNull(url).toID(), title = title.orEmpty(),
        thumbnailUrl = thumbnail.orEmpty(), duration = duration?.toInt() ?: -1,
        uploadDate = uploaded,
        uploader = Channel(
            id = requireNotNull(uploaderUrl).toID(), name = uploaderName.orEmpty(),
            avatar = uploaderAvatar, verified = uploaderVerified == true
        )
    )

    override suspend fun getPlaylists(): List<Playlists> =
        read("playlists", PlaylistResponse.serializer(), ::legacyPlaylists)
            .map { it.playlist.toPipedPlaylists() }

    override suspend fun getPlaylist(playlistId: String): Playlist =
        read("playlists", PlaylistResponse.serializer(), ::legacyPlaylists)
            .first { it.playlist.id == playlistId }.toPipedPlaylist()

    override suspend fun createPlaylist(playlistName: String): String {
        val id = UUID.randomUUID().toString()
        change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { playlists ->
            playlists += PlaylistResponse(
                SyncPlaylist(id = id, title = playlistName, description = ""), emptyList()
            )
        }
        return id
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean =
        change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { playlists ->
            playlists.removeAll { it.playlist.id == playlistId }
        }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Boolean =
        changePlaylist(playlistId) { it.copy(title = newName) }

    override suspend fun changePlaylistDescription(playlistId: String, newDescription: String): Boolean =
        changePlaylist(playlistId) { it.copy(description = newDescription) }

    private suspend fun changePlaylist(id: String, update: (SyncPlaylist) -> SyncPlaylist): Boolean =
        change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { playlists ->
            val index = playlists.indexOfFirst { it.playlist.id == id }
            if (index < 0) false else {
                playlists[index] = playlists[index].copy(playlist = update(playlists[index].playlist))
                true
            }
        }

    override suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean =
        change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { playlists ->
            val index = playlists.indexOfFirst { it.playlist.id == playlistId }
            if (index < 0) false else {
                val entry = playlists[index]
                val updatedVideos = entry.videos + videos.map { it.toCreateVideo() }
                playlists[index] = entry.copy(
                    playlist = entry.playlist.copy(videoCount = updatedVideos.size.toLong()),
                    videos = updatedVideos
                )
                true
            }
        }

    override suspend fun removeFromPlaylist(playlistId: String, videoId: String, index: Int): Boolean =
        change("playlists", PlaylistResponse.serializer(), ::legacyPlaylists) { playlists ->
            val playlistIndex = playlists.indexOfFirst { it.playlist.id == playlistId }
            if (playlistIndex < 0) false else {
                val entry = playlists[playlistIndex]
                val videoIndex = if (index in entry.videos.indices && entry.videos[index].id == videoId) {
                    index
                } else entry.videos.indexOfFirst { it.id == videoId }
                if (videoIndex < 0) false else {
                    val updatedVideos = entry.videos.toMutableList().apply { removeAt(videoIndex) }
                    playlists[playlistIndex] = entry.copy(
                        playlist = entry.playlist.copy(videoCount = updatedVideos.size.toLong()),
                        videos = updatedVideos
                    )
                    true
                }
            }
        }

    private fun ExtendedSubscriptionGroup.toLocal() = SubscriptionGroup(
        id = group.id, name = group.title, channels = channels.map { it.id }
    )

    override suspend fun getSubscriptionGroups(): List<SubscriptionGroup> =
        read("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles).map { it.toLocal() }

    override suspend fun getSubscriptionGroup(subscriptionGroupId: String): SubscriptionGroup =
        getSubscriptionGroups().first { it.id == subscriptionGroupId }

    override suspend fun createSubscriptionGroup(name: String): String {
        val id = UUID.randomUUID().toString()
        change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { groups ->
            groups += ExtendedSubscriptionGroup(emptyList(), SyncGroup(id, name))
        }
        return id
    }

    override suspend fun renameSubscriptionGroup(subscriptionGroupId: String, newName: String) {
        change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { groups ->
            val index = groups.indexOfFirst { it.group.id == subscriptionGroupId }
            require(index >= 0) { "Subscription group not found" }
            groups[index] = groups[index].copy(group = groups[index].group.copy(title = newName))
        }
    }

    override suspend fun deleteSubscriptionGroup(subscriptionGroupId: String) {
        change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { groups ->
            groups.removeAll { it.group.id == subscriptionGroupId }
        }
    }

    override suspend fun addToSubscriptionGroup(subscriptionGroupId: String, channelId: String) {
        val channel = read("subscriptions", Channel.serializer(), ::legacySubscriptions)
            .firstOrNull { it.id == channelId } ?: Channel(id = channelId, name = "", verified = false)
        change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { groups ->
            val index = groups.indexOfFirst { it.group.id == subscriptionGroupId }
            require(index >= 0) { "Subscription group not found" }
            groups[index] = groups[index].copy(channels = groups[index].channels.filterNot { it.id == channelId } + channel)
        }
    }

    override suspend fun removeFromSubscriptionGroup(subscriptionGroupId: String, channelId: String) {
        change("profiles", ExtendedSubscriptionGroup.serializer(), ::legacyProfiles) { groups ->
            val index = groups.indexOfFirst { it.group.id == subscriptionGroupId }
            require(index >= 0) { "Subscription group not found" }
            groups[index] = groups[index].copy(channels = groups[index].channels.filterNot { it.id == channelId })
        }
    }

    private fun ExtendedWatchHistoryItem.toLocal() = WatchHistoryEntry(
        metadata = WatchHistoryEntryMetadata(
            videoId = video.id, addedDate = metadata.addedDate,
            finished = metadata.watchedState == WatchedState.Completed,
            positionMillis = metadata.positionMillis?.toLong()
        ),
        video = video.toStreamItem()
    )

    override suspend fun getWatchHistory(
        pageSize: Int, cursor: Any?, watchedState: WatchHistoryStatus
    ): Pair<List<WatchHistoryEntry>, Any?> {
        val all = read("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory)
            .filter {
                when (watchedState) {
                    WatchHistoryStatus.ALL -> true
                    WatchHistoryStatus.FINISHED -> it.metadata.watchedState == WatchedState.Completed
                    WatchHistoryStatus.CONTINUE_WATCHING -> it.metadata.watchedState == WatchedState.Watching
                }
            }
            .sortedByDescending { it.metadata.addedDate }
        val page = (cursor as? Int ?: 1).coerceAtLeast(1)
        val from = (page - 1) * pageSize
        val entries = all.drop(from).take(pageSize).map { it.toLocal() }
        return entries to if (from + entries.size < all.size) page + 1 else null
    }

    override suspend fun getFromWatchHistory(videoId: String): WatchHistoryEntry? =
        read("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory)
            .firstOrNull { it.video.id == videoId }?.toLocal()

    override suspend fun addToWatchHistory(watchHistoryEntry: WatchHistoryEntry) {
        change("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory) { history ->
            history.removeAll { it.video.id == watchHistoryEntry.metadata.videoId }
            history += ExtendedWatchHistoryItem(
                WatchHistoryItem(
                    addedDate = watchHistoryEntry.metadata.addedDate,
                    watchedState = if (watchHistoryEntry.metadata.finished) WatchedState.Completed else WatchedState.Watching,
                    positionMillis = watchHistoryEntry.metadata.positionMillis?.toInt()
                ),
                watchHistoryEntry.video.toCreateVideo()
            )
        }
    }

    override suspend fun updateWatchHistoryEntry(metadata: WatchHistoryEntryMetadata) {
        change("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory) { history ->
            val index = history.indexOfFirst { it.video.id == metadata.videoId }
            if (index >= 0) history[index] = history[index].copy(
                metadata = WatchHistoryItem(
                    addedDate = metadata.addedDate,
                    watchedState = if (metadata.finished) WatchedState.Completed else WatchedState.Watching,
                    positionMillis = metadata.positionMillis?.toInt()
                )
            )
        }
    }

    override suspend fun removeFromWatchHistory(videoId: String) {
        change("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory) { history ->
            history.removeAll { it.video.id == videoId }
        }
    }

    override suspend fun clearWatchHistory() {
        change("history", ExtendedWatchHistoryItem.serializer(), ::legacyHistory) { it.clear() }
    }

    override suspend fun getPlaylistBookmarks(): List<PlaylistBookmark> =
        read("playlistBookmarks", ExtendedPublicPlaylist.serializer(), ::legacyBookmarks)
            .map { it.toPlaylistBookmark() }

    override suspend fun getPlaylistBookmark(playlistId: String): PlaylistBookmark? =
        getPlaylistBookmarks().firstOrNull { it.playlistId == playlistId }

    override suspend fun createPlaylistBookmark(playlist: PlaylistBookmark) {
        change("playlistBookmarks", ExtendedPublicPlaylist.serializer(), ::legacyBookmarks) { bookmarks ->
            bookmarks.removeAll { it.playlist.id == playlist.playlistId }
            bookmarks += ExtendedPublicPlaylist(
                ExtendedPlaylist(
                    id = playlist.playlistId, title = playlist.playlistName.orEmpty(),
                    description = "", thumbnailUrl = playlist.thumbnailUrl,
                    videoCount = playlist.videos.toLong()
                ),
                Channel(
                    id = playlist.uploaderUrl?.toID().orEmpty(), name = playlist.uploader.orEmpty(),
                    avatar = playlist.uploaderAvatar, verified = false
                ),
                savedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun deletePlaylistBookmark(playlistId: String) {
        change("playlistBookmarks", ExtendedPublicPlaylist.serializer(), ::legacyBookmarks) { bookmarks ->
            bookmarks.removeAll { it.playlist.id == playlistId }
        }
    }

    companion object {
        private val writeMutex = Mutex()
    }
}
