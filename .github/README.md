# KDE Connect (Shizuku)

My fork of [KDE Connect for Android](https://invent.kde.org/network/kdeconnect-android),
patched so that **copying on my phone syncs to my desktop automatically** — no
"Send clipboard" tap — on devices where the stock method doesn't work.

It carries **one small patch** on top of an upstream release tag. Everything else
is unmodified KDE Connect, and CI rebases the patch onto each new upstream
release automatically.

## Credit

Almost none of this is my work:

- **[KDE Connect](https://invent.kde.org/network/kdeconnect-android)** — the
  entire application, including the clipboard plugin, `ClipboardFloatingActivity`
  and the original logcat-watching approach I build on. Licensed
  GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL, which this fork
  inherits.
- **[libdu/kde-connect-shizuku](https://github.com/libdu/kde-connect-shizuku)** —
  worked out the Shizuku approach. My patch is cherry-picked from their commit
  `9239e3a6`, reduced to just the functional change.
- The **[KDE Discuss thread](https://discuss.kde.org/t/kde-connect-clipboard-sync/6422)**,
  where Harrisc, Urban, wqwwrea1, IllegalOperation, CrossyAtom46 and others
  worked out the `READ_LOGS` workaround and where it stops working.

If this is useful to you, the credit belongs to them. I just packaged it and
automated keeping it current.

## Why I made this

Since Android 10, apps can't read the clipboard in the background. KDE Connect
already works around this: it runs `logcat`, watches for the `ClipboardService`
denial naming its own package, and raises a transparent activity to read the
clipboard with legitimate foreground access. That path is gated behind
`READ_LOGS`, which you grant over adb:

```
adb shell pm grant org.kde.kdeconnect_tp android.permission.READ_LOGS
adb shell appops set org.kde.kdeconnect_tp SYSTEM_ALERT_WINDOW allow
adb shell am force-stop org.kde.kdeconnect_tp
```

**On my phone this did nothing at all.** I checked every input on Android 16 /
One UI 8.5 (Samsung SM-S948B):

- `READ_LOGS` — `granted=true`
- `SYSTEM_ALERT_WINDOW` — `allow`
- the in-app log-access consent — approved
- the `logcat` child process — running
- both filter forms (`ClipboardService:E` and `E ClipboardService`) — valid
- the `-T` timestamp format — accepted
- the denial line — emitted, naming the package

The listener looked completely healthy and never fired once.

## What's actually wrong

Android gates **system-wide** log access for *apps*, separately from the
`READ_LOGS` permission. You can see it directly:

```
adb shell logcat ClipboardService:E   →  returns the denials
the app's own logcat (READ_LOGS)      →  returns nothing
```

The shell user has unrestricted log access. The app doesn't.

## The fix

One line:

```kotlin
// upstream
val process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", ts, filter, "*:S"))

// here
val process = Shizuku.newProcess(arrayOf("logcat", "-T", ts, filter, "*:S"), null, null)
```

Same command, run as the shell user via [Shizuku](https://shizuku.rikka.app/).

It falls back gracefully, so nothing regresses if you don't need it:

1. Shizuku, if available and authorized
2. request Shizuku permission, if the binder is alive
3. otherwise the existing `READ_LOGS` path

## Requirements

- [Shizuku](https://shizuku.rikka.app/) running, with permission granted to this app
- Clipboard plugin enabled for the paired device

No root needed.

## Installing

Grab the APK from [Releases](../../releases). I'd suggest
[Obtainium](https://github.com/ImranR98/Obtainium) pointed at this repo so
updates arrive by themselves.

This installs as `org.kde.kdeconnect_tp.shizuku`, **alongside** official KDE
Connect rather than replacing it, so it needs its own pairing.

I renamed it deliberately. The GPL lets me redistribute a modified build, but
"KDE" and "KDE Connect" are trademarks of KDE e.V., and shipping this under
upstream's package name and label would invite people to mistake it for official
KDE Connect. For the same reason I sign with my own key rather than a
certificate carrying KDE's name.

## How I keep it current

CI polls upstream daily, rebases the patch onto any new release tag, builds,
signs and publishes. It's built to fail loudly rather than quietly:

- if the rebase conflicts, it **opens an issue and publishes nothing**, instead
  of leaving the last release looking current
- it asserts the patch **survived** the rebase (`Shizuku.newProcess`, the
  Shizuku dependency, my `applicationId`). A rebase can succeed mechanically
  while dropping the hunk this fork exists for, and a green build that silently
  lost the feature is worse than a red one
- it verifies the built APK carries my `applicationId`, so a misconfiguration
  can't ship something that installs over official KDE Connect
- it commits a heartbeat if the repo goes 45 days quiet, because GitHub disables
  scheduled workflows after 60 days of inactivity — which would otherwise switch
  off the automation whose whole job is noticing upstream releases

To keep rebases clean, everything of mine lives in files upstream doesn't touch:
this README is at `.github/README.md` (not the root one), the app label is in its
own resource file (not `strings.xml`, which upstream rewrites most releases), and
the current base tag is recorded in `.upstream-base`.

The patch itself is three files: `ClipboardListener.kt`, `AndroidManifest.xml`,
`build.gradle.kts`.

## Differences from libdu/kde-connect-shizuku

Their commit bundles the functional change with some build-environment
preferences. I took only the former, to keep the patch small enough to rebase
comfortably:

| | libdu | here |
|---|---|---|
| Shizuku clipboard listener | yes | **yes** (cherry-picked) |
| `applicationId` | `…​.shizuku` | `…​.shizuku` (same reasoning) |
| `versionName` | pinned `1.35.9-shizuku` | upstream's, unpinned |
| `.debug` suffix | removed | kept, so debug and release coexist |
| gradle daemon config | changed | untouched |
| app label | edited `strings.xml` | separate resource file |

Note this collides with their build — same `applicationId`, different signing
key — so uninstall one before installing the other.

## If upstream adopts this

Delete this fork. It exists only because upstream can't ship an accessibility-
or log-based clipboard reader without risking their Play Store listing, which is
a constraint on them and not a technical limitation. If that ever changes, there
is no reason for this to exist.
