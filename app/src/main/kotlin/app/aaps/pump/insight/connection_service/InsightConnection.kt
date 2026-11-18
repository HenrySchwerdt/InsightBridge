/*
 * This file is part of InsightBridge and is derived from the Accu-Chek Insight driver of AndroidAPS
 * (https://github.com/nightscout/AndroidAPS, module pump/insight).
 * Copyright (C) AndroidAPS contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, version 3 or any
 * later version. It is distributed WITHOUT ANY WARRANTY. See the LICENSE file in the repository root.
 *
 * Changes for InsightBridge: AndroidAPS infrastructure (dependency injection, database, rx bus,
 * preferences, plugin framework, UI) removed; plain Kotlin/coroutines, Android Keystore-wrapped storage
 * and robustness fixes added where noted in the code.
 */
package app.aaps.pump.insight.connection_service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.PowerManager
import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.AppLayerMessage.Companion.unwrap
import app.aaps.pump.insight.app_layer.AppLayerMessage.Companion.wrap
import app.aaps.pump.insight.app_layer.ReadParameterBlockMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.app_layer.configuration.CloseConfigurationWriteSessionMessage
import app.aaps.pump.insight.app_layer.configuration.OpenConfigurationWriteSessionMessage
import app.aaps.pump.insight.app_layer.configuration.WriteConfigurationBlockMessage
import app.aaps.pump.insight.app_layer.connection.ActivateServiceMessage
import app.aaps.pump.insight.app_layer.connection.BindMessage
import app.aaps.pump.insight.app_layer.connection.ConnectMessage
import app.aaps.pump.insight.app_layer.connection.DisconnectMessage
import app.aaps.pump.insight.app_layer.connection.ServiceChallengeMessage
import app.aaps.pump.insight.app_layer.parameter_blocks.SystemIdentificationBlock
import app.aaps.pump.insight.app_layer.status.GetFirmwareVersionsMessage
import app.aaps.pump.insight.descriptors.FirmwareVersions
import app.aaps.pump.insight.descriptors.InsightState
import app.aaps.pump.insight.descriptors.SystemIdentification
import app.aaps.pump.insight.exceptions.ConnectionFailedException
import app.aaps.pump.insight.exceptions.ConnectionLostException
import app.aaps.pump.insight.exceptions.DisconnectedException
import app.aaps.pump.insight.exceptions.InsightException
import app.aaps.pump.insight.exceptions.InvalidNonceException
import app.aaps.pump.insight.exceptions.InvalidSatlCommandException
import app.aaps.pump.insight.exceptions.ReceivedPacketInInvalidStateException
import app.aaps.pump.insight.exceptions.TimeoutException
import app.aaps.pump.insight.exceptions.TooChattyPumpException
import app.aaps.pump.insight.exceptions.satl_errors.SatlCompatibleStateErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlDecryptVerifyFailedErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlIncompatibleVersionErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidCRCErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidCommIdErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidMacErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidMessageTypeErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidNonceErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidPacketErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlInvalidPayloadLengthErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlNoneErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlPairingRejectedException
import app.aaps.pump.insight.exceptions.satl_errors.SatlUndefinedErrorException
import app.aaps.pump.insight.exceptions.satl_errors.SatlWrongStateException
import app.aaps.pump.insight.satl.ConnectionRequest
import app.aaps.pump.insight.satl.ConnectionResponse
import app.aaps.pump.insight.satl.DataMessage
import app.aaps.pump.insight.satl.ErrorMessage
import app.aaps.pump.insight.satl.KeyRequest
import app.aaps.pump.insight.satl.KeyResponse
import app.aaps.pump.insight.satl.PairingStatus
import app.aaps.pump.insight.satl.SatlError
import app.aaps.pump.insight.satl.SatlMessage
import app.aaps.pump.insight.satl.SatlMessage.Companion.deserialize
import app.aaps.pump.insight.satl.SatlMessage.Companion.hasCompletePacket
import app.aaps.pump.insight.satl.SynAckResponse
import app.aaps.pump.insight.satl.SynRequest
import app.aaps.pump.insight.satl.VerifyConfirmRequest
import app.aaps.pump.insight.satl.VerifyConfirmResponse
import app.aaps.pump.insight.satl.VerifyDisplayRequest
import app.aaps.pump.insight.satl.VerifyDisplayResponse
import app.aaps.pump.insight.utils.ByteBuf
import app.aaps.pump.insight.utils.ConnectionEstablisher
import app.aaps.pump.insight.utils.DelayedActionThread
import app.aaps.pump.insight.utils.DelayedActionThread.Companion.runDelayed
import app.aaps.pump.insight.utils.InputStreamReader
import app.aaps.pump.insight.utils.Nonce
import app.aaps.pump.insight.utils.OutputStreamWriter
import app.aaps.pump.insight.utils.PairingDataStorage
import app.aaps.pump.insight.utils.PumpLog
import app.aaps.pump.insight.utils.ReconnectPolicy
import app.aaps.pump.insight.utils.crypto.Cryptograph.combine
import app.aaps.pump.insight.utils.crypto.Cryptograph.decryptRSA
import app.aaps.pump.insight.utils.crypto.Cryptograph.deriveKeys
import app.aaps.pump.insight.utils.crypto.Cryptograph.generateRSAKey
import app.aaps.pump.insight.utils.crypto.Cryptograph.getServicePasswordHash
import app.aaps.pump.insight.utils.crypto.KeyPair
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.spongycastle.crypto.InvalidCipherTextException

/**
 * The Sight link to the Insight pump: RFCOMM socket, SATL transport, app layer, service
 * activation, request queue and reconnect.
 *
 * Ported from AndroidAPS `InsightConnectionService` (AGPL-3.0). It is no longer an Android
 * Service (the bridge's foreground service owns the process lifetime) and was hardened for
 * months of uptime: capped exponential reconnect backoff that never gives up ([ReconnectPolicy]),
 * generation-checked response timers, timed wake locks, exception-proof reader/writer threads,
 * and `transmitted` tracking on requests so callers can tell "not sent" from "outcome unknown".
 *
 * All public methods are synchronized on this instance; callbacks run on arbitrary threads and
 * must be quick and non-blocking.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is verified by [bluetoothPermitted] before use
class InsightConnection(
    private val context: Context,
    internal val pairingDataStorage: PairingDataStorage,
    private val bluetoothPermitted: () -> Boolean,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy()
) : ConnectionEstablisher.Callback, InputStreamReader.Callback, OutputStreamWriter.Callback {

    private val stateCallbacks: MutableList<StateCallback> = ArrayList()
    private val connectionRequests: MutableList<Any> = ArrayList()
    private val exceptionCallbacks: MutableList<ExceptionCallback> = ArrayList()

    private val _stateFlow = MutableStateFlow(if (pairingDataStorage.paired) InsightState.DISCONNECTED else InsightState.NOT_PAIRED)
    val stateFlow: StateFlow<InsightState> = _stateFlow.asStateFlow()
    val state: InsightState get() = _stateFlow.value

    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InsightBridge:PumpExchange")
            .apply { setReferenceCounted(false) }

    private var disconnectTimer: DelayedActionThread? = null
    private var recoveryTimer: DelayedActionThread? = null
    private var timeoutTimer: DelayedActionThread? = null
    private var timerGeneration = 0L
    private var connectGeneration = 0L
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var connectionEstablisher: ConnectionEstablisher? = null
    private var inputStreamReader: InputStreamReader? = null
    private var outputStreamWriter: OutputStreamWriter? = null
    private var keyRequest: KeyRequest? = null
    private val buffer = ByteBuf(BUFFER_SIZE)
    @get:Synchronized var verificationString: String? = null
        private set
    private val messageQueue = MessageQueue()
    private val activatedServices: MutableList<Service?> = ArrayList()

    private val _lastDataTime = MutableStateFlow(0L)
    /** When the pump last answered anything (epoch ms, 0 = never). */
    val lastDataTimeFlow: StateFlow<Long> = _lastDataTime.asStateFlow()
    val lastDataTime: Long get() = _lastDataTime.value
    var lastConnected: Long = 0
        private set
    /** Delay before a retry that is currently scheduled (for diagnostics). */
    @get:Synchronized var recoveryDuration: Long = 0
        private set
    /** How long after the last connection lock was released the link is kept (0 .. 15000 ms). */
    @Volatile var disconnectDelayMillis: Long = 10_000

    private var connectionAttempts = 0
    private var connectionSeriesStart = 0L
    private var timeoutDuringHandshakeCounter = 0
    private var intKeyPair: KeyPair? = null
    private var intRandomBytes: ByteArray? = null

    val pumpFirmwareVersions: FirmwareVersions? get() = pairingDataStorage.firmwareVersions
    val pumpSystemIdentification: SystemIdentification? get() = pairingDataStorage.systemIdentification
    val bluetoothAddress: String? get() = pairingDataStorage.macAddress
    @get:Synchronized val isPaired: Boolean get() = pairingDataStorage.paired
    @get:Synchronized val consecutiveConnectFailures: Int get() = reconnectPolicy.consecutiveFailures

    @Synchronized fun registerStateCallback(c: StateCallback) { stateCallbacks.add(c) }
    @Synchronized fun unregisterStateCallback(c: StateCallback) { stateCallbacks.remove(c) }
    @Synchronized fun registerExceptionCallback(c: ExceptionCallback) { exceptionCallbacks.add(c) }
    @Synchronized fun unregisterExceptionCallback(c: ExceptionCallback) { exceptionCallbacks.remove(c) }

    @Synchronized fun confirmVerificationString() {
        setState(InsightState.SATL_VERIFY_CONFIRM_REQUEST)
        sendSatlMessage(VerifyConfirmRequest())
    }

    @Synchronized fun rejectVerificationString() {
        handleException(SatlPairingRejectedException())
    }

    // ------------------------------------------------------------------ requests

    @Synchronized fun <T : AppLayerMessage> requestMessage(message: T): MessageRequest<T> {
        val messageRequest: MessageRequest<T>
        if (state !== InsightState.CONNECTED) {
            messageRequest = MessageRequest(message)
            messageRequest.exception = DisconnectedException()
            return messageRequest
        }
        touchWakeLock()
        if (message is WriteConfigurationBlockMessage) {
            val openRequest = MessageRequest(OpenConfigurationWriteSessionMessage())
            val closeRequest = MessageRequest(CloseConfigurationWriteSessionMessage())
            messageRequest = ConfigurationMessageRequest(message, openRequest, closeRequest)
            messageQueue.enqueueRequest(openRequest)
            messageQueue.enqueueRequest(messageRequest)
            messageQueue.enqueueRequest(closeRequest)
        } else {
            messageRequest = MessageRequest(message)
            messageQueue.enqueueRequest(messageRequest)
        }
        requestNextMessage()
        return messageRequest
    }

    /**
     * Writes several configuration blocks inside ONE write session (see [ConfigurationWriteSessionRequest]).
     * Same-priority messages keep their enqueue order (stable sort).
     */
    @Synchronized fun requestConfigurationWrites(messages: List<WriteConfigurationBlockMessage>): ConfigurationWriteSessionRequest {
        val openRequest = MessageRequest(OpenConfigurationWriteSessionMessage())
        val writeRequests = messages.map { MessageRequest(it) }
        val closeRequest = MessageRequest(CloseConfigurationWriteSessionMessage())
        if (state !== InsightState.CONNECTED) {
            val exception = DisconnectedException()
            openRequest.exception = exception
            writeRequests.forEach { it.exception = exception }
            closeRequest.exception = exception
            return ConfigurationWriteSessionRequest(openRequest, writeRequests, closeRequest)
        }
        touchWakeLock()
        messageQueue.enqueueRequest(openRequest)
        writeRequests.forEach { messageQueue.enqueueRequest(it) }
        messageQueue.enqueueRequest(closeRequest)
        requestNextMessage()
        return ConfigurationWriteSessionRequest(openRequest, writeRequests, closeRequest)
    }

    private fun requestNextMessage() {
        while (messageQueue.activeRequest == null && messageQueue.hasPendingMessages()) {
            messageQueue.nextRequest()
            val active = messageQueue.activeRequest ?: break
            val service = active.request.service
            if (service !== Service.CONNECTION && !activatedServices.contains(service)) {
                if (service!!.servicePassword == null) {
                    val activateServiceMessage = ActivateServiceMessage()
                    activateServiceMessage.serviceID = service.id
                    activateServiceMessage.version = service.version
                    activateServiceMessage.servicePassword = ByteArray(16)
                    sendAppLayerMessage(activateServiceMessage)
                } else {
                    val serviceChallengeMessage = ServiceChallengeMessage()
                    serviceChallengeMessage.serviceID = service.id
                    serviceChallengeMessage.version = service.version
                    sendAppLayerMessage(serviceChallengeMessage)
                }
            } else {
                active.transmitted = true
                sendAppLayerMessage(active.request)
            }
        }
    }

    // ------------------------------------------------------------------ state / leases

    private fun touchWakeLock() {
        try {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            PumpLog.w("wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Throwable) {
        }
    }

    private fun setState(newState: InsightState) {
        if (state === newState) return
        if (state === InsightState.CONNECTED) lastConnected = System.currentTimeMillis()
        if (newState === InsightState.DISCONNECTED || newState === InsightState.NOT_PAIRED) releaseWakeLock() else touchWakeLock()
        _stateFlow.value = newState
        for (cb in stateCallbacks.toList()) {
            try {
                cb.onStateChanged(newState)
            } catch (t: Throwable) {
                PumpLog.e("state callback failed", t)
            }
        }
        PumpLog.i("Insight state: ${newState.name}")
    }

    @Synchronized fun requestConnection(lock: Any) {
        if (connectionRequests.contains(lock)) return
        connectionRequests.add(lock)
        disconnectTimer?.interrupt()
        disconnectTimer = null
        if (state === InsightState.DISCONNECTED && pairingDataStorage.paired) {
            recoveryDuration = 0
            connectionAttempts = 0
            timeoutDuringHandshakeCounter = 0
            connect()
        } else if (state === InsightState.RECOVERING && pairingDataStorage.paired) {
            // Somebody wants the pump NOW (a command): do not make them wait out the backoff timer.
            recoveryTimer?.interrupt()
            recoveryTimer = null
            timerGeneration++
            connect()
        }
    }

    @Synchronized fun withdrawConnectionRequest(lock: Any) {
        if (!connectionRequests.contains(lock)) return
        connectionRequests.remove(lock)
        if (connectionRequests.isEmpty()) {
            if (connectionAttempts > 0) {
                PumpLog.i("Gave up after $connectionAttempts attempt(s) in ${System.currentTimeMillis() - connectionSeriesStart} ms")
                connectionAttempts = 0
            }
            if (state === InsightState.RECOVERING) {
                recoveryTimer?.interrupt()
                recoveryTimer = null
                setState(InsightState.DISCONNECTED)
                cleanup(true)
            } else if (state !== InsightState.DISCONNECTED) {
                val delay = disconnectDelayMillis.coerceIn(0, 15_000)
                PumpLog.d("Last connection lock released, disconnecting in $delay ms")
                disconnectTimer = runDelayed("Disconnect Timer", delay) {
                    synchronized(this@InsightConnection) {
                        // A new lease may have arrived while the timer was firing.
                        if (connectionRequests.isEmpty()) disconnect()
                    }
                }
            }
        }
    }

    @Synchronized fun hasRequestedConnection(lock: Any): Boolean = connectionRequests.contains(lock)

    private fun cleanup(closeSocket: Boolean) {
        messageQueue.completeActiveRequest(ConnectionLostException())
        messageQueue.completePendingRequests(ConnectionLostException())
        recoveryTimer?.interrupt()
        recoveryTimer = null
        disconnectTimer?.interrupt()
        disconnectTimer = null
        inputStreamReader?.close()
        inputStreamReader = null
        outputStreamWriter?.close()
        outputStreamWriter = null
        connectionEstablisher?.let {
            if (closeSocket) {
                it.close(closeSocket)
                bluetoothSocket = null
            }
        }
        connectionEstablisher = null
        timerGeneration++
        connectGeneration++
        timeoutTimer?.interrupt()
        timeoutTimer = null
        buffer.clear()
        verificationString = null
        intKeyPair = null
        intRandomBytes = null
        keyRequest = null
        activatedServices.clear()
        if (!pairingDataStorage.paired) {
            bluetoothSocket = null
            bluetoothDevice = null
            pairingDataStorage.reset()
        }
    }

    private fun causeText(e: Exception): String = e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: ""

    @Synchronized private fun handleException(e: Exception) {
        when (state) {
            InsightState.NOT_PAIRED, InsightState.DISCONNECTED, InsightState.RECOVERING -> return
            else -> Unit
        }
        val detail = if (e is ConnectionFailedException) " after ${e.durationOfConnectionAttempt} ms" + causeText(e)
        else causeText(e).ifEmpty { e.message?.let { ": $it" } ?: "" }
        PumpLog.i("Pump link exception: ${e.javaClass.simpleName}$detail")
        if (pairingDataStorage.paired) {
            if (e is TimeoutException && (state === InsightState.SATL_SYN_REQUEST || state === InsightState.APP_CONNECT_MESSAGE)) {
                if (++timeoutDuringHandshakeCounter == TIMEOUT_DURING_HANDSHAKE_NOTIFICATION_THRESHOLD) {
                    for (cb in stateCallbacks.toList()) {
                        try { cb.onTimeoutDuringHandshake() } catch (t: Throwable) { PumpLog.e("callback", t) }
                    }
                }
            }
            setState(if (connectionRequests.isNotEmpty()) InsightState.RECOVERING else InsightState.DISCONNECTED)
            // A BluetoothSocket cannot be connected twice; always drop it (see AAPS history, #5209 etc.).
            cleanup(true)
            messageQueue.completeActiveRequest(e)
            messageQueue.completePendingRequests(e)
            if (connectionRequests.isNotEmpty()) {
                val delay = reconnectPolicy.nextDelayMillis()
                recoveryDuration = delay
                if (delay == 0L) connect() else {
                    val generation = timerGeneration
                    recoveryTimer = runDelayed("RecoveryTimer", delay) {
                        synchronized(this@InsightConnection) {
                            if (generation == timerGeneration && state === InsightState.RECOVERING) {
                                recoveryTimer = null
                                connect()
                            }
                        }
                    }
                }
            }
        } else {
            setState(InsightState.NOT_PAIRED)
            cleanup(true)
        }
        for (cb in exceptionCallbacks.toList()) {
            try { cb.onExceptionOccur(e) } catch (t: Throwable) { PumpLog.e("callback", t) }
        }
    }

    @Synchronized private fun disconnect() {
        if (state === InsightState.CONNECTED) {
            sendAppLayerMessage(DisconnectMessage())
            sendSatlMessageAndWait(app.aaps.pump.insight.satl.DisconnectMessage())
        }
        cleanup(true)
        setState(if (pairingDataStorage.paired) InsightState.DISCONNECTED else InsightState.NOT_PAIRED)
    }

    /** Forgets the pairing and drops the link. */
    @Synchronized fun reset() {
        connectionRequests.clear()
        pairingDataStorage.reset()
        disconnect()
        reconnectPolicy.reset()
    }

    /** Shuts everything down (service stop). */
    @Synchronized fun shutdown() {
        connectionRequests.clear()
        try { disconnect() } catch (t: Throwable) { PumpLog.w("shutdown", t) }
        releaseWakeLock()
    }

    /** Starts Sight pairing with the pump at [macAddress]. Needs a connection lock to be held. */
    @Synchronized fun pair(macAddress: String?) {
        check(!pairingDataStorage.paired) { "Pump must be unbonded first." }
        check(connectionRequests.isNotEmpty()) { "A connection lock must be hold for pairing" }
        PumpLog.i("Pairing initiated")
        cleanup(true)
        pairingDataStorage.macAddress = macAddress
        connect()
    }

    private fun adapter(): BluetoothAdapter? {
        if (!bluetoothPermitted()) return null
        return (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Synchronized private fun connect() {
        val adapter = adapter()
        val mac = pairingDataStorage.macAddress
        if (adapter == null || mac == null) {
            // No permission or no adapter right now: keep retrying with backoff instead of stalling.
            setState(InsightState.CONNECTING)
            handleException(ConnectionFailedException(0, IOException("Bluetooth unavailable or permission missing")))
            return
        }
        try {
            if (bluetoothDevice == null) bluetoothDevice = adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            setState(InsightState.CONNECTING)
            handleException(ConnectionFailedException(0, e))
            return
        }
        if (connectionAttempts == 0) connectionSeriesStart = System.currentTimeMillis()
        connectionAttempts++
        setState(InsightState.CONNECTING)
        armConnectTimeout()
        bluetoothDevice?.let { device ->
            connectionEstablisher = ConnectionEstablisher(this, !pairingDataStorage.paired, adapter, device, bluetoothSocket).also { it.start() }
        }
    }

    /**
     * A socket connect() can hang far longer than anybody is willing to wait (and cannot be interrupted except by
     * closing the socket). Give up after [CONNECT_TIMEOUT_MS]: cleanup closes the socket and the normal backoff
     * ([handleException] -> RECOVERING) takes over.
     */
    private fun armConnectTimeout() {
        val generation = ++connectGeneration
        runDelayed("ConnectTimeout", CONNECT_TIMEOUT_MS) {
            synchronized(this@InsightConnection) {
                if (generation == connectGeneration && state === InsightState.CONNECTING) {
                    handleException(ConnectionFailedException(CONNECT_TIMEOUT_MS, IOException("connect timed out")))
                }
            }
        }
    }

    @Synchronized override fun onSocketCreated(bluetoothSocket: BluetoothSocket?) {
        this.bluetoothSocket = bluetoothSocket
    }

    @Synchronized override fun onConnectionSucceed() {
        connectGeneration++
        try {
            PumpLog.i("Connected after $connectionAttempts attempt(s) in ${System.currentTimeMillis() - connectionSeriesStart} ms")
            connectionAttempts = 0
            val socket = bluetoothSocket ?: throw IOException("socket vanished")
            inputStreamReader = InputStreamReader(socket.inputStream, this).also { it.start() }
            outputStreamWriter = OutputStreamWriter(socket.outputStream, this).also { it.start() }
            if (pairingDataStorage.paired) {
                setState(InsightState.SATL_SYN_REQUEST)
                sendSatlMessage(SynRequest())
            } else {
                setState(InsightState.SATL_CONNECTION_REQUEST)
                sendSatlMessage(ConnectionRequest())
            }
        } catch (e: IOException) {
            handleException(e)
        }
    }

    // ------------------------------------------------------------------ SATL

    @Synchronized override fun onReceiveBytes(buffer: ByteArray, bytesRead: Int) {
        try {
            this.buffer.putBytes(buffer, bytesRead)
            while (hasCompletePacket(this.buffer)) {
                val satlMessage = deserialize(this.buffer, pairingDataStorage.lastNonceReceived, pairingDataStorage.incomingKey)
                satlMessage?.let {
                    if (pairingDataStorage.incomingKey != null && pairingDataStorage.lastNonceReceived != null &&
                        !pairingDataStorage.lastNonceReceived!!.isSmallerThan(it.nonce!!)
                    ) {
                        throw InvalidNonceException()
                    } else processSatlMessage(it)
                }
            }
        } catch (e: InsightException) {
            handleException(e)
        } catch (e: Exception) {
            // Malformed data from the pump (index errors, overflow): treat as a broken link, never crash.
            PumpLog.w("Unparseable data from pump", e)
            handleException(ConnectionLostException())
        }
    }

    private fun prepareSatlMessage(satlMessage: SatlMessage): ByteArray {
        satlMessage.commID = pairingDataStorage.commId
        val nonce = pairingDataStorage.lastNonceSent
        nonce?.let {
            it.increment()
            pairingDataStorage.lastNonceSent = it
            satlMessage.nonce = it
        }
        val serialized = satlMessage.serialize(pairingDataStorage.outgoingKey)
        timerGeneration++
        val generation = timerGeneration
        timeoutTimer?.interrupt()
        timeoutTimer = runDelayed("TimeoutTimer", RESPONSE_TIMEOUT) {
            synchronized(this@InsightConnection) {
                if (generation != timerGeneration) return@synchronized // answered meanwhile
                timeoutTimer = null
                handleException(TimeoutException())
            }
        }
        return serialized.bytes
    }

    private fun sendSatlMessage(satlMessage: SatlMessage) = outputStreamWriter?.write(prepareSatlMessage(satlMessage))

    private fun sendSatlMessageAndWait(satlMessage: SatlMessage) = outputStreamWriter?.writeAndWait(prepareSatlMessage(satlMessage))

    private fun processSatlMessage(satlMessage: SatlMessage?) {
        timerGeneration++
        timeoutTimer?.interrupt()
        timeoutTimer = null
        satlMessage?.let { pairingDataStorage.lastNonceReceived = it.nonce }
        if (satlMessage is ConnectionResponse) processConnectionResponse()
        else if (satlMessage is KeyResponse) processKeyResponse(satlMessage)
        else if (satlMessage is VerifyDisplayResponse) processVerifyDisplayResponse()
        else if (satlMessage is VerifyConfirmResponse) processVerifyConfirmResponse(satlMessage)
        else if (satlMessage is DataMessage) processDataMessage(satlMessage)
        else if (satlMessage is SynAckResponse) processSynAckResponse()
        else if (satlMessage is ErrorMessage) processErrorMessage(satlMessage)
        else handleException(InvalidSatlCommandException())
    }

    private fun processConnectionResponse() {
        if (state !== InsightState.SATL_CONNECTION_REQUEST) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        val kp = generateRSAKey().also { intKeyPair = it }
        val rnd = ByteArray(28).also { SecureRandom().nextBytes(it); intRandomBytes = it }
        keyRequest = KeyRequest().also {
            it.setPreMasterKey(kp.publicKeyBytes)
            it.setRandomBytes(rnd)
            setState(InsightState.SATL_KEY_REQUEST)
            sendSatlMessage(it)
        }
    }

    private fun processKeyResponse(keyResponse: KeyResponse) {
        if (state !== InsightState.SATL_KEY_REQUEST) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        try {
            val kp = intKeyPair ?: throw ReceivedPacketInInvalidStateException()
            val rnd = intRandomBytes ?: throw ReceivedPacketInInvalidStateException()
            val derivedKeys = deriveKeys(
                verificationSeed = combine(keyRequest!!.satlContent, keyResponse.satlContent),
                secret = decryptRSA(kp.privateKey, keyResponse.preMasterSecret),
                random = rnd,
                peerRandom = keyResponse.randomData
            )
            pairingDataStorage.commId = keyResponse.commID
            keyRequest = null
            intRandomBytes = null
            intKeyPair = null
            verificationString = derivedKeys.verificationString
            pairingDataStorage.outgoingKey = derivedKeys.outgoingKey
            pairingDataStorage.incomingKey = derivedKeys.incomingKey
            pairingDataStorage.lastNonceSent = Nonce()
            setState(InsightState.SATL_VERIFY_DISPLAY_REQUEST)
            sendSatlMessage(VerifyDisplayRequest())
        } catch (e: InvalidCipherTextException) {
            handleException(e)
        } catch (e: InsightException) {
            handleException(e)
        }
    }

    private fun processVerifyDisplayResponse() {
        if (state !== InsightState.SATL_VERIFY_DISPLAY_REQUEST) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        setState(InsightState.AWAITING_CODE_CONFIRMATION)
    }

    private fun processVerifyConfirmResponse(r: VerifyConfirmResponse) {
        if (state !== InsightState.SATL_VERIFY_CONFIRM_REQUEST) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        when (r.pairingStatus) {
            PairingStatus.CONFIRMED -> {
                verificationString = null
                setState(InsightState.APP_BIND_MESSAGE)
                sendAppLayerMessage(BindMessage())
            }
            PairingStatus.PENDING -> try {
                Thread.sleep(200)
                sendSatlMessage(VerifyConfirmRequest())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            PairingStatus.REJECTED -> handleException(SatlPairingRejectedException())
        }
    }

    private fun processSynAckResponse() {
        if (state !== InsightState.SATL_SYN_REQUEST) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        setState(InsightState.APP_CONNECT_MESSAGE)
        sendAppLayerMessage(ConnectMessage())
    }

    private fun processErrorMessage(m: ErrorMessage) {
        when (m.error) {
            SatlError.INVALID_NONCE -> handleException(SatlInvalidNonceErrorException())
            SatlError.INVALID_CRC -> handleException(SatlInvalidCRCErrorException())
            SatlError.INVALID_MAC_TRAILER -> handleException(SatlInvalidMacErrorException())
            SatlError.DECRYPT_VERIFY_FAILED -> handleException(SatlDecryptVerifyFailedErrorException())
            SatlError.INVALID_PAYLOAD_LENGTH -> handleException(SatlInvalidPayloadLengthErrorException())
            SatlError.INVALID_MESSAGE_TYPE -> handleException(SatlInvalidMessageTypeErrorException())
            SatlError.INCOMPATIBLE_VERSION -> handleException(SatlIncompatibleVersionErrorException())
            SatlError.COMPATIBLE_STATE -> handleException(SatlCompatibleStateErrorException())
            SatlError.INVALID_COMM_ID -> handleException(SatlInvalidCommIdErrorException())
            SatlError.INVALID_PACKET -> handleException(SatlInvalidPacketErrorException())
            SatlError.WRONG_STATE -> handleException(SatlWrongStateException())
            SatlError.UNDEFINED -> handleException(SatlUndefinedErrorException())
            SatlError.NONE -> handleException(SatlNoneErrorException())
        }
    }

    // ------------------------------------------------------------------ app layer

    private fun processDataMessage(dataMessage: DataMessage) {
        when (state) {
            InsightState.CONNECTED,
            InsightState.APP_BIND_MESSAGE,
            InsightState.APP_CONNECT_MESSAGE,
            InsightState.APP_ACTIVATE_PARAMETER_SERVICE,
            InsightState.APP_ACTIVATE_STATUS_SERVICE,
            InsightState.APP_FIRMWARE_VERSIONS,
            InsightState.APP_SYSTEM_IDENTIFICATION -> Unit
            else -> {
                handleException(ReceivedPacketInInvalidStateException())
                return
            }
        }
        try {
            val appLayerMessage = unwrap(dataMessage)
            when (appLayerMessage) {
                is BindMessage -> processBindMessage()
                is ConnectMessage -> processConnectMessage()
                is ActivateServiceMessage -> processActivateServiceMessage()
                is DisconnectMessage -> Unit
                is ServiceChallengeMessage -> processServiceChallengeMessage(appLayerMessage)
                is GetFirmwareVersionsMessage -> processFirmwareVersionsMessage(appLayerMessage)
                is ReadParameterBlockMessage -> processReadParameterBlockMessage(appLayerMessage)
                else -> processGenericAppLayerMessage(appLayerMessage)
            }
        } catch (e: Exception) {
            if (state !== InsightState.CONNECTED) {
                handleException(e)
            } else {
                if (messageQueue.activeRequest == null) {
                    handleException(TooChattyPumpException())
                } else {
                    messageQueue.completeActiveRequest(e)
                    requestNextMessage()
                }
            }
        }
    }

    private fun processBindMessage() {
        if (state !== InsightState.APP_BIND_MESSAGE) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        setState(InsightState.APP_ACTIVATE_STATUS_SERVICE)
        sendAppLayerMessage(ActivateServiceMessage().also {
            it.serviceID = Service.STATUS.id
            it.servicePassword = ByteArray(16)
            it.version = Service.STATUS.version
        })
    }

    private fun processFirmwareVersionsMessage(message: GetFirmwareVersionsMessage) {
        if (state !== InsightState.APP_FIRMWARE_VERSIONS) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        pairingDataStorage.firmwareVersions = message.firmwareVersions
        setState(InsightState.APP_ACTIVATE_PARAMETER_SERVICE)
        sendAppLayerMessage(ActivateServiceMessage().also {
            it.serviceID = Service.PARAMETER.id
            it.servicePassword = ByteArray(16)
            it.version = Service.PARAMETER.version
        })
    }

    private fun processConnectMessage() {
        if (state !== InsightState.APP_CONNECT_MESSAGE) {
            handleException(ReceivedPacketInInvalidStateException())
            return
        }
        setState(InsightState.CONNECTED)
    }

    private fun processActivateServiceMessage() {
        if (state === InsightState.APP_ACTIVATE_PARAMETER_SERVICE) {
            activatedServices.add(Service.PARAMETER)
            setState(InsightState.APP_SYSTEM_IDENTIFICATION)
            val message = ReadParameterBlockMessage()
            message.parameterBlockId = SystemIdentificationBlock::class.java
            message.service = Service.PARAMETER
            sendAppLayerMessage(message)
        } else if (state === InsightState.APP_ACTIVATE_STATUS_SERVICE) {
            activatedServices.add(Service.STATUS)
            setState(InsightState.APP_FIRMWARE_VERSIONS)
            sendAppLayerMessage(GetFirmwareVersionsMessage())
        } else {
            messageQueue.activeRequest?.let {
                activatedServices.add(it.request.service)
                it.transmitted = true
                sendAppLayerMessage(it.request)
            } ?: handleException(TooChattyPumpException())
        }
    }

    private fun processReadParameterBlockMessage(message: ReadParameterBlockMessage) {
        if (state === InsightState.APP_SYSTEM_IDENTIFICATION) {
            if (message.parameterBlock !is SystemIdentificationBlock) handleException(TooChattyPumpException()) else {
                val systemIdentification = (message.parameterBlock as SystemIdentificationBlock).systemIdentification
                pairingDataStorage.systemIdentification = systemIdentification
                pairingDataStorage.paired = true
                PumpLog.i("Pump pairing completed")
                reconnectPolicy.reset()
                setState(InsightState.CONNECTED)
                for (cb in stateCallbacks.toList()) {
                    try { cb.onPumpPaired() } catch (t: Throwable) { PumpLog.e("callback", t) }
                }
            }
        } else processGenericAppLayerMessage(message)
    }

    private fun processServiceChallengeMessage(m: ServiceChallengeMessage) {
        messageQueue.activeRequest?.let { messageRequest ->
            val service = messageRequest.request.service
            val activate = ActivateServiceMessage()
            if (service != null) {
                activate.serviceID = service.id
                activate.version = service.version
            }
            service?.run { servicePassword?.let { activate.servicePassword = getServicePasswordHash(it, m.randomData) } }
            sendAppLayerMessage(activate)
        } ?: handleException(TooChattyPumpException())
    }

    private fun processGenericAppLayerMessage(appLayerMessage: AppLayerMessage) {
        if (messageQueue.activeRequest == null) handleException(TooChattyPumpException()) else {
            messageQueue.completeActiveRequest(appLayerMessage)
            _lastDataTime.value = System.currentTimeMillis()
            reconnectPolicy.onHealthy()
            requestNextMessage()
        }
    }

    private fun sendAppLayerMessage(appLayerMessage: AppLayerMessage?) {
        appLayerMessage?.let { sendSatlMessage(wrap(it)) }
    }

    @Synchronized override fun onConnectionFail(e: Exception?, duration: Long) {
        handleException(ConnectionFailedException(duration, e))
    }

    @Synchronized override fun onErrorWhileReading(e: Exception) {
        handleException(ConnectionLostException())
    }

    @Synchronized override fun onErrorWhileWriting(e: Exception) {
        handleException(ConnectionLostException())
    }

    interface StateCallback {

        fun onStateChanged(state: InsightState?)
        fun onPumpPaired() {}
        fun onTimeoutDuringHandshake() {}
    }

    interface ExceptionCallback {

        fun onExceptionOccur(e: Exception?)
    }

    companion object {

        private const val BUFFER_SIZE = 4096
        private const val TIMEOUT_DURING_HANDSHAKE_NOTIFICATION_THRESHOLD = 3
        private const val RESPONSE_TIMEOUT: Long = 6000
        const val CONNECT_TIMEOUT_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
    }
}
