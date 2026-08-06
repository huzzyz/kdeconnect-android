# KDE Connect (Shizuku)

KDE Connect for Android, patched so copying on my phone actually shows up on my
desktop without tapping "Send clipboard" first.

It's one small patch on top of an upstream release. CI rebases it onto each new
KDE Connect release and publishes a signed APK.

## Credit

Almost none of this is mine.

[KDE Connect](https://invent.kde.org/network/kdeconnect-android) is the whole
app, including the clipboard plugin and the logcat trick this depends on. GPL,
which this inherits.

[libdu/kde-connect-shizuku](https://github.com/libdu/kde-connect-shizuku)
figured out the Shizuku approach. My patch is their commit `9239e3a6` with the
build-environment bits stripped out.

The [KDE Discuss thread](https://discuss.kde.org/t/kde-connect-clipboard-sync/6422)
is where the `READ_LOGS` workaround got worked out, and where people started
reporting it breaking on newer Android.

I packaged it and set up the CI. That's it.

## The problem

Android 10 killed background clipboard reads. KDE Connect works around it by
running `logcat`, watching for the `ClipboardService` denial with its own
package name in it, then popping a transparent activity to read the clipboard
while technically in the foreground. You enable it with adb:

```
adb shell pm grant org.kde.kdeconnect_tp android.permission.READ_LOGS
adb shell appops set org.kde.kdeconnect_tp SYSTEM_ALERT_WINDOW allow
adb shell am force-stop org.kde.kdeconnect_tp
```

On my phone (Android 16, One UI 8.5) this did nothing at all, and gave no
indication why. I went through everything: permission granted, overlay allowed,
the in-app consent prompt accepted, the `logcat` process running, both filter
forms valid, the `-T` timestamp accepted, and the denial line definitely being
written with the package name in it. All fine. Never fired once.

## Why

Android restricts system-wide log access for apps separately from the
`READ_LOGS` permission. Easiest way to see it:

```
adb shell logcat ClipboardService:E   →  shows the denials
the app's own logcat, with READ_LOGS  →  shows nothing
```

Shell can read the logs. The app can't. So KDE Connect is waiting for a line
it's no longer allowed to see.

## The fix

Run the same command as shell, via [Shizuku](https://shizuku.rikka.app/):

```kotlin
// upstream
Runtime.getRuntime().exec(arrayOf("logcat", "-T", ts, filter, "*:S"))

// here
Shizuku.newProcess(arrayOf("logcat", "-T", ts, filter, "*:S"), null, null)
```

It tries Shizuku first, asks for permission if Shizuku's running but not
authorised, and otherwise falls back to the old `READ_LOGS` path so nothing
breaks for people it already works for.

## Setup

You need [Shizuku](https://shizuku.rikka.app/) running with permission granted
to this app, and the clipboard plugin enabled for your paired device. No root.

Grab the APK from [Releases](../../releases), or point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repo so updates come
through on their own.

It installs as `org.kde.kdeconnect_tp.shizuku`, so it sits alongside official
KDE Connect rather than replacing it, and needs its own pairing.

I renamed it on purpose. The GPL lets me distribute a modified build, but "KDE"
and "KDE Connect" are KDE e.V. trademarks and shipping this under their package
name would be asking people to confuse it for the real thing. Same reason I sign
with my own key instead of a cert with KDE's name on it.

## Staying current

CI checks upstream daily, rebases the patch onto any new tag, builds, signs and
publishes.

It's built to break loudly rather than quietly, because everything about this
problem failed silently the first time round:

- rebase conflicts open an issue and publish nothing, so a stale release never
  sits there looking current
- it checks the patch actually survived the rebase. A rebase can apply cleanly
  and still drop the hunk the fork exists for, and a green build missing the
  feature is worse than a red one
- it checks the built APK's applicationId and signing key. Wrong key means it
  can't install as an update, which strands anyone already running it
- it commits a heartbeat if the repo goes 45 days quiet, since GitHub switches
  off scheduled workflows after 60 days of nothing, which would quietly disable
  the thing whose job is watching for upstream releases

Anything of mine that could collide with upstream lives somewhere upstream
doesn't touch: this README is `.github/README.md` rather than the root one, the
app label is its own resource file instead of `strings.xml` (which upstream
rewrites constantly), and the base tag sits in `.upstream-base`.

The actual patch is three files: `ClipboardListener.kt`, `AndroidManifest.xml`,
`build.gradle.kts`.

## vs libdu's build

Their commit mixes the fix with some build-environment changes. I took the fix
and left the rest, mostly to keep rebases painless:

| | libdu | here |
|---|---|---|
| Shizuku clipboard listener | yes | yes |
| Build type | debug | release |
| `android:debuggable` | **true** | false |
| APK size | 26.0 MB | 6.8 MB |
| dex | 54.4 MB | 7.9 MB |
| `applicationId` | `….shizuku` | same |
| `versionName` | pinned to `1.35.9` | upstream's |
| `.debug` suffix | removed | kept |
| app label | edited `strings.xml` | separate file |

The build type is the one that actually matters. Their release APK has
`android:debuggable=true`, which is why it's four times the size and the dex
seven times bigger, unminified. More to the point, a debuggable app can have a
debugger attached by any process and its data directory pulled out with
`run-as`.

That's worth avoiding on KDE Connect specifically, because it isn't just
clipboard. It mirrors your notifications, so 2FA codes, message previews and
email subjects all pass through it. It handles SMS, file transfers, remote
input and media control, and its data directory holds the device pairing keys.
That's a lot to leave debuggable.

Removing the `.debug` suffix from the debug block, as they do, is what lets a
debug build install under the normal package name.

Ours is a plain upstream release build: minified, shrunk, not debuggable.

Same package and different signing keys, so uninstall one before installing
the other.

## If upstream ships this

Delete the fork. It only exists because KDE Connect is on the Play Store and
can't ship a log- or accessibility-based clipboard reader without putting the
listing at risk. That's a distribution problem, not a technical one.
