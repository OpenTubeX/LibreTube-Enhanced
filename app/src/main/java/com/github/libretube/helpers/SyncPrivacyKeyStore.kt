package com.github.libretube.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the sync key with a non-exportable Android Keystore key. */
internal object SyncPrivacyKeyStore {
    private const val ALIAS = "libretube_sync_privacy_key"
    private const val IV_BYTES = 12

    private fun existingKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(ALIAS, null) as? SecretKey
    }

    @Synchronized
    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).run {
        init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }

    fun wrap(rawKey: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val wrapped = cipher.iv + cipher.doFinal(Base64.getDecoder().decode(rawKey))
        return Base64.getEncoder().encodeToString(wrapped)
    }

    fun unwrap(wrappedKey: String): String {
        val bytes = Base64.getDecoder().decode(wrappedKey)
        require(bytes.size >= IV_BYTES + 16) { "Invalid stored sync key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireNotNull(existingKey()) { "Stored sync key cannot be recovered" },
            GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)))
    }
}
