package io.github.hccullen.volumehud;

/** The pill's numbers, pure so the JVM tests can hold them. */
final class Levels {
    private Levels() {
    }

    /** The text at the pill's end: MUTED, or the level as a percent of the range. */
    static String label(boolean muted, int level, int max) {
        if (muted) return "MUTED";
        if (max <= 0) return "?";   // no range to read: never divide by it
        return Math.round(level * 100f / max) + "%";
    }

    /** The bar's width in the track: nothing while muted or without a range, else the level's share. */
    static int width(int trackWidth, int level, int max, boolean muted) {
        if (muted || max <= 0) return 0;
        return Math.round(trackWidth * level / (float) max);
    }
}
