/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.backend.BackendEvent
import app.trio.insightbridge.protocol.Wire
import kotlinx.serialization.json.JsonObject

/** Backend events -> wire events (PROTOCOL.md section 5.5). */
object EventMapper {

    /** Returns the wire event and an optional throttle key (bolusProgress: at most one per 2 s). */
    fun toWire(event: BackendEvent): Pair<JsonObject, String?> = when (event) {
        is BackendEvent.StatusChanged -> Wire.statusEvent(event.status) to null
        is BackendEvent.AlertChanged -> Wire.alertEvent(event.alert) to null
        is BackendEvent.BolusProgress -> Wire.bolusProgressEvent(event.progress) to "bolusProgress"
        is BackendEvent.LinkChanged -> Wire.pumpLinkEvent(event.state) to null
    }
}
