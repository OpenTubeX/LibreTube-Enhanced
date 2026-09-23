package com.github.libretube.ui.models

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class SubscriptionsViewModel : ViewModel() {
    private val sourceRevision = AtomicInteger()
    private val authSettings = LibreTubeApp.instance.getSharedPreferences(
        PreferenceKeys.AUTH_PREF_FILE, Context.MODE_PRIVATE
    )
    private val sourcePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PreferenceKeys.SYNC_SERVER_TYPE ||
            key == PreferenceKeys.AUTH_INSTANCE ||
            key == PreferenceKeys.LIBRETUBE_SYNC_SERVER_URL ||
            key == PreferenceKeys.TOKEN
        ) {
            sourceRevision.incrementAndGet()
        }
    }
    var displayedSourceRevision = sourceRevision.get()
    val currentSourceRevision get() = sourceRevision.get()

    init {
        PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(sourcePreferenceListener)
        authSettings.registerOnSharedPreferenceChangeListener(sourcePreferenceListener)
    }

    override fun onCleared() {
        PreferenceHelper.settings.unregisterOnSharedPreferenceChangeListener(sourcePreferenceListener)
        authSettings.unregisterOnSharedPreferenceChangeListener(sourcePreferenceListener)
        super.onCleared()
    }

    var videoFeed = MutableLiveData<List<StreamItem>?>()

    var subscriptions = MutableLiveData<List<Subscription>?>()
    val feedProgress = MutableLiveData<FeedProgress?>()

    var subFeedRecyclerViewState: Parcelable? = null

    val groups = MutableLiveData<List<SubscriptionGroup>>()
    var groupToEdit: SubscriptionGroup? = null

    fun fetchFeed(context: Context, forceRefresh: Boolean) {
        val sourceRevision = currentSourceRevision
        viewModelScope.launch(Dispatchers.IO) {
            if (sourceRevision != currentSourceRevision) return@launch
            val videoFeed = try {
                SubscriptionHelper.getFeed(forceRefresh = forceRefresh) { feedProgress ->
                    this@SubscriptionsViewModel.feedProgress.postValue(feedProgress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                withContext(Dispatchers.Main) {
                    if (sourceRevision == currentSourceRevision) {
                        context.toastFromMainDispatcher(R.string.server_error)
                        this@SubscriptionsViewModel.videoFeed.value = emptyList()
                    }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (sourceRevision != currentSourceRevision) return@withContext
                this@SubscriptionsViewModel.videoFeed.value = videoFeed
                videoFeed.firstOrNull { !it.isUpcoming }?.uploaded?.let {
                    PreferenceHelper.updateLastFeedWatchedTime(it, false)
                }
            }
        }
    }

    fun fetchSubscriptions(context: Context) {
        val sourceRevision = currentSourceRevision
        viewModelScope.launch(Dispatchers.IO) {
            if (sourceRevision != currentSourceRevision) return@launch
            val subscriptions = try {
                SubscriptionHelper.getSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                withContext(Dispatchers.Main) {
                    if (sourceRevision == currentSourceRevision) {
                        context.toastFromMainDispatcher(R.string.server_error)
                        this@SubscriptionsViewModel.subscriptions.value = emptyList()
                    }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (sourceRevision == currentSourceRevision) {
                    this@SubscriptionsViewModel.subscriptions.value = subscriptions
                }
            }
        }
    }
}
