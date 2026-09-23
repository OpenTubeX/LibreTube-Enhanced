package com.github.libretube.repo

import android.content.SharedPreferences
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ChannelSpeedLoginTest {
    @Test
    fun preservesLocalEditsMadeDuringLoginWhenApplyingRemoteSpeeds() {
        val previous = runCatching { PreferenceHelper.settings }.getOrNull()
        val values = mutableMapOf(
            "channel_speed_deleted" to "2.0",
            "theme" to "dark"
        )
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "commit" -> true
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        PreferenceHelper.settings = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        try {
            assertEquals(mapOf("deleted" to 2f), PlayerHelper.beginChannelSpeedSync("new-token"))
            PlayerHelper.saveChannelPlaybackSpeed("edited", 1.75f)
            PlayerHelper.removeChannelPlaybackSpeed("deleted")
            PlayerHelper.applySyncedChannelSpeeds(mapOf("deleted" to 1.25f, "remote" to 1.5f))
            PlayerHelper.saveChannelPlaybackSpeed("late", 2f)

            assertEquals(mapOf("edited" to 1.75f, "remote" to 1.5f, "late" to 2f),
                PlayerHelper.getAllSavedChannelSpeeds())
            assertEquals("dark", values["theme"])
        } finally {
            PlayerHelper.cancelChannelSpeedSync()
            if (previous != null) {
                PreferenceHelper.settings = previous
            } else {
                PreferenceHelper::class.java.getDeclaredField("settings").apply {
                    isAccessible = true
                    set(null, null)
                }
            }
        }
    }
}
