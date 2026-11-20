package io.github.maxhayim.smarthome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NowPlayingTest {
    private static NowPlaying.Player player(String id, String state, String title, String artist, String name) {
        return new NowPlaying.Player(id, state, name, title, artist, "");
    }

    private static JSONObject entity(String id, String state, String title, String artist, String name)
            throws org.json.JSONException {
        return new JSONObject("{\"entity_id\": \"" + id + "\", \"state\": \"" + state + "\", \"attributes\": {"
                + "\"friendly_name\": \"" + name + "\", \"media_title\": \"" + title
                + "\", \"media_artist\": \"" + artist + "\"}}");
    }

    @Test public void onlyMediaPlayersParse() throws Exception {
        assertNull(NowPlaying.player(entity("light.kitchen", "on", "", "", "Kitchen")));
        NowPlaying.Player p = NowPlaying.player(entity("media_player.sonos_one", "playing", "Song", "Artist", "Sonos One"));
        assertEquals("media_player.sonos_one", p.id);
        assertEquals("Sonos One", p.name);
        assertEquals("Song", p.title);
        assertEquals("Artist", p.artist);
        assertTrue(p.playing());
    }

    @Test public void emptyFriendlyNameFallsBackToTheId() throws Exception {
        JSONObject o = new JSONObject("{\"entity_id\": \"media_player.kitchen\", \"state\": \"playing\", \"attributes\": {}}");
        assertEquals("Kitchen", NowPlaying.player(o).name);
    }

    @Test public void thePreferredPlayerWinsWhenItIsPlaying() {
        List<NowPlaying.Player> all = Arrays.asList(
                player("media_player.bedroom", "playing", "Other", "Someone", "Bedroom"),
                player("media_player.sonos_one", "playing", "Song", "Artist", "Sonos One"));
        assertEquals("media_player.sonos_one", NowPlaying.pick(all, "media_player.sonos_one", NowPlaying.Fallback.ALL).id);
    }

    @Test public void anIdlePreferredPlayerStepsAsideForTheFallback() {
        List<NowPlaying.Player> all = Collections.singletonList(
                player("media_player.bedroom", "playing", "Other", "Someone", "Bedroom"));
        assertEquals("media_player.bedroom",
                NowPlaying.pick(all, "media_player.sonos_one", NowPlaying.Fallback.ALL).id);
    }

    @Test public void allFallbackPicksAlphabetically() {
        List<NowPlaying.Player> all = Arrays.asList(
                player("media_player.kitchen", "playing", "C", "", "Kitchen"),
                player("media_player.bedroom", "playing", "B", "", "Bedroom"));
        assertEquals("media_player.bedroom", NowPlaying.pick(all, "", NowPlaying.Fallback.ALL).id);
    }

    @Test public void orderedFallbackFollowsItsList() {
        List<NowPlaying.Player> all = Arrays.asList(
                player("media_player.kitchen", "playing", "C", "", "Kitchen"),
                player("media_player.bedroom", "playing", "B", "", "Bedroom"));
        NowPlaying.Fallback fb = new NowPlaying.Fallback(Arrays.asList("media_player.kitchen", "media_player.bedroom"));
        assertEquals("media_player.kitchen", NowPlaying.pick(all, "media_player.sonos_one", fb).id);
        NowPlaying.Fallback reversed = new NowPlaying.Fallback(Arrays.asList("media_player.bedroom", "media_player.kitchen"));
        assertEquals("media_player.bedroom", NowPlaying.pick(all, "media_player.sonos_one", reversed).id);
        // An id that plays nowhere is skipped, the next one answers.
        NowPlaying.Fallback ghost = new NowPlaying.Fallback(Arrays.asList("media_player.hall", "media_player.bedroom"));
        assertEquals("media_player.bedroom", NowPlaying.pick(all, "media_player.sonos_one", ghost).id);
    }

    @Test public void noFallbackMeansThePreferredOneOrNothing() {
        List<NowPlaying.Player> all = Collections.singletonList(
                player("media_player.bedroom", "playing", "Other", "Someone", "Bedroom"));
        assertNull(NowPlaying.pick(all, "media_player.sonos_one", NowPlaying.Fallback.NONE));
        assertEquals("media_player.bedroom",
                NowPlaying.pick(all, "media_player.bedroom", NowPlaying.Fallback.NONE).id);
    }

    @Test public void playersWithoutTextNeverWin() {
        List<NowPlaying.Player> all = Collections.singletonList(
                player("media_player.tv", "playing", "", "", "TV"));
        assertNull(NowPlaying.pick(all, "", NowPlaying.Fallback.ALL));
    }

    @Test public void bufferingCountsAsPlaying() {
        List<NowPlaying.Player> all = Collections.singletonList(
                player("media_player.beam", "buffering", "Next track", "", "Beam"));
        assertEquals("media_player.beam", NowPlaying.pick(all, "", NowPlaying.Fallback.ALL).id);
    }

    @Test public void nothingPlayingIsAnEmptyPanel() {
        List<NowPlaying.Player> all = Arrays.asList(
                player("media_player.bedroom", "paused", "Other", "Someone", "Bedroom"),
                player("media_player.kitchen", "idle", "C", "", "Kitchen"));
        assertNull(NowPlaying.pick(all, "", NowPlaying.Fallback.ALL));
        assertNull(NowPlaying.pick(Collections.emptyList(), "media_player.x", NowPlaying.Fallback.ALL));
    }

    @Test public void theRowReadsTitleThenArtistDotSpeaker() {
        NowPlaying.Player sonos = player("media_player.sonos_one", "playing", "Song", "Artist", "Sonos One");
        assertEquals("Song", NowPlaying.line1(sonos));
        assertEquals("Artist", NowPlaying.line2(sonos));
        assertEquals("Sonos One", NowPlaying.line3(sonos));
        // A radio show without a title shows the artist as the big line, so the second half
        // stays empty and only the speaker is named under it.
        NowPlaying.Player radio = player("media_player.kitchen", "playing", "", "Morning show", "Kitchen");
        assertEquals("Morning show", NowPlaying.line1(radio));
        assertEquals("", NowPlaying.line2(radio));
        assertEquals("Kitchen", NowPlaying.line3(radio));
    }

    @Test public void theFallbackReadsTrueFalseAndLists() {
        assertEquals(NowPlaying.Fallback.ALL, NowPlaying.parseFallback(null));
        assertEquals(NowPlaying.Fallback.ALL, NowPlaying.parseFallback(""));
        assertEquals(NowPlaying.Fallback.ALL, NowPlaying.parseFallback("true"));
        assertEquals(NowPlaying.Fallback.NONE, NowPlaying.parseFallback("false"));
        NowPlaying.Fallback list = NowPlaying.parseFallback("media_player.kitchen, media_player.bedroom");
        assertEquals(NowPlaying.Fallback.Mode.ORDERED, list.mode);
        assertEquals(Arrays.asList("media_player.kitchen", "media_player.bedroom"), list.ids);
        // A single id is a one-entry list; a duplicate is dropped.
        assertEquals(Collections.singletonList("media_player.tv"),
                NowPlaying.parseFallback("media_player.tv").ids);
        assertEquals(Collections.singletonList("media_player.tv"),
                NowPlaying.parseFallback("media_player.tv,media_player.tv").ids);
    }
}
