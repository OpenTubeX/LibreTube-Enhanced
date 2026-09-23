package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.SyncServerType
import com.github.libretube.databinding.DialogLoginBinding
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.preferences.InstanceSettings.Companion.INSTANCE_DIALOG_REQUEST_KEY
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginDialog : DialogFragment() {
    private var signingIn = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogLoginBinding.inflate(layoutInflater)
        val alreadyLoggedIn = PreferenceHelper.getToken().isNotBlank()
        binding.privacyPassphraseInput.isVisible =
            UserDataRepositoryHelper.syncServerType == SyncServerType.LIBRETUBE

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.login)
            .setPositiveButton(R.string.login, null)
            .setView(binding.root)
        if (!alreadyLoggedIn) builder.setNegativeButton(R.string.register, null)

        return builder.show().apply {
            getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val email = binding.username.text?.toString()
                val password = binding.password.text?.toString()

                if (!email.isNullOrEmpty() && !password.isNullOrEmpty()) {
                    signIn(binding, email, password, binding.privacyPassphrase.text?.toString().orEmpty())
                } else {
                    Toast.makeText(context, R.string.empty, Toast.LENGTH_SHORT).show()
                }
            }
            if (!alreadyLoggedIn) getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                val email = binding.username.text?.toString().orEmpty()
                val password = binding.password.text?.toString().orEmpty()

                if (isEmail(email)) {
                    showPrivacyAlertDialog(binding, email, password, binding.privacyPassphrase.text?.toString().orEmpty())
                } else if (email.isNotEmpty() && password.isNotEmpty()) {
                    signIn(binding, email, password, binding.privacyPassphrase.text?.toString().orEmpty(), true)
                } else {
                    Toast.makeText(context, R.string.empty, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun signIn(
        binding: DialogLoginBinding,
        username: String, password: String, privacyPassphrase: String,
        createNewAccount: Boolean = false
    ) {
        if (signingIn) return
        signingIn = true
        setBusy(binding, if (createNewAccount) R.string.login_creating_account else R.string.login_signing_in)

        lifecycleScope.launch {
            try {
                @Suppress("DEPRECATION")
                val repository = UserDataRepositoryHelper.userDataRepository
                val token = withContext(Dispatchers.IO) {
                    if (createNewAccount) {
                        repository.validateRegistration(password, privacyPassphrase)
                        repository.register(username, password)
                    } else {
                        repository.login(username, password)
                    }
                }

                binding.loginProgressStatus.setText(R.string.login_preparing_sync)
                withContext(Dispatchers.IO) {
                    repository.prepareSync(token, password, privacyPassphrase)
                    PreferenceHelper.setToken(token)
                    PreferenceHelper.setUsername(username)
                }

                context?.toastFromMainDispatcher(
                    if (createNewAccount) R.string.registered else R.string.loggedIn
                )
                setFragmentResult(
                    INSTANCE_DIALOG_REQUEST_KEY,
                    bundleOf(IntentData.loginTask to true)
                )
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.message.orEmpty())
            } finally {
                signingIn = false
                if (dialog?.isShowing == true) setBusy(binding, null)
            }
        }
    }

    private fun setBusy(binding: DialogLoginBinding, status: Int?) {
        val busy = status != null
        binding.loginProgress.isVisible = busy
        if (status != null) binding.loginProgressStatus.setText(status)
        binding.username.isEnabled = !busy
        binding.password.isEnabled = !busy
        binding.privacyPassphrase.isEnabled = !busy
        (dialog as? AlertDialog)?.apply {
            getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !busy
            getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = !busy
        }
    }

    private fun showPrivacyAlertDialog(
        binding: DialogLoginBinding,
        email: String,
        password: String,
        privacyPassphrase: String
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_alert)
            .setMessage(R.string.username_email)
            .setNegativeButton(R.string.proceed) { _, _ ->
                signIn(binding, email, password, privacyPassphrase, true)
            }
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    private fun isEmail(text: String): Boolean {
        return Patterns.EMAIL_ADDRESS.toRegex().matches(text)
    }
}
