# Smart Home

Your [Home Assistant](https://www.home-assistant.io) on the TV: a glance of weather and fuel prices at the top,
then scenes, lights and switches, climate, temperature and humidity sensors, doors and windows, and locks, in
sections you work with the remote. OK runs a scene or switches a light; ◀ ▶ dims a light by 10 % or moves a
thermostat by half a degree. A community add-on, not made by or affiliated with the Home Assistant project. For
the [Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md): once you answer
**Show card** its tile reads "Smart Home" with a blue glow. On any other Android TV home it is an ordinary app.

![Smart Home on a TV, with the glance header](screenshot.jpg)

| Section | Shows | With the remote |
|---|---|---|
| Glance | `weather.*` temperature and condition icon; sensors whose unit is `€`, `EUR`, `€/L` or `EUR/L` (Tankerkönig and the like) | Shown only |
| Scenes | `scene.*` | OK runs it |
| Lights and switches | `light.*`, `switch.*`, `fan.*`, `input_boolean.*`, on or off with brightness | OK toggles, ◀ ▶ dims a light |
| Climate | `climate.*`: room temperature → set temperature | ◀ ▶ moves the set temperature |
| Sensors | `sensor.*` with device class temperature or humidity | Shown only |
| Doors and windows | `binary_sensor.*` with device class door, window, garage door or opening | Shown only |
| Locks | `lock.*`, locked or unlocked | **Shown only: a TV remote never unlocks a door** |

At most 80 entities, sorted by name within each section; unavailable ones are dimmed. A scene that has never
run reads `unknown` in Home Assistant and can still be run. Fuel prices show as Home Assistant reports them, with
all their decimals (1.689 €/L). The glance header takes the
first available weather entity and up to six fuel-price sensors unless you name the entities in the config (below).
It reads `GET /api/states` every 10 seconds while the screen is open, and nothing when it is closed.

## Setup

You need the server's address (for example `http://192.168.1.10:8123`) and a long-lived access token: in Home
Assistant, open your profile, then Security, then Long-lived access tokens, Create token. Then either:

- open the app and fill in **Home Assistant address** and **Access token** with the TV's keyboard (or the phone
  remote's), or
- put both in a file and push it from a computer, so the token is never typed on the TV or put on a command line:

  ```bash
  echo '{"url": "http://192.168.1.10:8123", "token": "eyJ...", "glance": ["weather.home", "sensor.station_e10", "sensor.station_diesel"]}' > backup/smart-home.json
  TV_HOST=<tv ip> addons/smart-home/install.sh backup/smart-home.json
  ```

  `glance` is optional: without it the app picks the first weather entity and up to six euro fuel-price sensors.
  `show`, `hide`, `hide_unavailable` and `quick` are optional too, see [Choosing the rows](#choosing-the-rows)
  and [A remote button that runs one thing](#a-remote-button-that-runs-one-thing).
  The app takes the file into its own storage and deletes it. Keep the file outside the repo (`backup/` is
  git-ignored).

### Choosing the rows

With many entities the list gets long. Three optional keys in the pushed file pick what it shows:

| Key | What it does |
|---|---|
| `show` | Only these entity ids, in this order within each section. Empty or missing: every supported entity. |
| `hide` | Leave these out: entity ids, or a prefix ending in `*`, such as `scene.office_*`. |
| `hide_unavailable` | `true` leaves out what Home Assistant reports as unavailable (and unknown, except scenes). |

```json
{"show": ["scene.living_room_bright", "light.floor_lamp", "climate.living_room"], "hide_unavailable": true}
```

Only the keys in a pushed file change, so a file with these keys alone keeps the address and token. An empty list
switches that filter off. The rows are filtered before the 80-row cap, and the glance header is not filtered.

Tip: to list a room's entities, open Developer tools > Template in Home Assistant and enter
`{{ area_entities('Living room') | join(', ') }}`; the ids it prints go straight into `show`.

## A remote button that runs one thing

A pushed file's `quick` key names one entity that a remote button runs without opening the app: a scene or a
script turns on, an automation triggers, a light, switch, fan or input boolean toggles. It needs the server keys
(the button uses the same address and token as the app). Map a remote button to it with the launcher's `app_keys`:

```json
{"url": "http://192.168.1.10:8123", "token": "eyJ...", "quick": "scene.movie_mode"}
```

```
"app_keys": ["scan:<scancode>:io.github.maxhayim.smarthome/.QuickActivity"]
```

The press is silent when it works — the house changing is the feedback — and shows a toast only when
something failed: no server set up, no entity for that digit, an unknown entity, or what went wrong on the
network. A file with `quick` alone adds or changes it without touching the server
keys. A mapped button when the add-on is not installed does nothing (that is the launcher's rule for every
`app_keys` entry); when `quick` is not set, the toast says so.

The remote's number buttons each carry their own: keys `quick_1` to `quick_0` in a pushed file name an entity
per digit, and the launcher maps the number keys to the same activity:

```json
{"quick_1": "scene.movie_mode", "quick_2": "scene.tv_time", "quick_0": "script.all_off"}
```

```
"app_keys": ["KEYCODE_1:io.github.maxhayim.smarthome/.QuickActivity"]
```

The launcher's key services attach the pressed key's code to every activity they open, so the activity picks
the digit's entity; a digit with none toasts "Number 5 has no quick action set" instead of doing nothing.
Changing what a number runs is a config push away, with no rebuild. Note a number key listed in `app_keys` is
taken everywhere, including channel entry in a Live TV app.

A value that is a link instead of an entity id opens on the TV rather than calling Home Assistant, and that
press needs no server at all: `"quick_4": "https://www.youtube.com/watch?v=2wYtJwDkKIk"` plays that video in
the TV's YouTube app (a `youtube.com` or `vnd.youtube:` link is aimed at the YouTube package directly, so no
browser or chooser answers first; any other link opens by resolution).

## The Home panel: what is playing, under the clock

With the launcher's Home panel on (`home_panel_pill`, with this package named in `panel_packages` — see
[`docs/specs/panel.md`](../../docs/specs/panel.md)), the app serves one row: what a Home Assistant media player
is playing right now, with its cover art. The launcher asks when Home comes back and once a minute; the app
answers from a 20-second cache and otherwise reads Home Assistant once, with short timeouts — no service, no
poller, nothing runs while Home is off screen. Nothing playing: no row, and the launcher's panel is not there.

Two keys in a pushed file choose the player:

```json
{"preferred_media_player": "media_player.beam", "fallback": true}
```

| Key | Meaning |
|---|---|
| `preferred_media_player` | The entity id the panel shows whenever it is playing. Leave it out to let any player win. |
| `fallback` | `true` (the default): any playing player, first by entity id. `false`: the preferred one or nothing. A list: those entities in order, first one playing wins. |

Every combination is valid — with neither key set, the panel shows any player in the house that is playing.
A row reads the title as the big line, `Artist · Speaker` under it (the speaker dimmed; a source without a
title shows the artist as the big line), and the cover art if the player has one. The launcher keeps the
art small and re-decodes it only when it changes. A broken or slow Home Assistant shows the last row it
managed, or no row — never an error on your home screen.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Your Home Assistant server, and nothing else: no telemetry, no update checks, no ads. |

Plain `http://` is allowed for the home network (`res/xml/network.xml`); use `https://` with a certificate from a
public authority (Nabu Casa, Let's Encrypt). The token stays in the app's own storage (backups are off) and is
sent only to the address you entered, as `Authorization: Bearer`. `ConfigProvider` takes a pushed file and only
the ADB shell can use it (it requires `WRITE_SECURE_SETTINGS`, as Frigate Glance's does). No foreground service,
accounts or libraries.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
TV_HOST=<tv ip> addons/smart-home/install.sh [config.json]
```

To build only: `cd addons/smart-home && ./gradlew assembleRelease`; the JVM tests run with
`./gradlew testReleaseUnitTest`.

A new `screenshot.jpg` from a real TV (16:9, under 300 KB, address and token blurred) belongs with any visual
change to the glance header.

## Limits

- **Self-signed HTTPS** certificates are refused. Use `http://` on the home network or a public certificate.
- **`homeassistant.local`** names only resolve where the TV's network does mDNS name lookups; use the IP address
  if it doesn't.
- **Which entities** can appear is fixed by domain and device class; within that, `show` and `hide` pick the rows
  and the glance list can be named, all in `config.json`. There is no on-TV picker yet.

## Tested on

MOKA R8G (Android 14), on a home app that implements the module-card spec, against a stand-in server on the home network answering with Home Assistant's documented response shapes: the config pushed through `ConfigProvider`, reading the states, running a scene and dimming a light (the requests arrived as the REST API documents them). The screenshot shows the glance header on the Sony BRAVIA, with the address blurred. Also tried on a Sony BRAVIA (Android 9) against a real Home Assistant: weather and a euro fuel-price sensor on the glance header (with unit). The JVM tests cover parsing, the service calls, the request headers against a local stand-in, and errors.

## Maintainer

[@maxhayim](https://github.com/maxhayim)
