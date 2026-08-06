# Automatic clipboard sync via Shizuku

A minimal fork of [KDE Connect for Android](https://invent.kde.org/network/kdeconnect-android)
that makes **Android → desktop clipboard sync work automatically**, with no
manual "Send clipboard" tap, on devices where the stock `READ_LOGS` method fails.

This branch carries **one commit, three files, +103/−14** on top of an upstream
release tag. Everything else is unmodified KDE Connect.

## Credit

This is other people's work, lightly rearranged:

- **[KDE Connect](https://invent.kde.org/network/kdeconnect-android)** — the
  entire application, including the clipboard plugin, `ClipboardFloatingActivity`,
  and the original logcat-watching approach this builds on. Licensed
  GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL, and this fork
  inherits that license.
- **[libdu/kde-connect-shizuku](https://github.com/libdu/kde-connect-shizuku)** —
  originated the Shizuku approach. Our patch is cherry-picked from their commit
  `9239e3a6`, reduced to the functional change (see *Differences* below).
- The **[KDE Discuss thread](https://discuss.kde.org/t/kde-connect-clipboard-sync/6422)**
  where the `READ_LOGS` workaround and its limits were worked out by
  Harrisc, Urban, wqwwrea1, IllegalOperation, CrossyAtom46 and others.

If this is useful to you, the credit belongs upstream.

## The problem

Since Android 10, apps cannot read the clipboard in the background. KDE Connect
already works around this: it runs `logcat`, watches for the `ClipboardService`
denial naming its own package, and raises a transparent activity to read the
clipboard with legitimate foreground access. That path is gated behind
`READ_LOGS`, granted over adb:

```
adb shell pm grant org.kde.kdeconnect_tp android.permission.READ_LOGS
adb shell appops set org.kde.kdeconnect_tp SYSTEM_ALERT_WINDOW allow
adb shell am force-stop org.kde.kdeconnect_tp
```

**On some OEM builds this silently does nothing.** Verified failing on
Android 16 / One UI 8.5 (Samsung SM-S948B) with every input correct:

- `READ_LOGS` `granted=true`
- `SYSTEM_ALERT_WINDOW` `allow`
- the in-app log-access consent approved
- the `logcat` child process running
- both filter forms (`ClipboardService:E` and `E ClipboardService`) valid
- the `-T` timestamp format accepted
- the denial line demonstrably emitted, naming the package

The listener looks perfectly healthy and never fires.

## The cause

Android gates **system-wide** log access for *apps*, separately from the
`READ_LOGS` permission. The discriminator is easy to observe:

```
adb shell logcat ClipboardService:E   →  returns the denials
the app's own logcat (READ_LOGS)      →  returns nothing
```

The shell user has unrestricted log access. The app does not.

## The fix

One line:

```kotlin
// upstream
val process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", ts, filter, "*:S"))

// here
val process = Shizuku.newProcess(arrayOf("logcat", "-T", ts, filter, "*:S"), null, null)
```

Same command, executed as the shell user via [Shizuku](https://shizuku.rikka.app/).

Order of preference, so nothing regresses:

1. Shizuku, if available and authorized
2. request Shizuku permission, if the binder is alive
3. otherwise fall back to the existing `READ_LOGS` path

## Requirements

- [Shizuku](https://shizuku.rikka.app/) running, with permission granted to this app
- Clipboard plugin enabled for the paired device

No root required.

## Naming, and why it is renamed

This installs as `org.kde.kdeconnect_tp.shizuku`, labelled **"KDE Connect
(Shizuku)"** — not under upstream's package name.

The GPL permits redistributing modified builds. The **KDE name is a separate
matter**: "KDE" and "KDE Connect" are trademarks of KDE e.V., and shipping a
third-party build under upstream's package name and label invites users to
mistake it for official KDE Connect. Renaming costs the drop-in property and is
worth it. This follows libdu's lead.

For the same reason, builds here are signed with the maintainer's own key — not
a certificate bearing KDE's name.

Because the `applicationId` differs from upstream, this **installs alongside**
an official KDE Connect rather than replacing it. It needs its own pairing.

## Differences from libdu/kde-connect-shizuku

Their commit bundles the functional change with build-environment preferences.
This fork takes only the former, so the patch stays small enough to rebase per
release:

| | libdu | here |
|---|---|---|
| Shizuku clipboard listener | yes | **yes** (cherry-picked) |
| `applicationId` | `…​.shizuku` | `…​.shizuku` (same reasoning) |
| `versionName` | pinned `1.35.9-shizuku` | upstream's, unpinned |
| `.debug` suffix | removed | kept, so debug and release coexist |
| gradle daemon config | changed | untouched |
| app label | edited `strings.xml` | separate resource file |

That last row matters more than it looks: upstream edits `strings.xml` on almost
every release via translation commits, so overriding the label there would
conflict constantly. A separate file never does.

Note that `…​.shizuku` collides with libdu's build. The signing keys differ, so
the two cannot update over one another — uninstall one before installing the
other.

## Building

Standard KDE Connect build; nothing special is required.

```
./gradlew assembleRelease
```

## Maintenance

The patch is deliberately confined to files upstream rarely touches, and this
documentation lives in its own file rather than in upstream's `README.md`, so a
rebase per release should be uneventful. If upstream ever adopts a Shizuku path
themselves, this fork should be deleted rather than maintained.
