package app.trio.insightbridge.ble

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AdvertisingPolicyTest {

    private fun healthy(now: Long = 100_000L) = AdvSnapshot(
        now = now, adapterReady = true, serverOpen = true, serviceAdded = true, serverAgeMs = 60_000,
        serviceStillRegistered = null, advertising = true, advertisedMode = AdvMode.LOW_LATENCY, startPending = false,
        desiredMode = AdvMode.LOW_LATENCY, consecutiveFailures = 0, lastAttemptAt = 0, refreshDue = false
    )

    @Test fun healthyStateNeedsNoAction() {
        assertThat(AdvertisingPolicy.decide(healthy())).isEqualTo(AdvAction.None)
    }

    @Test fun backoffDoublesAndIsCappedAtFiveSeconds() {
        assertThat((0..8).map { AdvertisingPolicy.backoffMs(it) })
            .containsExactly(0L, 250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L).inOrder()
        assertThat(AdvertisingPolicy.backoffMs(500)).isEqualTo(5_000L)
    }

    @Test fun advertisingThatIsOffIsStartedImmediatelyAfterADisconnect() {
        val s = healthy().copy(advertising = false, advertisedMode = null)
        assertThat(AdvertisingPolicy.decide(s)).isEqualTo(AdvAction.StartAdvertising(AdvMode.LOW_LATENCY))
    }

    @Test fun failedStartsAreRetriedWithBackoff() {
        val failed = healthy(now = 10_000).copy(advertising = false, advertisedMode = null, consecutiveFailures = 3, lastAttemptAt = 9_500)
        assertThat(AdvertisingPolicy.decide(failed)).isEqualTo(AdvAction.None) // 500 ms < 1000 ms backoff
        assertThat(AdvertisingPolicy.decide(failed.copy(now = 10_600))).isEqualTo(AdvAction.StartAdvertising(AdvMode.LOW_LATENCY))
        val maxed = failed.copy(consecutiveFailures = 50, lastAttemptAt = 0, now = 4_999)
        assertThat(AdvertisingPolicy.decide(maxed)).isEqualTo(AdvAction.None)
        assertThat(AdvertisingPolicy.decide(maxed.copy(now = 5_000))).isEqualTo(AdvAction.StartAdvertising(AdvMode.LOW_LATENCY)) // never waits longer than 5 s
    }

    @Test fun aStartWithoutCallbackIsGivenTimeThenRestarted() {
        val pending = healthy(now = 10_000).copy(advertising = false, startPending = true, lastAttemptAt = 8_000)
        assertThat(AdvertisingPolicy.decide(pending)).isEqualTo(AdvAction.None)
        assertThat(AdvertisingPolicy.decide(pending.copy(now = 12_500)))
            .isInstanceOf(AdvAction.RestartAdvertising::class.java)
    }

    @Test fun modeFollowsTheSession() {
        assertThat(AdvertisingPolicy.desiredMode(sessionActive = false)).isEqualTo(AdvMode.LOW_LATENCY)
        assertThat(AdvertisingPolicy.desiredMode(sessionActive = true)).isEqualTo(AdvMode.BALANCED)
        val connected = healthy().copy(desiredMode = AdvMode.BALANCED) // session came up while advertising low-latency
        assertThat(AdvertisingPolicy.decide(connected)).isInstanceOf(AdvAction.RestartAdvertising::class.java)
        assertThat((AdvertisingPolicy.decide(connected) as AdvAction.RestartAdvertising).mode).isEqualTo(AdvMode.BALANCED)
        // and the other way round after the session ended
        val ended = healthy().copy(advertisedMode = AdvMode.BALANCED, desiredMode = AdvMode.LOW_LATENCY)
        assertThat((AdvertisingPolicy.decide(ended) as AdvAction.RestartAdvertising).mode).isEqualTo(AdvMode.LOW_LATENCY)
    }

    @Test fun advertisingIsKeptWhileConnectedAndRefreshedWhenDue() {
        // being connected is not a reason to stop advertising
        assertThat(AdvertisingPolicy.decide(healthy().copy(desiredMode = AdvMode.BALANCED, advertisedMode = AdvMode.BALANCED))).isEqualTo(AdvAction.None)
        val due = healthy().copy(refreshDue = true)
        assertThat(AdvertisingPolicy.decide(due)).isEqualTo(AdvAction.RestartAdvertising(AdvMode.LOW_LATENCY, "periodic refresh"))
    }

    @Test fun periodicRefreshIsDueWhenACentralIsConnectedOrTheLastStartIsOld() {
        assertThat(AdvertisingPolicy.refreshDue(connectedCount = 1, now = 1_000, lastStartAt = 999)).isTrue()
        assertThat(AdvertisingPolicy.refreshDue(connectedCount = 0, now = 5 * 60_000, lastStartAt = 0)).isFalse()
        assertThat(AdvertisingPolicy.refreshDue(connectedCount = 0, now = 11 * 60_000, lastStartAt = 0)).isTrue()
    }

    @Test fun missingServerOrServiceRecreatesTheServer() {
        val noServer = healthy().copy(serverOpen = false, serviceAdded = false, advertising = false)
        assertThat(AdvertisingPolicy.decide(noServer)).isInstanceOf(AdvAction.RecreateServer::class.java)
        val gone = healthy().copy(serviceStillRegistered = false)
        assertThat((AdvertisingPolicy.decide(gone) as AdvAction.RecreateServer).reason).contains("disappeared")
        assertThat(AdvertisingPolicy.decide(healthy().copy(serviceStillRegistered = true))).isEqualTo(AdvAction.None)
    }

    @Test fun serverRecreationAlsoBacksOff() {
        val s = healthy(now = 10_000).copy(serverOpen = false, serviceAdded = false, advertising = false, consecutiveFailures = 4, lastAttemptAt = 9_000)
        assertThat(AdvertisingPolicy.decide(s)).isEqualTo(AdvAction.None) // 1 s < 2 s
        assertThat(AdvertisingPolicy.decide(s.copy(now = 11_100))).isInstanceOf(AdvAction.RecreateServer::class.java)
    }

    @Test fun aServiceThatIsNeverAddedIsRecreatedAfterTheGracePeriod() {
        val adding = healthy().copy(serviceAdded = false, advertising = false, serverAgeMs = 1_000)
        assertThat(AdvertisingPolicy.decide(adding)).isEqualTo(AdvAction.None)
        assertThat(AdvertisingPolicy.decide(adding.copy(serverAgeMs = 6_000))).isInstanceOf(AdvAction.RecreateServer::class.java)
    }

    @Test fun bluetoothOffOrMissingPermissionReleasesEverythingOnlyOnce() {
        assertThat(AdvertisingPolicy.decide(healthy().copy(adapterReady = false))).isEqualTo(AdvAction.StopAll)
        assertThat(AdvertisingPolicy.decide(healthy().copy(adapterReady = false, serverOpen = false, advertising = false))).isEqualTo(AdvAction.None)
    }

    @Test fun gattStatusCodesAreNamedForTheLog() {
        assertThat(GattStatus.describe(8)).contains("timeout")
        assertThat(GattStatus.describe(19)).contains("remote")
        assertThat(GattStatus.describe(133)).contains("133")
        assertThat(GattStatus.describe(999)).isEqualTo("status 999")
    }
}
