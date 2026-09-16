# QindaDeck companion app

Kotlin and Jetpack Compose. Finds the deck over mDNS, then drives it over the
framed TCP protocol in `docs/PROTOCOL.md`.

## Build

The SDK comes from Portage (`dev-util/android-sdk-cmdline-tools`), which installs
it at `/opt/android-sdk` owned `root:android` and group-writable, and exports
`ANDROID_HOME` through `/etc/env.d`. You need to be in the `android` group:

```sh
sudo gpasswd -a "$USER" android   # then re-login, or use: sg android -c '…'
```

Then:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` points at `/opt/android-sdk` and is not committed.

### Why these versions

The ceiling is the SDK manager, not the app. Portage's cmdline-tools (build
11076708) speaks SDK XML version 3, while `platforms;android-37` is published as
version 4 and is therefore invisible to it. So `compileSdk` stays at 36, which
pins AGP to 8.13.x, which in turn pins the androidx libraries to the last releases
that still compile against 36 — the newer ones require API 37 and AGP 9.1+.
Nothing in this app needs an API 37 symbol.

AGP also refuses the system JVM (OpenJDK 25, via `eselect java-vm`), so
`gradle.properties` points `org.gradle.java.home` at Portage's
`dev-java/openjdk-bin:21`.

## Shape

| Path | What |
| --- | --- |
| `net/Protocol.kt` | Wire constants, mirrored from the firmware |
| `net/DeckClient.kt` | One socket, one reader; replies matched by id |
| `net/Discovery.kt` | `NsdManager` mDNS for `_qindadeck._tcp` |
| `data/Models.kt` | The schema as Kotlin, with explicit JSON both ways |
| `data/DeckRepository.kt` | Single source of truth; every edit writes through |
| `ui/MacroEditor.kt` | Step list, nested repeat blocks, coordinate picking |
| `ui/DisplayCanvas.kt` | The virtual desktop, to scale, with the macro's route |
| `ui/RemapEditorScreen.kt` | Learn-mode capture for gamepad and keyboard bindings |

## Two decisions worth knowing

**The device owns the data.** The repository is a cache, not a store: edits are
written through and the reply decides whether the local copy updates. The firmware
validates every profile and macro, so a bad chord surfaces as an error on save
rather than as a button that silently does nothing later.

**No Bluetooth permission.** Pairing a controller uses the *deck's* radio. The
phone is a remote control for it, so the app never touches Bluetooth, and a paired
controller keeps working when the phone is elsewhere.

## Not part of this app

Wi-Fi provisioning for a brand-new deck. A deck with no credentials raises its own
access point and serves a setup page at `http://192.168.4.1/`; a phone joins that
directly. Once it is on the network the app takes over, and can change networks
later from Settings.
