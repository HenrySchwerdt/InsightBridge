/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

/** PROTOCOL.md section 5.6. [wire] is the exact string sent as error.code. */
enum class ErrorCode(val wire: String) {
    NOT_PAIRED("notPaired"),
    PUMP_UNREACHABLE("pumpUnreachable"),
    PUMP_SUSPENDED("pumpSuspended"),
    PUMP_BUSY("pumpBusy"),
    INVALID_ARGS("invalidArgs"),
    LIMIT_EXCEEDED("limitExceeded"),
    VERIFY_FAILED("verifyFailed"),
    ALERT_ACTIVE("alertActive"),
    BUSY("busy"),
    TOO_LARGE("tooLarge"),
    UNSUPPORTED_PROTOCOL("unsupportedProtocol"),
    INTERNAL("internal");

    companion object {

        fun fromWire(s: String): ErrorCode? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * A failed operation. [uncertain] = the bridge cannot tell whether the pump executed the command;
 * the caller must never retry a dosing command automatically in that case.
 */
class PumpOpException(
    val code: ErrorCode,
    message: String,
    val uncertain: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause) {

    init {
        // Uncertain only ever applies to commands that may have reached the pump.
        require(!uncertain || code == ErrorCode.PUMP_UNREACHABLE || code == ErrorCode.INTERNAL) {
            "uncertain outcomes must be pumpUnreachable or internal"
        }
    }
}
