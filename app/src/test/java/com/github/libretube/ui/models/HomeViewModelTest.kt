package com.github.libretube.ui.models

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.repo.UserDataRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class HomeViewModelTest {
    @Test
    fun continueWatchingUsesFetchedHistoryWithoutPerVideoRequests() = runBlocking {
        val entries = listOf(
            historyEntry("unwatched", 10_000),
            historyEntry("watched", 95_000),
            historyEntry("unknown-duration", 10_000, null),
            historyEntry("no-position", null, null)
        )
        var historyReads = 0
        var individualReads = 0
        val repository = Proxy.newProxyInstance(
            UserDataRepository::class.java.classLoader,
            arrayOf(UserDataRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getWatchHistory" -> {
                    historyReads++
                    entries to null
                }
                "getFromWatchHistory" -> {
                    individualReads++
                    entries.firstOrNull { it.metadata.videoId == args!![0] }
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as UserDataRepository

        val videos = HomeViewModel.loadWatchingFromDB(repository)

        assertEquals(listOf("unwatched", "no-position"), videos.map { it.url })
        assertEquals(1, historyReads)
        assertEquals(0, individualReads)
    }

    private fun historyEntry(id: String, positionMillis: Long?, duration: Long? = 100) = WatchHistoryEntry(
        WatchHistoryEntryMetadata(id, 0, false, positionMillis),
        StreamItem(url = id, duration = duration)
    )
}
