package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class WallpaperRotation {
    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";
    /** The whole collection shuffled once per date: the order is stable across restarts and changes each day. */
    static final String ORDER_DAILY = "daily";

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order) || ORDER_DAILY.equals(order);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    /** The on-TV choice (Launcher settings, Wallpaper) that turns the wallpaper off. */
    static final String CHOICE_NONE = "none";

    /**
     * What an on-TV wallpaper choice puts on Home in place of the theme's rotation: null when the theme decides
     * (no choice, or a picture that is no longer there), an empty list for {@link #CHOICE_NONE}, or the one picture
     * chosen from the theme's collection or its single {@code single} wallpaper.
     */
    static List<File> chosen(List<File> collection, File single, String choice) {
        if (choice == null) return null;
        if (CHOICE_NONE.equals(choice)) return Collections.emptyList();
        File picked = find(collection, choice);
        if (picked == null && single != null && choice.equals(single.getName())) picked = single;
        return picked == null ? null : Collections.singletonList(picked);
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /**
     * The picture before the named one, wrapping to the last at the start. The skip buttons and the
     * rewind key walk the same list forward rotation walks, whatever the order is. An unknown name
     * starts at the first picture, as {@link #next} does.
     */
    static File previous(List<File> images, String name) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) {
                return images.get((i - 1 + images.size()) % images.size());
            }
        }
        return images.get(0);
    }

    /**
     * The collection in today's order for {@link #ORDER_DAILY}: shuffled with the date as the seed,
     * so every player the same day walks the same order and the next day shuffles it again. The
     * input list is left alone; the caller keeps its master copy.
     */
    static List<File> dailyOrder(List<File> images, long day) {
        List<File> order = new ArrayList<>(images);
        Collections.shuffle(order, new Random(day));
        return order;
    }

    /**
     * Whether it is dark outside (wallpaper_night_dim): from an hour after sunset to an hour before
     * sunrise, the twilight either side of the sun's times. The hour is about how long civil twilight
     * lasts at mid-latitude Europe; without the sun's times (no latitude and longitude in config.xml)
     * the defaults are sunrise 07:00 and sunset 18:30.
     */
    static boolean darkOutside(int minutesOfDay, int sunrise, int sunset) {
        return minutesOfDay >= sunset + 60 || minutesOfDay < sunrise - 60;
    }

    /**
     * The dim a picture decodes with (wallpaper_dim and wallpaper_night_dim): the night dim replaces
     * the plain one while it is dark, never stacks on it.
     */
    static int dimAt(int dim, int nightDim, boolean dark) {
        return dark && nightDim > 0 ? Math.max(dim, nightDim) : dim;
    }

    /** Picks the next picture for {@code order}; an unknown order keeps the historical shuffle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        // The daily player feeds the day-shuffled list in here, so it walks that list as sequential.
        return ORDER_SEQUENTIAL.equals(order) || ORDER_DAILY.equals(order)
                ? next(images, previous) : pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        // The minute tick is a Handler postDelayed, so it drifts by tens of milliseconds and often lands a few
        // hundred short of the interval. Compared exactly, that pass is skipped and the picture waits a whole
        // extra interval - a one-minute collection then changes every two. Two seconds of slack absorbs the
        // drift without letting a genuinely early call advance the picture.
        long slack = Math.min(2_000L, interval / 10);
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval - slack);
    }
}
