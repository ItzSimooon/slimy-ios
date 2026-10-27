package de.hyprnode.slimy.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the AES key that protects the token comes from. On the phone it is
 * an Android Keystore key that never leaves secure hardware ([KeystoreKey]);
 * tests use a key in memory.
 */
internal interface TokenKey {
    /** The key, made first if [create] and there is none. Null when there is none or the store can't give it right now. */
    fun key(create: Boolean): SecretKey?

    /** Forgets the key (after the token is removed). */
    fun delete() {}
}

/**
 * The friends token, encrypted with AES/GCM in app-private storage: the
 * Android counterpart of the iPhone app's Keychain item. Only this app can
 * read the file, the key lives in the Android Keystore (not exportable, not
 * backed up), and the file sits in `noBackupFilesDir`, so the token never
 * moves to another device.
 *
 * File layout: a version byte, the IV length and IV, then the ciphertext
 * with its GCM tag.
 */
internal class TokenVault(private val file: File, private val keys: TokenKey) {
    /** The token, or null when there is none or it can't be read right now. */
    fun load(): String? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val input = DataInputStream(bytes.inputStream())
            if (input.readByte() != formatVersion) return null
            val ivLength = input.readUnsignedByte()
            if (ivLength !in 12..32) return null
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val sealed = input.readBytes()
            val key = keys.key(create = false) ?: return null
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tagBits, iv))
            val token = cipher.doFinal(sealed).toString(Charsets.UTF_8)
            token.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Stores the token, or removes it for null. Returns whether it worked. */
    fun save(token: String?): Boolean {
        if (token == null) {
            val removed = !file.exists() || file.delete()
            keys.delete()
            return removed
        }
        return try {
            val key = keys.key(create = true) ?: return false
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use {
                it.writeByte(formatVersion.toInt())
                it.writeByte(iv.size)
                it.write(iv)
                it.write(sealed)
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(out.toByteArray())
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val formatVersion: Byte = 1
        private const val transformation = "AES/GCM/NoPadding"
        private const val tagBits = 128

        /** The app's vault: `noBackupFilesDir/friends-token`, keyed by the Keystore. */
        fun forContext(context: Context): TokenVault =
            TokenVault(File(context.applicationContext.noBackupFilesDir, "friends-token"), KeystoreKey(keyAlias))

        const val keyAlias = "de.hyprnode.slimy.friends"
    }
}

/** An AES-256 key in the Android Keystore, only for encrypting and decrypting with GCM. */
internal class KeystoreKey(private val alias: String) : TokenKey {
    override fun key(create: Boolean): SecretKey? = try {
        val store = KeyStore.getInstance(provider).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: if (create) generate() else null
    } catch (e: Exception) {
        null
    }

    override fun delete() {
        try {
            KeyStore.getInstance(provider).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            // Nothing to forget.
        }
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        generator.init(spec.build())
        return generator.generateKey()
    }

    private companion object {
        const val provider = "AndroidKeyStore"
    }
}
