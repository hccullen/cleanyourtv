package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConfigCoordinateTest {
    @Test public void keepsAReadableCoordinate() {
        assertEquals("55.6761", Config.coordinate(" 55.6761 ", -90, 90));
        assertEquals("-25.28", Config.coordinate("-25.28", -90, 90));
        assertEquals("179.999", Config.coordinate("179.999", -180, 180));
    }

    @Test public void dropsWhatDoesNotParse() {
        assertEquals("", Config.coordinate("home", -90, 90));
        assertEquals("", Config.coordinate("", -90, 90));
        assertEquals("", Config.coordinate(null, -90, 90));
        assertEquals("", Config.coordinate("54,69", -90, 90));
    }

    @Test public void dropsWhatIsOutOfThisWorld() {
        assertEquals("", Config.coordinate("90.5", -90, 90));
        assertEquals("", Config.coordinate("-91", -90, 90));
        assertEquals("", Config.coordinate("181", -180, 180));
        assertEquals("", Config.coordinate("-180.1", -180, 180));
    }
}
