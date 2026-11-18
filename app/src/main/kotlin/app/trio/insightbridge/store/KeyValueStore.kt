/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.store

import app.trio.insightbridge.engine.IdentityStore
import app.trio.insightbridge.engine.TextFile
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small string key/value store; values may be secrets. */
interface KeyValueStore {

    fun getString(key: String): String?
    fun getLong(key: String, default: Long = 0): Long = getString(key)?.toLongOrNull() ?: default
    fun getBoolean(key: String, default: Boolean = false): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default

    /** Applies all changes durably in one write. A null value removes the key. */
    fun edit(changes: Map<String, String?>)

    fun putString(key: String, value: String?) = edit(mapOf(key to value))
    fun putLong(key: String, value: Long) = edit(mapOf(key to value.toString()))
    fun putBoolean(key: String, value: Boolean) = edit(mapOf(key to value.toString()))
}

class MemoryKeyValueStore : KeyValueStore {

    private val map = HashMap<String, String>()
    @Synchronized override fun getString(key: String) = map[key]
    @Synchronized override fun edit(changes: Map<String, String?>) {
        changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}

/** Encrypts/decrypts whole blobs. Production: Android Keystore AES-GCM key ([KeystoreBlobCipher]). */
interface BlobCipher {

    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** AES-256-GCM with a caller supplied key (JVM tests, and as the building block of the Keystore cipher). */
class AesGcmBlobCipher(private val key: ByteArray, private val random: SecureRandom = SecureRandom()) : BlobCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 12 + 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** Atomic file write: temp file + fsync + rename. Plain (non-secret) content. */
class AtomicTextFile(private val file: File) : TextFile {

    override fun read(): String? = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        }
    }
}

/**
 * Key/value store persisted as one encrypted blob (atomic). Secrets (pump pairing keys, LTK) go
 * here. A file that cannot be decrypted (e.g. Keystore key lost after a restore) is treated as
 * empty: the user must re-pair, nothing wrong is ever used.
 */
class EncryptedFileKeyValueStore(private val file: File, private val cipher: BlobCipher) : KeyValueStore {

    private val map = HashMap<String, String>()

    init {
        try {
            if (file.exists()) {
                val plain = cipher.decrypt(file.readBytes())
                val obj = Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as JsonObject
                obj.forEach { (k, v) -> map[k] = (v as JsonPrimitive).content }
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    @Synchronized override fun getString(key: String) = map[key]

    @Synchronized
    override fun edit(changes: Map<String, String?>) {
        val next = HashMap(map)
        changes.forEach { (k, v) -> if (v == null) next.remove(k) else next[k] = v }
        val json = buildJsonObject { next.forEach { (k, v) -> put(k, v) } }.toString()
        val blob = cipher.encrypt(json.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(blob); it.fd.sync() }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        }
        map.clear()
        map.putAll(next)
    }
}

/** [IdentityStore] on top of a (secret) [KeyValueStore]. */
class KvIdentityStore(private val kv: KeyValueStore, random: SecureRandom = SecureRandom()) : IdentityStore {

    override val bridgeId: ByteArray

    init {
        val existing = kv.getString("bridgeId")
        bridgeId = if (existing != null && existing.length == 16) {
            ByteArray(8) { existing.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            val id = ByteArray(8).also { random.nextBytes(it) }
            kv.putString("bridgeId", id.joinToString("") { "%02x".format(it) })
            id
        }
    }

    override fun ltk(): ByteArray? = kv.getString("ltk")?.let { hexToBytes(it) }
    override fun clientId(): String? = kv.getString("clientId")

    override fun savePairing(ltk: ByteArray, clientId: String?) {
        kv.edit(mapOf("ltk" to ltk.joinToString("") { "%02x".format(it) }, "clientId" to clientId))
    }

    override fun bindClientId(clientId: String) = kv.putString("clientId", clientId)
    override fun clearPairing() = kv.edit(mapOf("ltk" to null, "clientId" to null))

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
