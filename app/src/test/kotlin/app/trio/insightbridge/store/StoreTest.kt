/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.store

import app.aaps.pump.insight.utils.Nonce
import app.aaps.pump.insight.utils.PairingDataStorage
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test

class StoreTest {

    private val dir: File = Files.createTempDirectory("ib-store").toFile().also { it.deleteOnExit() }
    private val cipher = AesGcmBlobCipher(ByteArray(32) { it.toByte() })

    @Test fun encryptedStoreRoundTripsAndFileIsNotPlaintext() {
        val f = File(dir, "a.bin")
        val s = EncryptedFileKeyValueStore(f, cipher)
        s.edit(mapOf("ltk" to "deadbeefcafe", "x" to "y"))
        assertThat(String(f.readBytes(), Charsets.ISO_8859_1)).doesNotContain("deadbeef")
        val again = EncryptedFileKeyValueStore(f, cipher)
        assertThat(again.getString("ltk")).isEqualTo("deadbeefcafe")
        again.putString("ltk", null)
        assertThat(EncryptedFileKeyValueStore(f, cipher).getString("ltk")).isNull()
    }

    @Test fun unreadableOrTamperedFileIsTreatedAsEmpty() {
        val f = File(dir, "b.bin")
        EncryptedFileKeyValueStore(f, cipher).putString("k", "v")
        f.writeBytes(f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertThat(EncryptedFileKeyValueStore(f, cipher).getString("k")).isNull()
        val wrongKey = AesGcmBlobCipher(ByteArray(32) { 9 })
        EncryptedFileKeyValueStore(File(dir, "c.bin"), cipher).putString("k", "v")
        assertThat(EncryptedFileKeyValueStore(File(dir, "c.bin"), wrongKey).getString("k")).isNull()
    }

    @Test fun identityStoreKeepsBridgeIdAndPairing() {
        val kv = MemoryKeyValueStore()
        val a = KvIdentityStore(kv)
        val b = KvIdentityStore(kv)
        assertThat(a.bridgeId).isEqualTo(b.bridgeId)
        assertThat(a.bridgeId).hasLength(8)
        assertThat(a.ltk()).isNull()
        a.savePairing(ByteArray(32) { 7 }, "0123456789abcdef")
        assertThat(KvIdentityStore(kv).ltk()).isEqualTo(ByteArray(32) { 7 })
        assertThat(KvIdentityStore(kv).clientId()).isEqualTo("0123456789abcdef")
        a.clearPairing()
        assertThat(KvIdentityStore(kv).ltk()).isNull()
    }

    @Test fun atomicTextFileReplacesContent() {
        val f = AtomicTextFile(File(dir, "t.json"))
        assertThat(f.read()).isNull()
        f.write("one"); f.write("two")
        assertThat(f.read()).isEqualTo("two")
    }

    @Test fun settingsPersist() {
        val file = AtomicTextFile(File(dir, "s.json"))
        SettingsStore(file).apply { simulatorEnabled = true; setupDone = true }
        val s = SettingsStore(file)
        assertThat(s.simulatorEnabled).isTrue(); assertThat(s.setupDone).isTrue(); assertThat(s.alwaysOnLink).isFalse()
    }

    @Test fun pumpPairingDataPersistsNoncesSynchronouslyAndResets() {
        val kv = MemoryKeyValueStore()
        val p = PairingDataStorage(kv)
        p.paired = true; p.macAddress = "AA:BB"
        p.incomingKey = ByteArray(16) { 1 }; p.outgoingKey = ByteArray(16) { 2 }
        val n = Nonce(); n.increment(5); p.lastNonceSent = n
        val loaded = PairingDataStorage(kv)
        assertThat(loaded.paired).isTrue()
        assertThat(loaded.macAddress).isEqualTo("AA:BB")
        assertThat(loaded.incomingKey).isEqualTo(ByteArray(16) { 1 })
        assertThat(loaded.lastNonceSent!!.storageValue).isEqualTo(n.storageValue)
        loaded.reset()
        val cleared = PairingDataStorage(kv)
        assertThat(cleared.paired).isFalse(); assertThat(cleared.incomingKey).isNull(); assertThat(cleared.lastNonceSent).isNull()
    }
}
