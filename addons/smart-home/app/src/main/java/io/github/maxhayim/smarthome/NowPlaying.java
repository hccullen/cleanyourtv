package io.github.maxhayim.smarthome;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Home panel's now-playing row: which media player wins, and what its line says. The launcher
 * (com.gathin.tvhome) reads the panel provider's rows and renders them beside the wallpaper caption;
 * this class is the decision core, with no Android types and no network, so the JVM tests cover it
 * against the documented /api/states answers.
 *
 * Config keys (pushed config.json or the app's settings): "preferred_media_player" (one entity id)
 * and "fallback" (true, false, or a list of entity ids). Nothing set means fallback true: any player
 * that is playing, first by entity id. Every combination is valid; there is no invalid state.
 */
final class NowPlaying {
    /** What happens when the preferred player is not the one playing. */
    static final class Fallback {
        enum Mode { ALL, NONE, ORDERED }

        /** Any playing player: the first by entity id. The default. */
        static final Fallback ALL = new Fallback(Mode.ALL, Collections.emptyList());
        /** Nothing: the preferred player or an empty panel. */
        static final Fallback NONE = new Fallback(Mode.NONE, Collections.emptyList());

        final Mode mode;
        final List<String> ids;

        /** The configured entities, in order; the first one playing wins. */
        Fallback(List<String> ids) {
            this(Mode.ORDERED, ids);
        }

        private Fallback(Mode mode, List<String> ids) {
            this.mode = mode;
            this.ids = Collections.unmodifiableList(ids);
        }
    }

    /** One media_player entity, only the fields the row needs. */
    static final class Player {
        final String id;
        final String state;
        final String name;
        final String title;
        final String artist;
        /** The entity_picture path on the server ("/api/media_player_proxy/..."), or "" when there is none. */
        final String picture;

        Player(String id, String state, String name, String title, String artist, String picture) {
            this.id = id;
            this.state = state;
            this.name = name;
            this.title = title;
            this.artist = artist;
            this.picture = picture;
        }

        boolean playing() {
            // "buffering" counts: the track is about to play, and showing it a beat early beats a gap.
            return "playing".equals(state) || "buffering".equals(state);
        }
    }

    private NowPlaying() {
    }

    /**
     * One media_player entity from a /api/states entry; null for anything that is not a media player.
     * An empty friendly_name falls back to the entity id without its domain.
     */
    static Player player(JSONObject o) {
        String id = o.optString("entity_id", "");
        if (!id.startsWith("media_player.")) return null;
        JSONObject a = o.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        String name = a.optString("friendly_name", "").trim();
        if (name.isEmpty()) {
            name = id.substring("media_player.".length()).replace('_', ' ');
            name = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        String title = a.optString("media_title", "").trim();
        String artist = a.optString("media_artist", "").trim();
        String picture = a.optString("entity_picture", "").trim();
        return new Player(id, o.optString("state", ""), name, title, artist, picture);
    }

    /**
     * The one player the panel shows, or null for an empty panel: the preferred entity when it is
     * playing, else per the fallback. Only players with something to say count (a title or an artist);
     * idle or unavailable players never win, so candidates arrive from a state filter plus this check.
     */
    static Player pick(List<Player> candidates, String preferred, Fallback fallback) {
        List<Player> usable = new ArrayList<>();
        for (Player p : candidates) {
            if (p != null && p.playing() && usableText(p)) usable.add(p);
        }
        if (preferred != null && !preferred.trim().isEmpty()) {
            String want = preferred.trim();
            for (Player p : usable) if (p.id.equals(want)) return p;
        }
        switch (fallback.mode) {
            case ALL:
                usable.sort(Comparator.comparing(p -> p.id));
                return usable.isEmpty() ? null : usable.get(0);
            case ORDERED:
                for (String id : fallback.ids) {
                    String want = id.trim();
                    for (Player p : usable) if (p.id.equals(want)) return p;
                }
                return null;
            default:
                return null;
        }
    }

    /** A player with neither a title nor an artist shows nothing: skip it for the next candidate. */
    static boolean usableText(Player p) {
        return !(p.title.isEmpty() && p.artist.isEmpty());
    }

    /** The row's big line: the title, or the artist alone when the source gives no title (a radio show). */
    static String line1(Player p) {
        return p.title.isEmpty() ? p.artist : p.title;
    }

    /**
     * The row's second line, first half: the artist, or "" when the big line already is the artist
     * (a source with no title), so nothing is said twice.
     */
    static String line2(Player p) {
        return p.title.isEmpty() ? "" : p.artist;
    }

    /**
     * The row's second line, second half: the device's friendly name. The launcher joins the two
     * halves with a middle dot and dims the name, so "Artist · Speaker" reads artist first.
     */
    static String line3(Player p) {
        return p.name;
    }

    /**
     * The fallback from its stored form: "true" or "false", or a comma-separated list of entity ids.
     * An empty or null string is the default (ALL). The pushed config.json's boolean and array forms
     * are flattened to this string before storage, see Config.
     */
    static Fallback parseFallback(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.equals("true")) return Fallback.ALL;
        if (s.equals("false")) return Fallback.NONE;
        List<String> ids = new ArrayList<>();
        for (String part : s.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids.isEmpty() ? Fallback.ALL : new Fallback(ids);
    }
}
