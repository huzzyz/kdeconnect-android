# KDE Connect for Android with Shizuku clipboard sync

This fork adds automatic Android-to-desktop clipboard synchronization to KDE
Connect on current Android versions. Android normally blocks apps from reading
the clipboard while they run in the background. This build uses an authorized
[Shizuku](https://shizuku.rikka.app/) service to detect clipboard changes and
passes them through KDE Connect's existing encrypted device connection.

The project tracks the upstream
[KDE Connect Android application](https://invent.kde.org/network/kdeconnect-android).
The `shizuku-clipboard` branch carries the changes needed for Shizuku clipboard
support and rebases onto new upstream releases.

## Features

- Automatic clipboard synchronization from Android to a paired computer
- Standard KDE Connect clipboard synchronization from a computer to Android
- Automatic recovery when Shizuku stops, restarts, or replaces its Binder
- Cleanup of clipboard monitors left behind by an interrupted Shizuku session
- All upstream KDE Connect features, including notifications, file sharing,
  media control, remote input, and device status

## Reliable recovery

Older builds checked the Shizuku connection once when KDE Connect started. If
Shizuku restarted later, KDE Connect could retain a stale connection and stop
sending clipboard changes until you revoked and granted permission again.

This fork listens for Shizuku Binder lifecycle events. When Shizuku disconnects,
KDE Connect closes its stale clipboard monitor. When Shizuku returns, KDE
Connect checks authorization and starts one replacement monitor. It also removes
orphaned monitors left by an Android process restart.

Recovery uses event callbacks. It does not run a periodic health check, acquire
an additional wake lock, or add another foreground service. If a replacement
monitor exits during startup, KDE Connect retries after 1, 2, 4, 8, 16, and 30
seconds, then waits for the next Shizuku connection event. A received clipboard
event resets that retry budget.

## Requirements

- Android 6 or newer
- Shizuku or a compatible Shizuku manager
- A running Shizuku service started through wireless debugging or root
- KDE Connect installed on the computer
- Network connectivity between both devices

Shizuku started through wireless debugging normally needs to start again after
the phone reboots. Compatible Shizuku forks may provide their own watchdog or
restart mechanism.

## Installation

1. Install Shizuku and start its service.
2. Download the APK from this repository's
   [Releases](https://github.com/huzzyz/kdeconnect-android/releases) page.
3. Install the APK and open **KDE Connect (Shizuku)**.
4. Grant KDE Connect access from Shizuku's authorized applications list.
5. Pair the phone with KDE Connect on your computer.
6. Enable **Clipboard sync** for the paired device.

The fork uses the package name `org.kde.kdeconnect_tp.shizuku`, so it does not
replace the official KDE Connect Android application. Android preserves pairing
and settings when you update this fork with an APK signed by the same release
key.

## Building

Open the repository in Android Studio or build it with Gradle:

```shell
./gradlew testDebugUnitTest assembleDebug
```

Release builds require the signing configuration used by the release workflow.
The workflow verifies the package name and signing certificate before it
publishes an APK.

## Upstream and licensing

Report problems specific to Shizuku clipboard handling in this repository. For
general KDE Connect bugs and upstream development, use the
[KDE bug tracker](https://bugs.kde.org/) and
[KDE Connect project](https://invent.kde.org/network/kdeconnect-android).

KDE Connect is licensed under the GNU General Public License. See
[COPYING](COPYING) and the files under [LICENSES](LICENSES) for details.
