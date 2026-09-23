package com.github.libretube.api.ltsync

import com.github.libretube.api.JsonHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EncryptedSyncCryptoTest {
    @Serializable
    private data class Example(val name: String)

    // Generated with Node's PBKDF2, gzip, and AES-256-GCM using the OpenTubeX v1 envelope.
    private val compressedFixture = """{"version":1,"kdf":{"name":"PBKDF2","hash":"SHA-256","iterations":600000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"AES-GCM","iv":"AAECAwQFBgcICQoL"},"compression":{"name":"gzip"},"ciphertext":"GOnb/xgqjwjPdEgxdUGHJD8EJga+7sJMU5MbpCkLHCeyGyXahO6EkPLtK7GiDM+Al+CnKo2bX6m+mNsRZQN9MI+mtUvfdwYyg6e6BMwo/cZo++qbTKTuphdYydVQ54/xNw+P0Q7BFrjU2AGWh/SMxLGFUg=="}"""

    @Test
    fun decryptsOpenTubeXCompressedEnvelope() {
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", compressedFixture)
        assertEquals("2cqedVofQEtSL1PM8dziRPnrQKkBUUSlPprkHhSdjTM=", crypto.key)
        assertEquals("Example", crypto.decrypt(compressedFixture).jsonArray[0]
            .jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun decryptsCollectionDirectlyIntoTypedEntries() {
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", compressedFixture)

        assertEquals(
            listOf(Example("Example")),
            crypto.decryptCollection(compressedFixture, ListSerializer(Example.serializer()))
        )
    }

    @Test
    fun roundTripsTypedCollectionWithoutJsonTree() {
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", compressedFixture)
        val entries = List(2_000) { Example("Video $it") }

        val payload = crypto.encryptCollection(entries, ListSerializer(Example.serializer()))

        assertEquals(entries, crypto.decryptCollection(payload, ListSerializer(Example.serializer())))
        assertEquals("Video 0", crypto.decrypt(payload).jsonArray.first()
            .jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun writesOpenTubeXEnvelopeWithPaddedCiphertext() {
        val crypto = EncryptedSyncCrypto.fromPassphrase("privacy-passphrase-123", compressedFixture)
        val data = JsonHelper.json.parseToJsonElement("""[{"id":"abc"}]""")
        val envelope = crypto.encrypt(data)
        val parsed = JsonHelper.json.parseToJsonElement(envelope).jsonObject
        assertEquals("PBKDF2", parsed.getValue("kdf").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("AES-GCM", parsed.getValue("cipher").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("gzip", parsed.getValue("compression").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(Base64.getDecoder().decode(parsed.getValue("ciphertext").jsonPrimitive.content).size >= 64 * 1024 + 16)
        assertEquals(data, crypto.decrypt(envelope))
    }

    @Test
    fun rejectsWrongPassphrase() {
        val error = runCatching {
            EncryptedSyncCrypto.fromPassphrase("a-different-passphrase", compressedFixture)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Incorrect privacy passphrase or corrupted sync data", error?.message)
    }
}
