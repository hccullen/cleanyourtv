# Home panel, spec 1

An installed Android TV app can serve lines for the launcher's **Home panel**: the now-playing row
under the clock (a title, an artist, a speaker's name, a cover art thumbnail). The launcher names
the app in its `panel_packages` config and reads the app's rows through the app's own read-only
content provider. Naming the package is the owner's opt-in — there is no prompt, because the owner
wrote the config that names the app.

This document is the contract between a panel app and the launcher. The launcher stays offline: the
app fetches what its rows need and hands the result over as text and bytes.

## Declaration

In the app's `AndroidManifest.xml`, on the `<application>` element:

```xml
<meta-data android:name="com.gathin.tvhome.panel" android:value="${applicationId}.panel" />
```

and a read-only provider:

```xml
<provider
    android:name=".PanelProvider"
    android:authorities="${applicationId}.panel"
    android:exported="true" />
```

Rules for the declaration:

- The launcher resolves the authority out of the `com.gathin.tvhome.panel` meta-data of the package
  its `panel_packages` names, then verifies the provider at that authority belongs to that same
  package. A provider that belongs to another app is refused, so a third app cannot answer for a
  panel it does not own.
- The app must be visible to the launcher: any app with a `LEANBACK_LAUNCHER` activity is.
- The provider is read-only. `insert`, `update` and `delete` answer nothing.
- The panel is on only when `home_panel_pill` is true and `panel_packages` names at least one
  package. Both keys can also come from a theme's `config.json`.

## Rows

One query path, `lines`:

```
content://<authority>/lines
```

The launcher queries it with the columns below, in this order, and reads by position:

| Column | Type | Meaning |
| :- | :- | :- |
| `line1` | String | Required. The row's big line — a title, a headline. Null or blank: the row is skipped. |
| `line2` | String | The second line's first half (an artist, a state), shown at full second-line colour. |
| `line3` | String | The second line's second half (a device, a place). The launcher joins `line2` and `line3` with a middle dot and shows the `line3` part dimmed. |
| `tone` | Int | ARGB colour for `line1`; 0 means the launcher's default. |
| `art` | Blob | The cover image as a small JPEG (a 256 px re-encode is the reference size). Null: the row shows text only. Over 256 KB: dropped, text only. |
| `art_id` | String | Any string that changes when the art should be re-decoded (a hash of its source). Unchanged: the launcher keeps the bitmap it has. |

The launcher renders at most **3 rows** (one per package, several providers stack), trims every
line and caps it at **80 characters**. Anything beyond the caps is cut, not refused.

## Refresh model

The launcher never polls and never watches: it queries when Home comes back to the front and once
a minute while Home is on screen, and nothing while Home is off screen. The app answers each query
out of its own cache or fetches as it sees fit — a query may take a moment (the launcher reads off
its main thread), but a provider that answers in seconds keeps the panel feeling live, and one that
answers instantly from its cache keeps the battery quiet.

A query that answers with no rows, an absent provider or an error costs its row and nothing else:
the panel shows what the remaining packages serve, or simply is not there.

## What the app must not rely on

- **No launcher network.** The launcher has no `INTERNET` permission and never will; a panel that
  needs the network does its fetching in the app and ships bytes in the cursor.
- **No back channel.** The launcher sends nothing but the query. Configuration (entities, formats,
  preferred sources) lives in the app's own config, reached however the app likes.
- **No ordering promises between packages.** Rows appear in the order the packages are named.

## Privacy

A panel provider is `exported`: any app on the TV can read its rows. Serve nothing in a row you
would not put on the screen in a room full of people — for a now-playing panel that is the title,
the artist and the speaker's name, and nothing else.
