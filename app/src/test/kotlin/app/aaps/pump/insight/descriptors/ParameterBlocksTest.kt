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
package app.aaps.pump.insight.descriptors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Covers [ParameterBlocks.Companion]: fromId / fromType round-trip every entry, plus the unknown-id miss. */
class ParameterBlocksTest {

    @Test
    fun fromIdAndFromType_roundTripEveryEntry() {
        ParameterBlocks.entries.forEach { e ->
            assertThat(ParameterBlocks.fromId(e.id)).isEqualTo(e)
            assertThat(ParameterBlocks.fromType(e.type)).isEqualTo(e)
        }
    }

    @Test
    fun fromId_nullForUnknown() {
        assertThat(ParameterBlocks.fromId(-99999)).isNull()
    }

    /**
     * `ReadParameterBlockMessage.parse` builds the block by reflection:
     * `ParameterBlocks.fromId(...)?.type?.getDeclaredConstructor()?.newInstance()`. A registered
     * class without a public no-arg constructor compiles and passes the round-trip test above, then
     * throws when the pump actually returns that parameter - which is the only moment it is tried.
     */
    @Test
    fun everyRegisteredBlockCanBeBuiltTheWayTheParserBuildsIt() {
        val broken = ParameterBlocks.entries.filter { e ->
            runCatching { e.type.getDeclaredConstructor().newInstance() }.isFailure
        }
        assertThat(broken.map { it.name }).isEmpty()
    }

    /**
     * A freshly built block can be asked for its data - except the basal rate profile blocks, whose
     * `profileBlocks` is `lateinit` and must be filled in first, by `parse` or `setProfileBlocks`.
     * That is a precondition of writing a profile, not a fault, so those ten are listed rather than
     * asserted about. Their parse/write round trip is covered in BRProfileBlockTest.
     */
    @Test
    fun everyBlockExceptTheProfileOnesCanBeAskedForItsDataStraightAway() {
        val needFillingIn = ParameterBlocks.entries.filter { e ->
            runCatching { e.type.getDeclaredConstructor().newInstance()!!.data }.isFailure
        }.map { it.name }

        assertThat(needFillingIn).containsExactly(
            "BRPROFILE1BLOCK", "BRPROFILE2BLOCK", "BRPROFILE3BLOCK", "BRPROFILE4BLOCK", "BRPROFILE5BLOCK",
            "BRPROFILE1NAMEBLOCK", "BRPROFILE2NAMEBLOCK", "BRPROFILE3NAMEBLOCK", "BRPROFILE4NAMEBLOCK", "BRPROFILE5NAMEBLOCK"
        )
    }

    /** Two blocks sharing an id would make one of them unreachable from a pump answer. */
    @Test
    fun theBlockIdsAreAllDifferent() {
        val ids = ParameterBlocks.entries.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
    }
}
