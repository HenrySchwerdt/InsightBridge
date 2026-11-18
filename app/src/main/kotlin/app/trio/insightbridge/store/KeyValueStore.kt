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

/** The blob is damaged or fails authentication (permanent, as opposed to a transient Keystore / I/O error). */
class CorruptBlobException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
        if (data.size <= 12 + 16) throw CorruptBlobException("blob too short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        try {
            return c.doFinal(data, 12, data.size - 12)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CorruptBlobException("authentication failed", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw CorruptBlobException("authentication failed", e)
        }
    }
}

/** Atomic file write: temp file + fsync + rename. Plain (non-secret) content. */
class AtomicTextFile(private val file: File) : TextFile {

    /** Null only if the file does not exist; an existing but unreadable file throws (it must not look "empty"). */
    override fun read(): String? {
        if (!file.exists()) return null
        return file.readText(Charsets.UTF_8)
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

    override fun quarantine() = quarantineFile(file)
}

/** Moves [file] to `<name>.bak` (replacing an older backup) so a corrupt file is kept for diagnosis and never overwritten. */
fun quarantineFile(file: File) {
    if (!file.exists()) return
    val bak = File(file.parentFile, file.name + ".bak")
    bak.delete()
    if (!file.renameTo(bak)) throw java.io.IOException("could not move ${file.name} aside")
}

/**
 * Key/value store persisted as one encrypted blob (atomic). Secrets (pump pairing keys, LTK) go here.
 *
 * Failure handling is deliberately asymmetric, because wiping a pairing silently is worse than refusing:
 *  - file absent: empty store;
 *  - file fails authentication / is damaged ([CorruptBlobException], bad JSON): moved aside to `<name>.bak`,
 *    store starts empty ([recoveredFromCorruption]) - the user has to pair again, the old file is kept;
 *  - anything else (I/O error, Keystore temporarily unavailable): retried, then the store stays
 *    [available] = false; reads return null and every write throws, so the file is NEVER overwritten.
 *    A later access tries again.
 */
class EncryptedFileKeyValueStore(private val file: File, private val cipher: BlobCipher) : KeyValueStore {

    private val map = HashMap<String, String>()
    private var loaded = false

    @Volatile var recoveredFromCorruption = false
        private set

    val available: Boolean @Synchronized get() = ensureLoaded()

    init {
        ensureLoaded()
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (!file.exists()) { loaded = true; return true }
        var lastError: Exception? = null
        repeat(3) {
            try {
                val plain = cipher.decrypt(file.readBytes())
                val obj = Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as JsonObject
                map.clear()
                obj.forEach { (k, v) -> map[k] = (v as JsonPrimitive).content }
                loaded = true
                return true
            } catch (e: CorruptBlobException) {
                return quarantineAndStartEmpty(e)
            } catch (e: kotlinx.serialization.SerializationException) {
                return quarantineAndStartEmpty(e)
            } catch (e: ClassCastException) {
                return quarantineAndStartEmpty(e)
            } catch (e: Exception) {
                lastError = e
            }
        }
        app.aaps.pump.insight.utils.PumpLog.e("secret store unavailable, not touching ${file.name}", lastError)
        return false
    }

    private fun quarantineAndStartEmpty(cause: Exception): Boolean {
        app.aaps.pump.insight.utils.PumpLog.e("secret store ${file.name} is corrupt; moved aside, starting empty", cause)
        return try {
            quarantineFile(file)
            map.clear()
            recoveredFromCorruption = true
            loaded = true
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized override fun getString(key: String): String? = if (ensureLoaded()) map[key] else null

    @Synchronized
    override fun edit(changes: Map<String, String?>) {
        check(ensureLoaded()) { "secret store unavailable" }
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
            try {
                kv.putString("bridgeId", id.joinToString("") { "%02x".format(it) })
            } catch (e: Exception) {
                // Store unavailable right now: run with an unpersisted id rather than crash the process on start.
                app.aaps.pump.insight.utils.PumpLog.w("could not persist the bridge id", e)
            }
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
