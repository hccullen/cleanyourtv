package io.github.maxhayim.smarthome;

import org.json.JSONArray;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PanelProviderTest {
    @Test public void playersKeepsOnlyMediaPlayersInOrder() {
        String json = "["
                + "{\"entity_id\": \"light.kitchen\", \"state\": \"on\", \"attributes\": {}},"
                + "{\"entity_id\": \"media_player.beam\", \"state\": \"playing\","
                + "  \"attributes\": {\"friendly_name\": \"Beam\", \"media_title\": \"Song\"}},"
                + "{\"entity_id\": \"media_player.bath\", \"state\": \"paused\", \"attributes\": {}},"
                + "{\"entity_id\": \"sensor.temp\", \"state\": \"21\", \"attributes\": {}}]";
        List<NowPlaying.Player> players = PanelProvider.players(json);
        assertEquals(2, players.size());
        assertEquals("media_player.beam", players.get(0).id);
        assertEquals("media_player.bath", players.get(1).id);
        assertEquals("Song", players.get(0).title);
    }

    @Test public void playersSwallowsBrokenJsonAndReturnsNothing() {
        assertTrue(PanelProvider.players("not json").isEmpty());
        assertTrue(PanelProvider.players("{}").isEmpty());
        assertTrue(PanelProvider.players("").isEmpty());
    }

    @Test public void sampleLandsTheLargerSideBetweenTheBounds() {
        // 256 and below decode as they are.
        assertEquals(1, PanelProvider.sample(256, 256));
        assertEquals(1, PanelProvider.sample(300, 300));
        // Each step halves only while the half is still at or above the target, so the decoded
        // larger side lands in [256, 512) and the exact scale finishes the job.
        assertEquals(2, PanelProvider.sample(512, 512));    // decodes to 256
        assertEquals(4, PanelProvider.sample(1024, 1024));  // decodes to 256
        assertEquals(8, PanelProvider.sample(2048, 2048));  // decodes to 256
        // A tall cover takes its larger side.
        assertEquals(4, PanelProvider.sample(500, 1024));
    }

    @Test public void digestIsStableTwelveHexDigits() {
        String d = PanelProvider.digest("http://ha/api/media_player_proxy/beam|Song");
        assertEquals(12, d.length());
        assertTrue(d, d.matches("[0-9a-f]{12}"));
        assertEquals(d, PanelProvider.digest("http://ha/api/media_player_proxy/beam|Song"));
    }

    @Test public void fallbackSurvivesThePushedArrayForm() throws Exception {
        // What fallbackRaw stores when config.json carried true / false / a list.
        assertEquals(NowPlaying.Fallback.ALL, NowPlaying.parseFallback(Config.fallbackRaw(true)));
        assertEquals(NowPlaying.Fallback.NONE, NowPlaying.parseFallback(Config.fallbackRaw(false)));
        JSONArray list = new JSONArray("[\"media_player.beam\", \"media_player.bath\"]");
        NowPlaying.Fallback fb = NowPlaying.parseFallback(Config.fallbackRaw(list));
        assertEquals(NowPlaying.Fallback.Mode.ORDERED, fb.mode);
        assertEquals("media_player.beam", fb.ids.get(0));
        assertEquals("", Config.fallbackRaw(null));
    }
}
