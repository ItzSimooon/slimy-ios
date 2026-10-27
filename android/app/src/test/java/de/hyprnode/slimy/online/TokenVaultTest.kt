package de.hyprnode.slimy.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The token vault's file format and failure handling, with a software AES key standing in for the Android Keystore. */
class TokenVaultTest {
    private lateinit var dir: File
    private lateinit var file: File

    /** An AES-256 key in memory, made on demand like the Keystore one. */
    private class MemoryKey(var available: Boolean = true) : TokenKey {
        var key: SecretKey? = null
        var deleted = 0

        override fun key(create: Boolean): SecretKey? {
            if (!available) return null
            if (key == null && create) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return key
        }

        override fun delete() {
            key = null
            deleted += 1
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("slimy-vault").toFile()
        file = File(dir, "friends-token")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aTokenComesBack() {
        val vault = TokenVault(file, MemoryKey())
        assertNull(vault.load())
        assertTrue(vault.save("secret-token-123"))
        assertEquals("secret-token-123", vault.load())
    }

    @Test
    fun theFileNeverHoldsTheTokenInTheClear() {
        val vault = TokenVault(file, MemoryKey())
        vault.save("secret-token-123")
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-token-123"))
        assertEquals(1, bytes[0].toInt())
    }

    @Test
    fun everySaveUsesAFreshIv() {
        val keys = MemoryKey()
        val vault = TokenVault(file, keys)
        vault.save("same")
        val first = file.readBytes()
        vault.save("same")
        val second = file.readBytes()
        assertNotEquals(first.toList(), second.toList())
        assertEquals("same", vault.load())
    }

    @Test
    fun removingTheTokenRemovesTheFileAndTheKey() {
        val keys = MemoryKey()
        val vault = TokenVault(file, keys)
        vault.save("token")
        assertTrue(vault.save(null))
        assertFalse(file.exists())
        assertNull(vault.load())
        assertEquals(1, keys.deleted)
        // Nothing there: removing again still works.
        assertTrue(vault.save(null))
    }

    @Test
    fun anotherKeyCantReadIt() {
        TokenVault(file, MemoryKey()).save("token")
        assertNull(TokenVault(file, MemoryKey()).load())
    }

    @Test
    fun aKeystoreThatIsntReadyReadsAsNoToken() {
        val keys = MemoryKey()
        val vault = TokenVault(file, keys)
        vault.save("token")
        keys.available = false
        assertNull(vault.load())
        assertFalse(vault.save("other"))
        // The stored token is still there once the store answers again.
        keys.available = true
        assertEquals("token", vault.load())
    }

    @Test
    fun aDamagedFileReadsAsNoToken() {
        val keys = MemoryKey()
        val vault = TokenVault(file, keys)
        vault.save("token")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(vault.load())

        file.writeBytes(byteArrayOf(9, 12))
        assertNull(vault.load())
        file.writeBytes(ByteArray(0))
        assertNull(vault.load())
    }

    @Test
    fun anEmptyTokenIsNoToken() {
        val vault = TokenVault(file, MemoryKey())
        vault.save("")
        assertNull(vault.load())
    }
}
