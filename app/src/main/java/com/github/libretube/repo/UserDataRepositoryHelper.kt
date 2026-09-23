package com.github.libretube.repo

import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.SyncServerType
import com.github.libretube.helpers.PreferenceHelper

object UserDataRepositoryHelper {
    private val loggedIn get() = PreferenceHelper.getToken().isNotBlank()
    val syncServerType: SyncServerType
        get() = when (PreferenceHelper.getString(PreferenceKeys.SYNC_SERVER_TYPE, "none")) {
            "piped" -> SyncServerType.PIPED
            "libretube" -> SyncServerType.LIBRETUBE
            else -> SyncServerType.NONE
        }

    @Deprecated("DO NOT use this directly, use the wrappers from PlaylistHelper and SubscriptionHelper instead!")
    val userDataRepository: UserDataRepository
        get() = userDataRepositoryFor(syncServerType)

    private fun userDataRepositoryFor(type: SyncServerType): UserDataRepository = when (type) {
        SyncServerType.PIPED -> PipedUserDataRepository()
        SyncServerType.LIBRETUBE -> if (
            PreferenceHelper.getSyncPrivacySalt().isNotEmpty() || RetrofitInstance.isOpenTubeXSyncServer
        ) {
            EncryptedSyncServerUserDataRepository()
        } else {
            LibreTubeSyncServerUserDataRepository()
        }
        else -> LocalUserDataRepository()
    }

    @Deprecated("DO NOT use this directly, use the wrappers from SubscriptionHelper instead!")
    val feedRepository: FeedRepository
        get() {
            val type = syncServerType
            return when (type to loggedIn) {
                SyncServerType.PIPED to true -> PipedAccountFeedRepository()
                else -> LocalFeedRepository(userDataRepositoryFor(type), type)
            }
        }
}
