package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PanelPillTest {
    private static final byte[] SMALL = new byte[16];
    private static final byte[] HUGE = new byte[PanelPill.MAX_ART + 1];

    @Test public void aRowWithoutABigLineShowsNothing() {
        assertTrue(PanelPill.fromRows(Collections.singletonList(
                new Object[]{"", "", "", 0, null, ""})).isEmpty());
        assertTrue(PanelPill.fromRows(Collections.singletonList(
                new Object[]{null, "Artist", "Kitchen", 0, null, ""})).isEmpty());
        assertTrue(PanelPill.fromRows(Collections.singletonList(new Object[]{"   ", "", "", 0, null, ""})).isEmpty());
    }

    @Test public void aRowIsTrimmedAndCapped() {
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 200; i++) long1.append('a');
        List<PanelPill.Row> rows = PanelPill.fromRows(Collections.singletonList(
                new Object[]{"  " + long1 + "  ", "  Artist  ", "Kitchen", 0xFF112233, SMALL, "abc123"}));
        assertEquals(1, rows.size());
        PanelPill.Row row = rows.get(0);
        assertEquals(PanelPill.MAX_LINE, row.line1.length());
        assertEquals("Artist", row.line2);
        assertEquals("Kitchen", row.line3);
        assertEquals(0xFF112233, row.tone);
        assertEquals(SMALL, row.art);
        assertEquals("abc123", row.artId);
    }

    @Test public void wrongTypesFallBackRatherThanCrash() {
        List<PanelPill.Row> rows = PanelPill.fromRows(Collections.singletonList(
                new Object[]{"Title", 42, 43, "not a number", "not bytes", 7}));
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).line2);
        assertEquals("", rows.get(0).line3);
        assertEquals(0, rows.get(0).tone);
        assertNull(rows.get(0).art);
        assertEquals("", rows.get(0).artId);
    }

    @Test public void artTooBigForTheCornerIsDroppedTextAndAll() {
        List<PanelPill.Row> rows = PanelPill.fromRows(Collections.singletonList(
                new Object[]{"Song", "Artist", "Kitchen", 0, HUGE, "abc"}));
        assertNull(rows.get(0).art);
        assertEquals("Song", rows.get(0).line1);
        // art_id stays: it only gates re-decoding, and there is nothing to decode.
        assertEquals("abc", rows.get(0).artId);
    }

    @Test public void atMostThreeRowsComeThrough() {
        List<Object[]> four = Arrays.asList(
                new Object[]{"One", "", "", 0, null, ""},
                new Object[]{"Two", "", "", 0, null, ""},
                new Object[]{"Three", "", "", 0, null, ""},
                new Object[]{"Four", "", "", 0, null, ""});
        List<PanelPill.Row> rows = PanelPill.fromRows(four);
        assertEquals(3, rows.size());
        assertEquals("One", rows.get(0).line1);
        assertEquals("Three", rows.get(2).line1);
    }

    @Test public void shortAndOddRowsAreSkippedSafely() {
        assertTrue(PanelPill.fromRows(Collections.singletonList(new Object[]{"Song"})).isEmpty());
        assertTrue(PanelPill.fromRows(Collections.singletonList(new Object[]{null})).isEmpty());
        assertTrue(PanelPill.fromRows(Collections.singletonList(null)).isEmpty());
    }

    @Test public void boundedTrimsAndCapsWithoutSurprises() {
        assertEquals("Kitchen", PanelPill.bounded(" Kitchen \n"));
        assertNull(PanelPill.bounded(null));
        assertNull(PanelPill.bounded("   "));
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 100; i++) long1.append('x');
        assertEquals(PanelPill.MAX_LINE, PanelPill.bounded(long1.toString()).length());
    }
}
