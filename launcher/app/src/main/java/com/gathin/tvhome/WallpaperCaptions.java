package com.gathin.tvhome;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names for a wallpaper collection (wallpaper_captions): {@code wallpapers/captions.txt}, one
 * "filename<TAB>name<TAB>date" per line. The date column is optional: a line without it names the
 * picture and no date shows under the name. Blank lines, lines starting with # and lines without
 * a tab are skipped; a name is cut to {@link #MAX_LENGTH} characters and a date to
 * {@link #MAX_DATE_LENGTH}. No Android types, so it is tested on the JVM.
 */
final class WallpaperCaptions {
    static final String FILE_NAME = "captions.txt";
    static final int MAX_LENGTH = 80;
    static final int MAX_DATE_LENGTH = 24;

    /** A picture's caption, and the date it was taken when the file's third column carries one. */
    static final class Caption {
        final String name;
        final String date;

        Caption(String name, String date) {
            this.name = name;
            this.date = date;
        }
    }

    private WallpaperCaptions() { }

    static Map<String, Caption> parse(List<String> lines) {
        Map<String, Caption> names = new HashMap<>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String file = line.substring(0, tab).trim();
            String rest = line.substring(tab + 1).trim();
            int second = rest.indexOf('\t');
            String name = (second < 0 ? rest : rest.substring(0, second)).trim();
            String date = second < 0 ? null : rest.substring(second + 1).trim();
            if (file.isEmpty() || name.isEmpty()) continue;
            if (name.length() > MAX_LENGTH) name = name.substring(0, MAX_LENGTH).trim();
            if (date != null) {
                if (date.length() > MAX_DATE_LENGTH) date = date.substring(0, MAX_DATE_LENGTH).trim();
                if (date.isEmpty()) date = null;
            }
            names.put(file, new Caption(name, date));
        }
        return names;
    }

    /** The names in {@code folder}/captions.txt, or none when the file is missing or unreadable. */
    static Map<String, Caption> read(File folder) {
        File file = folder == null ? null : new File(folder, FILE_NAME);
        if (file == null || !file.isFile() || file.length() > 256 * 1024) return Collections.emptyMap();
        try {
            return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
