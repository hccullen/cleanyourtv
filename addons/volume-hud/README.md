# Volume pill

A slim glass capsule at the top right of the TV with the volume level as a thin bar and a percent,
shown for a moment whenever the volume changes, over every app. For TVs whose sound runs through an
amplifier or soundbar over HDMI ARC: Android draws no volume bar of its own while the amplifier
holds the level, so pressing Volume Up/Down answers in silence. The pill is that answer.

## How it works

A foreground service holds the listener. When the system's volume-changed broadcast arrives — on
every change no matter where it came from: the remote, an app, the amplifier's own volume reported
back over HDMI-CEC — the pill reads the level from `AudioManager` (on ARC firmware that level
follows the amplifier's CEC-reported volume), draws, and hides 1.6 s after the last change,
re-reading while the level settles (the amplifier answers slightly late).

The listener is registered at runtime inside the service because Android does not deliver implicit
broadcasts to manifest-declared receivers of apps targeting API 26 or later, and
`android.media.VOLUME_CHANGED_ACTION` is not on the exemption list. The service starts at boot,
when the app is updated, and whenever the card is opened — so a fresh install shows the pill from
the first visit to the card (or the first reboot) onwards. It is foreground with the lowest
notification importance; Android shows it in the launcher's running-apps state, like Quiet Hours.

The price of arriving with the level: the pill appears when the volume has *landed*, a moment after
the key press, not on the press itself — and shows the true level, including when a held key is
coalesced by an idle amplifier into one notch. A muted TV shows `MUTED` with an empty bar.

The pill sits in the letterbox band at the top right (about one sixth of the screen wide), drawn as
a `TYPE_APPLICATION_OVERLAY` window that is not focusable and not touchable. Nothing in the pill
may throw: every entry point catches and logs instead, so a drawing failure can never disturb
whatever is playing.

## The card

The card's screen says whether the pill is on, offers one switch, and shows the state of the
"display over other apps" permission. Off stops the service; on starts it.

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draws the pill over every app while the volume changes. Nothing else on screen, ever. |
| `FOREGROUND_SERVICE` | Keeps the volume-changed listener alive while the pill is on. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declares that listener's type: it shows a pill, it controls nothing. |
| `RECEIVE_BOOT_COMPLETED` | Starts the listener again after a reboot or an update of this app. |

No `INTERNET`: nothing leaves the TV. No accounts, no telemetry, no ads, no accessibility service.
The volume is only read, never set.

## Granting the permission

The pill is invisible until the TV allows drawing over other apps. On the TV: Settings ▸ Apps ▸
Volume pill ▸ Display over other apps. Or over ADB, once:

```
adb shell appops set io.github.hccullen.volumehud SYSTEM_ALERT_WINDOW allow
```

If the window is refused anyway, the add-on stops trying until the next process start and logs the
refusal to `logcat -s VolumePill`.

## Good to know

- **A TV with fixed volume** (sound through HDMI ARC where Android reports the volume as fixed) has
  no stream level to read, so there is nothing for the pill to show.
- **One pill at a time.** The launcher in this repo's `launcher/` once drew its own pill behind a
  `volume_hud` config switch. With both installed the two draw on the same spot; retire the
  launcher-side patch before relying on the add-on.
- The pill sits under the on-screen keyboard and toasts in Android's overlay order; for a
  not-touchable, not-focusable capsule that has not mattered on any TV tried.

## Tested on

Philips TPM191E (`PH9M_EA_5599`, MediaTek MT5887), Android TV 12, with the sound routed over
HDMI ARC to a Sonos soundbar, over ADB and the real remote. No internet permission, own art.
