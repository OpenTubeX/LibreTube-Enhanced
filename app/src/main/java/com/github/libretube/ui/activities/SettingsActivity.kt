package com.github.libretube.ui.activities

import android.content.Intent
import android.content.DialogInterface
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.github.libretube.R
import com.github.libretube.databinding.ActivitySettingsBinding
import com.github.libretube.databinding.DialogSyncPrivacyBinding
import com.github.libretube.enums.SyncServerType
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.LibreTubeSyncServerUserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.preferences.InstanceSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        val navController = binding.settings.getFragment<NavHostFragment>().navController
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)

        // ensure that the toolbar's back button is always visible
        val appBarConfiguration = AppBarConfiguration.Builder()
            .setFallbackOnNavigateUpListener {
                finish()
                true
            }
            .build()
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        if (intent.extras?.getString(REDIRECT_KEY) == REDIRECT_TO_INTENT_SETTINGS) {
            navController.navigate(R.id.action_global_instanceSettings)
        }
        handleSyncCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSyncCallback(intent)
    }

    private fun handleSyncCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.host == "login_callback") {
            val token = data.getQueryParameter("token")
            if (token == null) {
                toastFromMainThread(R.string.error)
                return
            }
            if (UserDataRepositoryHelper.syncServerType == SyncServerType.LIBRETUBE) {
                showSyncPrivacyDialog(token)
                return
            }
            PreferenceHelper.setToken(token)
        } else if (data.path == "delete_callback") {
            PreferenceHelper.setToken("")
        } else {
            return
        }

        refreshAuthUI()
    }

    private fun showSyncPrivacyDialog(token: String) {
        val privacyBinding = DialogSyncPrivacyBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_privacy_passphrase)
            .setView(privacyBinding.root)
            .setPositiveButton(R.string.proceed, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val proceed = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        proceed.setOnClickListener {
            proceed.isEnabled = false
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        LibreTubeSyncServerUserDataRepository().prepareSync(
                            token, "", privacyBinding.privacyPassphrase.text?.toString().orEmpty()
                        )
                        PreferenceHelper.setToken(token)
                    }
                } catch (error: Exception) {
                    toastFromMainThread(error.message.orEmpty())
                    proceed.isEnabled = true
                    return@launch
                }
                dialog.dismiss()
                refreshAuthUI()
            }
        }
    }

    private fun refreshAuthUI() {
        binding.settings.getFragment<NavHostFragment>().childFragmentManager.fragments.filterIsInstance<InstanceSettings>()
            .firstOrNull()?.toggleAuthAccountActionsUI(true)
    }

    companion object {
        const val REDIRECT_KEY = "redirect"
        const val REDIRECT_TO_INTENT_SETTINGS = "intent_settings"
    }
}
