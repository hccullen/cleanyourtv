# Album

Your own photographs as a slideshow on Home: the pictures you put in `wallpapers/` are shuffled
afresh every morning, a new one cross-fades in every 20 minutes, and the one on screen carries its
name and the day you took it under the clock. After dark the picture dims so the room stays calm,
and the fast-forward and rewind keys step through the album whenever you want the next one now.

The theme ships **no pictures** - it is a frame for yours. Add them and push, as below; until you
do, Home shows the plain background.

![Album theme](screenshot.jpg)

## What it changes

| Key | Value | Effect |
|---|---|---|
| `columns` | `5` | Five tiles per row |
| `ambient_glow_intensity` | `80` | The app-colour glow rests a little darker |
| `grid_hide_on_top_bar` | `true` | Focus on the top bar slides the grid away, so the photo is the screen |
| `wallpaper` | `true` | Show the `wallpapers/` collection |
| `wallpaper_rotation_minutes` | `20` | A new picture every 20 minutes |
| `wallpaper_order` | `daily` | The collection is shuffled once per day: the same order all day, a new one each morning |
| `wallpaper_true_color` | `true` | Full 32-bit colour, so photos keep their gradients |
| `wallpaper_dim` | `12` | A light shade so the clock and tiles read over any photo |
| `wallpaper_night_dim` | `40` | After dark the photo dims further, until an hour before sunrise |
| `wallpaper_captions` | `true` | The picture's name under the top-right buttons, from `captions.txt` |
| `wallpaper_caption_date` | `true` | Its date on a second, dimmer line: the optional third column of `captions.txt` |
| `wallpaper_crossfade_ms` | `1200` | Each change fades over 1.2 s instead of cutting |
| `wallpaper_next_keycodes` | `[90, 272]` | Fast-forward and skip-forward step to the next picture |
| `wallpaper_prev_keycodes` | `[89, 273]` | Rewind and skip-backward step back |

An older launcher applies the keys it knows and ignores the rest, so this theme degrades to a
plain rotating collection on a build without the newer keys.

## Your pictures

1. **Put them in `themes/album/wallpapers/`**, one file per picture: JPEG, PNG or WebP, 1920x1080
   (a smaller 16:9 picture is scaled up), under about 300 KB each, up to 100 pictures. Name them
   with letters, digits, `.`, `_` and `-` only - `01-hakone.jpg`, not `01 hakone.jpg`: the push
   refuses a name with a space or an accent in it.
2. **Name and date them in `themes/album/wallpapers/captions.txt`**, one line per picture, the
   columns separated by tab characters (the Tab key, not spaces) - the folder ships a template with
   the format in comments, ready to replace:

   ```
   01-hakone.jpg	Tonosawa, Hakone	5 Oct 2025
   02-nanzenji.jpg	Nanzen-ji, Kyoto
   ```

   The first column is the file name; the second is the name under the clock; the third, the date,
   is optional and shows only while `wallpaper_caption_date` is on. A picture without a line shows
   no name, and a line without a date shows no date.
3. **Push the folder to the TV:**

   ```bash
   TV_HOST=<tv ip> tools/theme.sh themes/album
   # Windows
   powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv ip> themes\album
   ```

   That sends `config.json`, the pictures and the captions over your network and restarts Home:
   no rebuild, no USB stick, nothing leaves your house. Undo with `tools/theme.sh --reset`.

Only ship pictures you have the right to share: for a pull request, your own photographs, or
someone else's under a licence that allows redistribution, named in a `CREDITS.md` beside them (see
[`themes/sweden/CREDITS.md`](../sweden/CREDITS.md) for the format). A private copy on your own TV
can hold whatever you like.

### After dark follows the real sun

`wallpaper_night_dim` lifts and returns by the sun, not the clock. Without a place set, "after
dark" is 18:30 to 07:00; put your coordinates in your copy of `config.json` and it follows the
real sunrise and sunset through the seasons:

```json
{ "latitude": "55.6761", "longitude": "12.5683" }
```

A theme's value wins over the one the build carries, and both need each other: one alone does
nothing.

### One picture instead

To show a single photo instead of the album, save it as `themes/album/wallpaper.jpg`, set
`"wallpaper_rotation_minutes": 0` in `config.json`, and remove the `wallpapers/` folder.

## Install

```bash
TV_HOST=<tv ip> tools/theme.sh themes/album
# Windows
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv ip> themes\album
```

Undo with `tools/theme.sh --reset`.

## Tested on

<!-- Say which TV, Android version and firmware the screenshot was taken on, before a PR. -->

## Credit and forks

You may copy, change and reuse this theme. If you publish a modified version, use a different
folder name so people can tell them apart.
