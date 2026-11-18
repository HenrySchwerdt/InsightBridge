package app.trio.insightbridge.store

import app.trio.insightbridge.engine.IdempotencyStore
import app.trio.insightbridge.engine.MemoryTextFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Review finding 8: absent / corrupt / transient are different things; pairing keys and the journal are never silently wiped. */
class StoreRobustnessTest {

    private val dir: File = Files.createTempDirectory("ib-robust").toFile().also { it.deleteOnExit() }
    private val cipher = AesGcmBlobCipher(ByteArray(32) { it.toByte() })
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    // ---------------------------------------------------------------- idempotency journal

    @Test fun unreadableJournalRefusesDosingAndIsNeverOverwritten() {
        val file = MemoryTextFile().also { it.text = """[{"requestId":"${id(1)}","op":"setTBR","fp":"x","at":${System.currentTimeMillis()},"done":1,"outcome":{"ok":true,"result":null,"code":null,"message":null,"uncertain":false}}]""" }
        val before = file.text
        file.failReads = 1000 // the disk is unreadable for a while
        val store = IdempotencyStore(file, { System.currentTimeMillis() })
        assertThrows<IllegalStateException> { store.begin(id(2), "setTBR", "x") }
        assertThrows<IllegalStateException> { store.lookup(id(1)) }
        assertThat(file.text).isEqualTo(before) // not written, not wiped
        // the disk recovers: the next access retries and now sees the journal, the old outcome is still there
        file.failReads = 0
        val replay = store.begin(id(1), "setTBR", "x")
        assertThat(replay).isInstanceOf(IdempotencyStore.Begin.Replay::class.java)
        assertThat(store.begin(id(3), "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed)
    }

    @Test fun aTransientReadErrorIsRetried() {
        val file = MemoryTextFile().also { it.text = "[]" }
        file.failReads = 2 // fewer than the 3 attempts
        val store = IdempotencyStore(file, { 0L })
        assertThat(store.begin(id(1), "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed)
    }

    @Test fun corruptJournalIsMovedAsideNotOverwritten() {
        val file = MemoryTextFile().also { it.text = "{this is not a journal" }
        val store = IdempotencyStore(file, { 0L })
        assertThat(store.quarantinedCount).isEqualTo(1)
        assertThat(file.backup).isEqualTo("{this is not a journal")
        assertThat(store.begin(id(1), "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed)
    }

    // ---------------------------------------------------------------- encrypted secrets

    @Test fun tamperedSecretFileIsKeptAsBackupAndTheStoreStartsEmpty() {
        val f = File(dir, "a.bin")
        EncryptedFileKeyValueStore(f, cipher).putString("ltk", "secret")
        val original = f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        f.writeBytes(original)
        val s = EncryptedFileKeyValueStore(f, cipher)
        assertThat(s.recoveredFromCorruption).isTrue()
        assertThat(s.getString("ltk")).isNull()
        assertThat(File(dir, "a.bin.bak").readBytes()).isEqualTo(original)
        s.putString("ltk", "new") // writable again, backup untouched
        assertThat(File(dir, "a.bin.bak").readBytes()).isEqualTo(original)
    }

    private class FlakyCipher(private val inner: BlobCipher, var failures: Int) : BlobCipher {
        override fun encrypt(plain: ByteArray) = inner.encrypt(plain)
        override fun decrypt(data: ByteArray): ByteArray {
            if (failures > 0) { failures--; throw java.security.ProviderException("Keystore temporarily unavailable") }
            return inner.decrypt(data)
        }
    }

    @Test fun transientKeystoreFailureNeverWipesPairingKeys() {
        val f = File(dir, "b.bin")
        EncryptedFileKeyValueStore(f, cipher).edit(mapOf("ltk" to "secret", "paired" to "true"))
        val onDisk = f.readBytes()
        val flaky = FlakyCipher(cipher, failures = 1000) // the Keystore is unavailable for a while
        val s = EncryptedFileKeyValueStore(f, flaky)
        assertThat(s.available).isFalse()
        assertThat(s.getString("ltk")).isNull() // unknown, NOT "deleted"
        assertThrows<IllegalStateException> { s.putString("x", "y") }
        assertThat(f.readBytes()).isEqualTo(onDisk) // untouched
        assertThat(File(dir, "b.bin.bak").exists()).isFalse()
        // the Keystore comes back: lazily reloaded with everything intact
        flaky.failures = 0
        assertThat(s.getString("ltk")).isEqualTo("secret")
        assertThat(s.available).isTrue()
        s.putString("x", "y")
        assertThat(s.getString("paired")).isEqualTo("true")
    }

    @Test fun identityStoreSurvivesAnUnavailableStoreAtStartup() {
        val f = File(dir, "c.bin")
        EncryptedFileKeyValueStore(f, cipher).putString("bridgeId", "0011223344556677")
        val flaky = FlakyCipher(cipher, failures = 1000)
        val identity = KvIdentityStore(EncryptedFileKeyValueStore(f, flaky)) // must not throw
        assertThat(identity.ltk()).isNull()
        assertThat(identity.bridgeId).hasLength(8)
    }

    @Test fun existingButUnreadableTextFileThrowsInsteadOfLookingEmpty() {
        val asDirectory = File(dir, "journal.json").also { it.mkdirs() }
        assertThat(asDirectory.exists()).isTrue()
        assertThrows<java.io.IOException> { AtomicTextFile(asDirectory).read() }
        assertThat(AtomicTextFile(File(dir, "absent.json")).read()).isNull()
    }

    @Test fun corruptSettingsAreKeptAsBackup() {
        val f = File(dir, "settings.json")
        f.writeText("{broken")
        val s = SettingsStore(AtomicTextFile(f))
        assertThat(s.setupDone).isFalse()
        assertThat(File(dir, "settings.json.bak").readText()).isEqualTo("{broken")
    }

    @Test fun staleSimulatorFlagIsClearedAtStartAndNeverResumes() {
        val file = AtomicTextFile(File(dir, "sim.json"))
        SettingsStore(file).simulatorEnabled = true
        val restarted = SettingsStore(file)
        assertThat(restarted.clearStaleSimulatorFlag()).isTrue()
        assertThat(restarted.simulatorEnabled).isFalse()
        assertThat(SettingsStore(file).simulatorEnabled).isFalse()
        assertThat(restarted.clearStaleSimulatorFlag()).isFalse()
    }
}
