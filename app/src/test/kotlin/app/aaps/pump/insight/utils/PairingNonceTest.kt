package app.aaps.pump.insight.utils

import app.trio.insightbridge.store.KeyValueStore
import app.trio.insightbridge.store.MemoryKeyValueStore
import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import org.junit.jupiter.api.Test

/** Review finding 9: no fsync + Keystore write per Sight message, and a restart can never reuse a sent nonce. */
class PairingNonceTest {

    private class CountingStore : KeyValueStore {

        val inner = MemoryKeyValueStore()
        var writes = 0
        override fun getString(key: String) = inner.getString(key)
        override fun edit(changes: Map<String, String?>) { writes++; inner.edit(changes) }
    }

    @Test fun sentNonceIsPersistedInBlocksNotPerMessage() {
        val kv = CountingStore()
        val p = PairingDataStorage(kv)
        val n = Nonce()
        p.lastNonceSent = n
        val afterInit = kv.writes
        repeat(1000) { n.increment(); p.lastNonceSent = n }
        val writes = kv.writes - afterInit
        assertThat(writes).isAtMost(1000 / PairingDataStorage.NONCE_RESERVE + 2)
        assertThat(writes).isAtLeast(1)
    }

    @Test fun afterARestartTheStoredValueIsNeverBehindWhatWasSent() {
        val kv = CountingStore()
        val p = PairingDataStorage(kv)
        val n = Nonce()
        p.lastNonceSent = n
        var maxSent = BigInteger.ZERO
        repeat(777) { n.increment(); p.lastNonceSent = n; maxSent = BigInteger(n.storageValue) }
        // "crash": a new instance reads what was persisted
        val after = PairingDataStorage(kv.inner)
        assertThat(BigInteger(after.lastNonceSent!!.storageValue)).isAtLeast(maxSent)
        // continuing from there is strictly larger than anything sent before
        val resumed = after.lastNonceSent!!
        resumed.increment()
        assertThat(BigInteger(resumed.storageValue)).isGreaterThan(maxSent)
    }

    @Test fun receivedNonceIsPersistedLazily() {
        val kv = CountingStore()
        val p = PairingDataStorage(kv)
        val n = Nonce()
        repeat(500) { n.increment(); p.lastNonceReceived = Nonce(n.storageValue) }
        assertThat(kv.writes).isAtMost(500 / PairingDataStorage.RECEIVED_PERSIST_EVERY + 2)
        assertThat(p.lastNonceReceived!!.storageValue).isEqualTo(n.storageValue) // memory is always exact
        // after a restart only a replay window of at most RECEIVED_PERSIST_EVERY is lost, never a larger nonce
        val after = PairingDataStorage(kv.inner)
        assertThat(BigInteger(after.lastNonceReceived!!.storageValue)).isAtMost(BigInteger(n.storageValue))
    }
}
