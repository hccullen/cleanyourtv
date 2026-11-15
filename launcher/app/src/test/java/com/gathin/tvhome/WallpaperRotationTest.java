package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class WallpaperRotationTest {

    private static File touch(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void imagesKeepsPicturesOnlySortedByName() throws IOException {
        File dir = Files.createTempDirectory("walls").toFile();
        touch(dir, "b.JPG", 10);
        touch(dir, "a.png", 10);
        touch(dir, "notes.txt", 10);
        touch(dir, "empty.jpg", 0);
        new File(dir, "folder.jpg").mkdir();
        List<File> images = WallpaperRotation.images(dir);
        assertEquals(Arrays.asList("a.png", "b.JPG"), names(images));
        assertTrue(WallpaperRotation.images(null).isEmpty());
        assertTrue(WallpaperRotation.images(new File(dir, "missing")).isEmpty());
    }

    @Test
    public void pickAvoidsThePreviousPictureWhenThereIsAnother() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            assertNotEquals("a.jpg", WallpaperRotation.pick(images, "a.jpg", random).getName());
        }
        assertEquals("a.jpg", WallpaperRotation.pick(Collections.singletonList(new File("a.jpg")), "a.jpg", random).getName());
        assertNull(WallpaperRotation.pick(Collections.emptyList(), "", random));
    }

    @Test
    public void sequentialAdvancesInSortedOrderAndWraps() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        assertEquals("b.jpg", WallpaperRotation.next(images, "a.jpg").getName());
        assertEquals("c.jpg", WallpaperRotation.next(images, "b.jpg").getName());
        assertEquals("a.jpg", WallpaperRotation.next(images, "c.jpg").getName());
        // A first run, or an image removed from the collection, starts at the first picture.
        assertEquals("a.jpg", WallpaperRotation.next(images, "").getName());
        assertEquals("a.jpg", WallpaperRotation.next(images, "gone.jpg").getName());
        assertEquals("only.jpg", WallpaperRotation.next(
                Collections.singletonList(new File("only.jpg")), "only.jpg").getName());
        assertNull(WallpaperRotation.next(Collections.emptyList(), ""));
    }

    @Test
    public void previousStepsBackAndWraps() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        assertEquals("b.jpg", WallpaperRotation.previous(images, "c.jpg").getName());
        assertEquals("a.jpg", WallpaperRotation.previous(images, "b.jpg").getName());
        assertEquals("c.jpg", WallpaperRotation.previous(images, "a.jpg").getName());
        // A previous name that is no longer in the collection starts again at the first picture.
        assertEquals("a.jpg", WallpaperRotation.previous(images, "gone.jpg").getName());
        assertNull(WallpaperRotation.previous(Collections.emptyList(), ""));
    }

    @Test
    public void darkOutsideStartsAnHourAfterSunsetAndEndsAnHourBeforeSunrise() {
        // 07:00 sunrise, 18:30 sunset: dark from 19:30 to 06:00, bright between.
        assertTrue(WallpaperRotation.darkOutside(19 * 60 + 30, 7 * 60, 18 * 60 + 30));
        assertTrue(WallpaperRotation.darkOutside(23 * 60, 7 * 60, 18 * 60 + 30));
        assertTrue(WallpaperRotation.darkOutside(5 * 60, 7 * 60, 18 * 60 + 30));
        assertFalse(WallpaperRotation.darkOutside(19 * 60 + 29, 7 * 60, 18 * 60 + 30));
        assertFalse(WallpaperRotation.darkOutside(6 * 60, 7 * 60, 18 * 60 + 30));
        assertFalse(WallpaperRotation.darkOutside(12 * 60, 7 * 60, 18 * 60 + 30));
        // A darkening that would start past the next sunrise keeps its shape, not a crash.
        assertTrue(WallpaperRotation.darkOutside(23 * 60, 23 * 60 - 90, 23 * 60 - 180));
    }

    @Test
    public void theNightDimReplacesThePlainOneOnlyInTheDark() {
        assertEquals(12, WallpaperRotation.dimAt(12, 40, false));
        assertEquals(40, WallpaperRotation.dimAt(12, 40, true));
        // The night dim never stacks on the plain one, and 0 keeps the plain dim.
        assertEquals(40, WallpaperRotation.dimAt(40, 40, true));
        assertEquals(12, WallpaperRotation.dimAt(12, 0, true));
    }

    @Test
    public void pickUsesTheRequestedOrder() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        Random random = new Random(1);
        assertEquals("b.jpg", WallpaperRotation.pick(images, "a.jpg", WallpaperRotation.ORDER_SEQUENTIAL, random).getName());
        assertEquals("a.jpg", WallpaperRotation.pick(images, "c.jpg", WallpaperRotation.ORDER_SEQUENTIAL, random).getName());
        for (int i = 0; i < 20; i++) {
            assertNotEquals("a.jpg", WallpaperRotation.pick(
                    images, "a.jpg", WallpaperRotation.ORDER_SHUFFLE, random).getName());
        }
        // Unknown order keeps the historical shuffle rather than failing or blanking.
        assertNotEquals("a.jpg", WallpaperRotation.pick(images, "a.jpg", "random", random).getName());
    }

    @Test
    public void validOrderAcceptsExactlyTheThreeOrders() {
        assertTrue(WallpaperRotation.validOrder(WallpaperRotation.ORDER_SEQUENTIAL));
        assertTrue(WallpaperRotation.validOrder(WallpaperRotation.ORDER_SHUFFLE));
        assertTrue(WallpaperRotation.validOrder(WallpaperRotation.ORDER_DAILY));
        // Case matters; anything else is a value config.json never held.
        assertFalse(WallpaperRotation.validOrder("Daily"));
        assertFalse(WallpaperRotation.validOrder(""));
        assertFalse(WallpaperRotation.validOrder("random"));
    }

    @Test
    public void dailyOrderShufflesACopyAndRepeatsForTheSameDay() {
        List<File> images = new ArrayList<>(Arrays.asList(new File("a.jpg"), new File("b.jpg"),
                new File("c.jpg"), new File("d.jpg")));
        List<String> before = names(images);
        List<File> order = WallpaperRotation.dailyOrder(images, 42L);
        List<File> orderAgain = WallpaperRotation.dailyOrder(images, 42L);
        assertNotSame(images, order);                  // a new list, not the one handed in
        assertEquals(before, names(images));          // the collection itself is left untouched
        assertEquals(names(order), names(orderAgain)); // the same day walks in the same order
        List<String> sortedBefore = new ArrayList<>(before);
        List<String> sortedOrder = new ArrayList<>(names(order));
        Collections.sort(sortedBefore);
        Collections.sort(sortedOrder);
        assertEquals(sortedBefore, sortedOrder);       // the same pictures, only shuffled
        assertTrue(WallpaperRotation.dailyOrder(Collections.emptyList(), 42L).isEmpty());
    }

    @Test
    public void dailyOrderVariesFromDayToDay() {
        List<File> images = new ArrayList<>();
        for (int i = 0; i < 10; i++) images.add(new File("wall-" + i + ".jpg"));
        Set<String> orders = new HashSet<>();
        for (long day = 0; day < 50; day++) {
            orders.add(names(WallpaperRotation.dailyOrder(images, day)).toString());
        }
        assertTrue(orders.size() >= 40);
    }

    @Test
    public void dailyPickWalksTheListLikeNext() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        Random random = new Random(1);
        // The day-shuffled list is walked in order, exactly like sequential; the Random is not consulted.
        assertEquals("b.jpg", WallpaperRotation.pick(images, "a.jpg", WallpaperRotation.ORDER_DAILY, random).getName());
        assertEquals("a.jpg", WallpaperRotation.pick(images, "c.jpg", WallpaperRotation.ORDER_DAILY, random).getName());
    }

    @Test
    public void dailyPickIgnoresTheRandom() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        assertEquals("c.jpg", WallpaperRotation.pick(images, "b.jpg", WallpaperRotation.ORDER_DAILY, new Random(1)).getName());
        assertEquals("c.jpg", WallpaperRotation.pick(images, "b.jpg", WallpaperRotation.ORDER_DAILY, new Random(999)).getName());
    }

    @Test
    public void findsTheFirstPictureWhenThePreviousOneIsGone() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals("a.jpg", WallpaperRotation.next(images, "deleted.jpg").getName());
    }

    @Test
    public void findMatchesByName() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals("b.jpg", WallpaperRotation.find(images, "b.jpg").getName());
        assertNull(WallpaperRotation.find(images, "c.jpg"));
    }

    @Test
    public void anOnTvPickReplacesTheRotation() {
        List<File> collection = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        File single = new File("wallpaper.jpg");
        // No choice, or a picture that has gone: the theme's rotation decides.
        assertNull(WallpaperRotation.chosen(collection, single, null));
        assertNull(WallpaperRotation.chosen(collection, single, "gone.jpg"));
        assertNull(WallpaperRotation.chosen(collection, null, "wallpaper.jpg"));
        // None: no wallpaper at all.
        assertTrue(WallpaperRotation.chosen(collection, single, WallpaperRotation.CHOICE_NONE).isEmpty());
        assertTrue(WallpaperRotation.chosen(Collections.emptyList(), null, WallpaperRotation.CHOICE_NONE).isEmpty());
        // A picked picture is the only one shown, so the rotation can't move past it.
        assertEquals(Collections.singletonList("b.jpg"), names(WallpaperRotation.chosen(collection, single, "b.jpg")));
        assertEquals(Collections.singletonList("wallpaper.jpg"),
                names(WallpaperRotation.chosen(collection, single, "wallpaper.jpg")));
        // Picking again simply replaces the earlier pick.
        assertEquals(Collections.singletonList("c.jpg"), names(WallpaperRotation.chosen(collection, single, "c.jpg")));
    }

    @Test
    public void homeRebuildsWhenTheSettingsRevisionMoved() {
        assertFalse(Config.settingsChanged(0, 0));
        assertFalse(Config.settingsChanged(3, 3));
        assertTrue(Config.settingsChanged(0, 1));
        assertTrue(Config.settingsChanged(4, 0));   // settings cleared
    }

    @Test
    public void dueOnlyWithRotationOnAndTheIntervalPassed() {
        long minute = 60_000L;
        assertFalse(WallpaperRotation.due(0, 10 * minute, 0));             // rotation off
        assertTrue(WallpaperRotation.due(0, 10 * minute, 5 * minute));      // never changed yet
        assertFalse(WallpaperRotation.due(8 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(5 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(20 * minute, 10 * minute, 5 * minute));   // clock went back
    }

    private static List<String> names(List<File> files) {
        String[] out = new String[files.size()];
        for (int i = 0; i < out.length; i++) out[i] = files.get(i).getName();
        return Arrays.asList(out);
    }
}
