/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI shows for iPhone pairing. */
sealed interface PairingUiState {

    data object Idle : PairingUiState
    /** Window open, waiting for the iPhone to start pairing. */
    data class Waiting(val secondsLeft: Int) : PairingUiState
    /** Show [code] on screen and let the user confirm that it matches the iPhone. */
    data class ConfirmCode(val code: String, val phoneConfirmed: Boolean, val userConfirmed: Boolean) : PairingUiState
    data object Done : PairingUiState
    data class Failed(val reason: String) : PairingUiState
}

/**
 * Owns the 120 s "Pair with iPhone" window and the user's decision on the 6-digit code.
 * The engine drives it; the UI observes [state] and calls [open], [userConfirm], [userReject], [cancel].
 */
class PairingController(private val clock: () -> Long, private val windowMillis: Long = WINDOW_MS) {

    companion object {

        const val WINDOW_MS = 120_000L
    }

    interface Listener {

        fun onUserDecision(accepted: Boolean)
    }

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    @Volatile var listener: Listener? = null
    private var windowEnd = 0L
    private var code: String? = null
    private var phoneConfirmed = false
    private var userConfirmed = false
    private var attemptUsed = false

    @Synchronized
    fun open() {
        windowEnd = clock() + windowMillis
        attemptUsed = false
        code = null
        phoneConfirmed = false
        userConfirmed = false
        _state.value = PairingUiState.Waiting((windowMillis / 1000).toInt())
    }

    /** A new attempt may start only while the window is open AND no attempt was made in it yet (PROTOCOL.md v2). */
    @Synchronized fun isWindowOpen(): Boolean = clock() < windowEnd && _state.value is PairingUiState.Waiting && !attemptUsed

    /** Engine: an iPhone started pairing (pairStart accepted). Uses up the window's single attempt. */
    @Synchronized
    fun beginAttempt(): Boolean {
        if (!isWindowOpen()) return false
        attemptUsed = true
        phoneConfirmed = false
        userConfirmed = false
        return true
    }

    /** Engine: the commitment was verified and the SAS is known; show it. */
    @Synchronized
    fun showCode(sas: String) {
        if (!attemptUsed || _state.value !is PairingUiState.Waiting) return
        code = sas
        _state.value = PairingUiState.ConfirmCode(sas, false, false)
    }

    @Synchronized
    fun phoneConfirmed() {
        val c = code ?: return
        phoneConfirmed = true
        _state.value = PairingUiState.ConfirmCode(c, true, userConfirmed)
    }

    /** Engine: the attempt ended without success. The window is closed; the user must tap "Pair with iPhone" again. */
    @Synchronized
    fun attemptAborted(reason: String) {
        if (!attemptUsed || _state.value is PairingUiState.Done) return
        code = null
        windowEnd = 0
        _state.value = PairingUiState.Failed(reason)
    }

    @Synchronized fun succeeded() { _state.value = PairingUiState.Done; windowEnd = 0 }

    @Synchronized fun failed(reason: String) { _state.value = PairingUiState.Failed(reason); windowEnd = 0; code = null }

    fun userConfirm() {
        synchronized(this) {
            val c = code ?: return
            userConfirmed = true
            _state.value = PairingUiState.ConfirmCode(c, phoneConfirmed, true)
        }
        listener?.onUserDecision(true)
    }

    fun userReject() {
        val had = synchronized(this) {
            val h = code != null
            _state.value = PairingUiState.Failed("rejected")
            windowEnd = 0
            code = null
            h
        }
        if (had) listener?.onUserDecision(false)
    }

    @Synchronized
    fun cancel() {
        windowEnd = 0
        code = null
        _state.value = PairingUiState.Idle
    }

    @Synchronized fun reset() = cancel()

    /** Called every second: counts the window down and expires it. Returns true if the window just expired. */
    @Synchronized
    fun tick(): Boolean {
        val s = _state.value
        if (s is PairingUiState.Waiting) {
            val left = ((windowEnd - clock()) / 1000).toInt()
            if (left <= 0) {
                _state.value = PairingUiState.Failed("timeout")
                return true
            }
            if (left != s.secondsLeft) _state.value = PairingUiState.Waiting(left)
        } else if (s is PairingUiState.ConfirmCode && clock() >= windowEnd + windowMillis) {
            _state.value = PairingUiState.Failed("timeout")
            code = null
            return true
        }
        return false
    }
}

/** Durable identity and pairing data of the bridge. LTK must live in Keystore-wrapped storage. */
interface IdentityStore {

    /** 8 random bytes, generated once. */
    val bridgeId: ByteArray

    fun ltk(): ByteArray?
    fun clientId(): String?
    fun savePairing(ltk: ByteArray, clientId: String?)
    fun bindClientId(clientId: String)
    fun clearPairing()
}

class MemoryIdentityStore(override val bridgeId: ByteArray = ByteArray(8) { (it + 1).toByte() }) : IdentityStore {

    private var ltk: ByteArray? = null
    private var client: String? = null
    override fun ltk() = ltk?.copyOf()
    override fun clientId() = client
    override fun savePairing(ltk: ByteArray, clientId: String?) { this.ltk = ltk.copyOf(); client = clientId }
    override fun bindClientId(clientId: String) { client = clientId }
    override fun clearPairing() { ltk = null; client = null }
}
