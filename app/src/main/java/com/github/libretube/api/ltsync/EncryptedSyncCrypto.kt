package com.github.libretube.api.ltsync

import com.github.libretube.api.JsonHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The OpenTubeX encrypted sync v1 envelope. */
internal class EncryptedSyncCrypto private constructor(
    val key: String,
    val salt: String
) {
    @Serializable
    private data class Kdf(
        val name: String = "PBKDF2",
        val hash: String = "SHA-256",
        val iterations: Int = ITERATIONS,
        val salt: String
    )

    @Serializable
    private data class CipherInfo(val name: String = "AES-GCM", val iv: String)

    @Serializable
    private data class Compression(val name: String)

    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val kdf: Kdf,
        val cipher: CipherInfo,
        val compression: Compression? = null,
        val ciphertext: String
    )

    private val keyBytes = Base64.getDecoder().decode(key).also { require(it.size == 32) }

    fun decrypt(payload: String): JsonElement {
        val envelope = parseEnvelope(payload)
        require(envelope.kdf.salt == salt) { "Encrypted sync salt changed" }
        val iv = Base64.getDecoder().decode(envelope.cipher.iv)
        require(iv.size == 12) { "Invalid encrypted sync nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ADDITIONAL_DATA)
        val padded = try {
            cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext))
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect privacy passphrase or corrupted sync data", error)
        }
        val documentBytes = if (envelope.compression != null) {
            require(padded.size >= 4) { "Invalid compressed sync document" }
            val length = ((padded[0].toInt() and 255) shl 24) or
                ((padded[1].toInt() and 255) shl 16) or
                ((padded[2].toInt() and 255) shl 8) or
                (padded[3].toInt() and 255)
            require(length > 0 && length <= padded.size - 4) { "Invalid compressed sync length" }
            GZIPInputStream(ByteArrayInputStream(padded, 4, length)).use { input ->
                // Bound decompression even when the encrypted input is malformed.
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_DOCUMENT_BYTES) { "Sync document is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } else {
            padded
        }
        require(documentBytes.size <= MAX_DOCUMENT_BYTES) { "Sync document is too large" }
        val document = JsonHelper.json.parseToJsonElement(documentBytes.toString(Charsets.UTF_8)).jsonObject
        require(document["version"]?.jsonPrimitive?.content == "1") { "Unsupported sync document" }
        return document["data"] ?: document // Older OpenTubeX single-document sync.
    }

    fun encrypt(data: JsonElement): String {
        val document = "{\"version\":1,\"data\":${data}}".toByteArray(Charsets.UTF_8)
        require(document.size <= MAX_DOCUMENT_BYTES) { "Sync document is too large" }
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(document) }
            output.toByteArray()
        }
        val paddedSize = ((compressed.size + 4 + BLOCK_BYTES - 1) / BLOCK_BYTES)
            .coerceAtLeast(1) * BLOCK_BYTES
        val padded = ByteArray(paddedSize) { 0x20 }
        padded[0] = (compressed.size ushr 24).toByte()
        padded[1] = (compressed.size ushr 16).toByte()
        padded[2] = (compressed.size ushr 8).toByte()
        padded[3] = compressed.size.toByte()
        compressed.copyInto(padded, 4)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ADDITIONAL_DATA)
        return envelopeJson.encodeToString(
            Envelope(
                kdf = Kdf(salt = salt),
                cipher = CipherInfo(iv = Base64.getEncoder().encodeToString(iv)),
                compression = Compression("gzip"),
                ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(padded))
            )
        )
    }

    companion object {
        private const val ITERATIONS = 600_000
        private const val BLOCK_BYTES = 64 * 1024
        private const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024
        // Allow gzip overhead, block padding, and the AES-GCM tag above the document limit.
        private const val MAX_CIPHERTEXT_BYTES = MAX_DOCUMENT_BYTES + 2 * BLOCK_BYTES + 16
        private const val MAX_CIPHERTEXT_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4
        private val ADDITIONAL_DATA = "OpenTubeX encrypted sync v1".toByteArray(Charsets.UTF_8)
        private val envelopeJson = Json(JsonHelper.json) { encodeDefaults = true }

        private fun parseEnvelope(payload: String): Envelope {
            require(payload.length <= MAX_CIPHERTEXT_CHARS + 1024) { "Sync payload is too large" }
            val value = JsonHelper.json.decodeFromString<Envelope>(payload)
            require(value.version == 1 && value.kdf.name == "PBKDF2" &&
                value.kdf.hash == "SHA-256" && value.kdf.iterations == ITERATIONS &&
                value.cipher.name == "AES-GCM" &&
                (value.compression == null || value.compression.name == "gzip")) {
                "Unsupported encrypted sync format"
            }
            require(value.kdf.salt.length == 24 && Base64.getDecoder().decode(value.kdf.salt).size == 16) {
                "Invalid encrypted sync salt"
            }
            require(value.cipher.iv.length == 16) { "Invalid encrypted sync nonce" }
            require(value.ciphertext.length <= MAX_CIPHERTEXT_CHARS) { "Sync document is too large" }
            return value
        }

        fun fromStoredKey(key: String, salt: String): EncryptedSyncCrypto {
            require(key.isNotEmpty()) { "Sign in again with a privacy passphrase to enable encrypted sync" }
            return EncryptedSyncCrypto(key, salt)
        }

        fun fromPassphrase(passphrase: String, remotePayload: String?): EncryptedSyncCrypto {
            val salt = remotePayload?.let { parseEnvelope(it).kdf.salt }
                ?: Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            val bytes = Base64.getDecoder().decode(salt)
            val spec = PBEKeySpec(passphrase.toCharArray(), bytes, ITERATIONS, 256)
            val derived = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            return EncryptedSyncCrypto(Base64.getEncoder().encodeToString(derived), salt).also {
                if (remotePayload != null) it.decrypt(remotePayload)
            }
        }
    }
}
