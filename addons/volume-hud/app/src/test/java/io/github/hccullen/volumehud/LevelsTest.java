package io.github.hccullen.volumehud;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LevelsTest {
    @Test
    public void theLabelIsTheLevelAsAPercent() {
        assertEquals("0%", Levels.label(false, 0, 20));
        assertEquals("33%", Levels.label(false, 1, 3));
        assertEquals("25%", Levels.label(false, 5, 20));
        assertEquals("100%", Levels.label(false, 20, 20));
    }

    @Test
    public void aMutedPillSaysMutedAndDrawsNoBar() {
        assertEquals("MUTED", Levels.label(true, 10, 20));
        assertEquals(0, Levels.width(100, 10, 20, true));
    }

    @Test
    public void anEmptyRangeIsNeverDividedBy() {
        assertEquals("?", Levels.label(false, 3, 0));
        assertEquals(0, Levels.width(100, 3, 0, false));
    }

    @Test
    public void theBarSpansTheTrack() {
        assertEquals(0, Levels.width(100, 0, 20, false));
        assertEquals(50, Levels.width(100, 10, 20, false));
        assertEquals(100, Levels.width(100, 20, 20, false));
    }
}
