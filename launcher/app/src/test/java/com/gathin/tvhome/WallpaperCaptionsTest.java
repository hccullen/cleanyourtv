package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.Test;

public class WallpaperCaptionsTest {

    @Test
    public void readsFilenameTabName() {
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.parse(Arrays.asList(
                "001.jpg\tRed Spider Nebula · Hubble",
                "002.jpg\t  Falcon 9 at sunset  "));
        assertEquals("Red Spider Nebula · Hubble", names.get("001.jpg").name);
        assertEquals("Falcon 9 at sunset", names.get("002.jpg").name);
    }

    @Test
    public void readsTheOptionalDateColumn() {
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.parse(Arrays.asList(
                "001.jpg\tDusk over the peaks\t3 Oct 2025",
                "002.jpg\tFalcon 9 at sunset"));
        assertEquals("Dusk over the peaks", names.get("001.jpg").name);
        assertEquals("3 Oct 2025", names.get("001.jpg").date);
        assertEquals("Falcon 9 at sunset", names.get("002.jpg").name);
        assertNull(names.get("002.jpg").date);
    }

    @Test
    public void anEmptyDateColumnReadsAsNoDate() {
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.parse(Arrays.asList(
                "001.jpg\tDusk over the peaks\t   ",
                "002.jpg\tFalcon 9 at sunset\t"));
        assertNull(names.get("001.jpg").date);
        assertNull(names.get("002.jpg").date);
    }

    @Test
    public void skipsCommentsBlankAndMalformedLines() {
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.parse(Arrays.asList(
                "# captions for the collection", "", "   ", "no-tab-here.jpg Dusk", "\tno file", "003.jpg\t", null));
        assertTrue(names.isEmpty());
    }

    @Test
    public void cutsVeryLongNames() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) longName.append("word ");
        Map<String, WallpaperCaptions.Caption> names =
                WallpaperCaptions.parse(Arrays.asList("004.jpg\t" + longName));
        assertTrue(names.get("004.jpg").name.length() <= WallpaperCaptions.MAX_LENGTH);
    }

    @Test
    public void cutsAVeryLongDate() {
        StringBuilder longDate = new StringBuilder();
        for (int i = 0; i < 10; i++) longDate.append("3 Oct 2025 ");
        Map<String, WallpaperCaptions.Caption> names =
                WallpaperCaptions.parse(Arrays.asList("004.jpg\tDusk\t" + longDate));
        assertTrue(names.get("004.jpg").date.length() <= WallpaperCaptions.MAX_DATE_LENGTH);
    }

    @Test
    public void laterLineWinsForTheSameFile() {
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.parse(Arrays.asList(
                "005.jpg\tOld\t1 Jan 2020", "005.jpg\tNew\t2 Jan 2021"));
        assertEquals("New", names.get("005.jpg").name);
        assertEquals("2 Jan 2021", names.get("005.jpg").date);
        assertFalse(names.containsKey("006.jpg"));
    }

    @Test
    public void missingFolderHasNoNames() {
        assertTrue(WallpaperCaptions.read(null).isEmpty());
    }

    @Test
    public void readParsesCaptionsFromTheFolder() throws Exception {
        java.io.File folder = java.nio.file.Files.createTempDirectory("captions").toFile();
        java.nio.file.Files.write(new java.io.File(folder, WallpaperCaptions.FILE_NAME).toPath(),
                ("001.jpg\tDusk over the peaks\t3 Oct 2025\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, WallpaperCaptions.Caption> names = WallpaperCaptions.read(folder);
        assertEquals("Dusk over the peaks", names.get("001.jpg").name);
        assertEquals("3 Oct 2025", names.get("001.jpg").date);
        assertTrue(WallpaperCaptions.read(new java.io.File(folder, "missing")).isEmpty());
    }
}
