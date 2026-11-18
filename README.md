# InsightBridge

Android companion app for Trio's **InsightKit** (the iOS pump manager for the Accu-Chek Insight).

The Insight speaks the proprietary *Sight* protocol over Bluetooth Classic RFCOMM, which iOS apps
cannot open. InsightBridge runs on an Android phone, holds the link to the pump and exposes a small,
authenticated, high-level command API to the iPhone over Bluetooth Low Energy (GATT).

```
 Trio (iPhone)  --BLE GATT, AES-256-GCM-->  InsightBridge (Android)  --RFCOMM / Sight-->  Insight pump
   InsightKit                                 pairing, history, alerts, idempotent commands
```

The contract between both apps is [PROTOCOL.md](PROTOCOL.md) (v2). Fixed cryptographic test vectors
for both implementations are in [test-vectors.json](test-vectors.json).

> **Safety-critical software.** This app can cause insulin to be delivered. It has only been tested
> against a simulator and unit tests, **never against a real pump or on real hardware by its
> authors**. Use at your own risk and verify every step yourself.

## Origin and license (AGPL-3.0)

The pump protocol layers (SATL transport and crypto with Twofish/RSA key exchange, pairing, app
layer messages, descriptors, history events, parameter blocks, the connection state machine and
the mapping of operations) are ported from the Insight driver of
[AndroidAPS](https://github.com/nightscout/AndroidAPS) (`pump/insight`), which is licensed under
AGPL-3.0. The ported code lives in `app/src/main/kotlin/app/aaps/pump/insight` and keeps the
original package name and copyright notice. AAPS infrastructure (dagger/metro, database, rx bus,
preferences, plugin framework, Compose screens) was removed and replaced by plain Kotlin and
coroutines. This whole project is therefore distributed under the **GNU Affero General Public
License v3** (see [LICENSE](LICENSE)). Source must be offered to anyone who uses it over a network
or receives a copy.

## What it does

- Pairs with the Insight pump (Sight pairing, verification code shown on the pump).
- Pairs with the iPhone using ECDH P-256 with a commitment, plus a 6-digit code that the user
  confirms on **both** devices.
- Encrypted BLE API: fragmentation, AES-256-GCM envelopes with counters, session handshake.
- Operations: status, bolus, cancel bolus, temporary basal, operating mode, basal profile
  (written to profile 1, activated and read back), time sync, history, alerts, request outcome lookup.
- Never initiates dosing by itself. Rejects out-of-range values instead of clamping them. Every
  state-changing command carries a `requestId` that is recorded as in-progress *before* anything
  is sent to the pump, so a retry can never dose twice; uncertain outcomes are reported as such.
- Runs 24/7 as a foreground service (see below). Setup wizard with illustrated pump steps, English and German.
- **Developer simulator** (hidden: tap the version label 7 times): a simulated pump so the whole
  chain Trio - BLE - bridge can be tested without a pump and without insulin. It is never
  enabled automatically, is mutually exclusive with a paired real pump and shows a red banner
  "SIMULATED PUMP - NO INSULIN IS DELIVERED" on every screen and in the notification.

## Requirements

- Android 8.0 (API 26) or newer, a device with Bluetooth LE peripheral (advertising) support.
- Permissions: Bluetooth connect/scan/advertise (API 31+; legacy Bluetooth + location below),
  notifications, battery-optimization exemption (strongly recommended, the wizard asks).

## Build and test

JDK 17+ and the Android SDK (platform 36) are required. Android Studio's bundled JDK works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17+
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests (protocol, pipeline with simulator, soak, ported AAPS tests)
```

Regenerate the test vectors (only when the protocol deliberately changes):

```sh
GEN_VECTORS=1 ./gradlew testDebugUnitTest --tests '*TestVectorsTest*'
```

## Layout

| Path | Content |
|---|---|
| `PROTOCOL.md` | the iOS <-> bridge contract |
| `app/src/main/kotlin/app/trio/insightbridge/protocol` | framing, envelope crypto, KDF/SAS/commitment, JSON wire model |
| `.../engine` | transport-independent engine: handshake, session, validation, idempotency, dispatcher |
| `.../backend` | `PumpBackend` interface, `SimulatedPump`, `sight/` real backend over the ported Sight stack |
| `.../ble` | GATT server and advertising (self healing) |
| `.../service` | foreground service, boot/restart receivers, watchdog, notifications, pump pairing flow |
| `.../store` | Keystore-wrapped encrypted storage, settings |
| `.../ui` | Compose UI: wizard, main screen, alerts, developer screen |
| `app/src/main/kotlin/app/aaps/pump/insight` | ported AndroidAPS Insight driver (AGPL-3.0) |

## Reliability design (24/7 operation)

- Foreground service (`connectedDevice`), `START_STICKY`; restarted on boot, locked boot, app
  update, after a crash (alarm scheduled by the crash handler before the process dies) and by a
  15 minute WorkManager watchdog that also detects a stalled service heartbeat.
- Every coroutine scope has a supervisor and an exception handler; every Bluetooth callback and
  I/O thread boundary catches and logs. A global uncaught-exception handler persists the crash.
- Self-healing links: pump reconnect with capped exponential backoff that never gives up, GATT
  server recreated when the Bluetooth adapter toggles, advertising restarted after failure,
  response timeouts for stalled pump links, sockets closed on every failure.
- Bounded memory: ring-buffer log, bounded queues and caches, timed partial wake locks only.
- Health visibility on the main screen and in the persistent notification; warnings for missing
  battery exemption, disabled notifications, Bluetooth off; a notification if the pump was not
  reachable for more than 15 minutes or after a restart following a crash.
- Pump link policy: by default the link is opened on demand and for a periodic refresh (saves pump
  battery, like AndroidAPS); the main screen has a switch to keep the pump connected all the time.

## Security notes

- Pump pairing keys and the iPhone long-term key are stored in files wrapped by a non-exportable
  Android Keystore AES key (device protected storage so the service can start before first unlock).
  They are excluded from backups and never logged.
- Logs contain events and error codes only, never keys, nonces or payloads.
- The BLE service accepts one active phone at a time; everything but the pairing/session handshake
  must be authenticated with the session key.

## Not verified

No test against a real Insight pump or a real iPhone. See the final notes in the project history
for a list of assumptions (menu wording on the pump, vendor background-kill settings, advertising
name behaviour on specific devices).

## Fonts and screenshots

The UI uses the Inter typeface (SIL Open Font License 1.1, see [docs/Inter-OFL-LICENSE.txt](docs/Inter-OFL-LICENSE.txt)).
Screenshots of every screen (light, dark and German samples, taken on an emulator with the
simulated pump) are in [docs/screenshots](docs/screenshots).
