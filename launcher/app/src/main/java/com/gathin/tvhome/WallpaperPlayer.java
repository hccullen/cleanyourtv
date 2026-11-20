package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim in percent, baked into each picture once at decode instead of a full-screen shade view. */
    private final int configDim;
    /** wallpaper_night_dim in percent: the dim that replaces wallpaper_dim while it is dark outside; 0 is off. */
    private final int nightDim;
    /** grid_hide_on_top_bar lifted the night dim while the grid is slid away (setNightDimSuspended). */
    private boolean dimSuspended;
    /** The dim the picture on screen was baked with, so a change re-bakes only when the dim really moved. */
    private int bakedDim;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    /** The picture fading in on {@link #fader} while a cross-fade runs; null when none does. */
    private Bitmap fading;
    /** The picture a cross-fade is covering on the base view, recycled once the fade has settled. */
    private Bitmap retired;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name and date from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    /** wallpaper_caption_date: the shown picture's date, a smaller line under the caption; null when off. */
    private final TextView dateLine;
    private final Map<String, WallpaperCaptions.Caption> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** wallpaper_order "daily": the whole collection shuffled once per date, changing with the first tick of a new day. */
    private final boolean daily;
    /** Today's shuffle of the collection while order is "daily"; rebuilt when the day moves. */
    private List<File> dayOrder;
    private LocalDate orderDay;
    /** config wallpaper_crossfade_ms: how long a new picture fades in over the old one; 0 swaps at once. */
    private final int crossfadeMs;
    /** The second view a cross-fade draws over {@link #image}; null when crossfadeMs is 0. */
    private final ImageView fader;
    /** config.xml or theme latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        configDim = config.wallpaperDim;
        order = config.wallpaperOrder;
        daily = WallpaperRotation.ORDER_DAILY.equals(order);
        crossfadeMs = config.wallpaperCrossfadeMs;
        nightDim = config.wallpaperNightDim;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() && config.wallpaperNightDim <= 0 ? "" : config.latitude;
        longitude = moods.isEmpty() && config.wallpaperNightDim <= 0 ? "" : config.longitude;
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        dateLine = caption == null || !config.wallpaperCaptionDate ? null : makeDate(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fader = crossfadeMs > 0 ? makeFader(context, root) : null;
            showFirst();
        } else {
            fader = null;
        }
    }

    /** The second view a cross-fade draws over the base one: same fill, never focusable, transparent at rest. */
    private static ImageView makeFader(Context context, FrameLayout root) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setAlpha(0f);
        root.addView(view, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        int dim = dimNow();
        while (first != null) {
            Bitmap bitmap = decode(first, dim);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                bakedDim = dim;
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            drop(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        settle();
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** Whether the skip keys have a rotation to move: more than one playable picture while Home shows it. */
    boolean canSkip() {
        return active && !closed && files.size() > 1;
    }

    /**
     * The skip keys (wallpaper_next_keycodes / wallpaper_prev_keycodes): the next or previous picture
     * now, not at the next tick. A decode in flight answers nothing - the next press moves once it
     * lands, so a held key steps the collection at the pace the decoder keeps.
     */
    void skip(int direction) {
        if (!canSkip() || loading) return;
        List<File> playable = playable();
        if (playable.size() < 2) return;
        File target = direction < 0
                ? WallpaperRotation.previous(playable, displayed)
                : WallpaperRotation.pick(playable, displayed, order, random);
        if (target == null || target.getName().equals(displayed)) return;
        load(target);
    }

    /** Removes a picture the decoder could not read from every list the rotation picks from. */
    private void drop(File file) {
        files.remove(file);
        if (dayOrder != null) dayOrder.remove(file);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) {
            if (!daily) return files;
            // Today's shuffle: seeded by the date, so a restart mid-day resumes the same order.
            LocalDate today = LocalDate.now();
            if (orderDay == null || !today.equals(orderDay)) {
                orderDay = today;
                dayOrder = WallpaperRotation.dailyOrder(files, today.toEpochDay());
            }
            return dayOrder;
        }
        ensureSun();
        LocalDateTime now = LocalDateTime.now();
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    /** Today's sunrise and sunset, worked out once a day (wallpaper_moods and wallpaper_night_dim). */
    private void ensureSun() {
        LocalDate today = LocalDate.now();
        if (today.equals(sunDay)) return;
        sunDay = today;
        sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
    }

    /** Whether it is dark outside for wallpaper_night_dim: an hour after sunset to an hour before sunrise. */
    private boolean darkNow() {
        if (nightDim <= 0) return false;
        ensureSun();
        LocalDateTime now = LocalDateTime.now();
        return WallpaperRotation.darkOutside(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]);
    }

    /** The dim the next decode bakes in: the theme's wallpaper_dim, or the night one while it is dark. */
    private int dimNow() {
        return WallpaperRotation.dimAt(configDim, nightDim, darkNow() && !dimSuspended);
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        // wallpaper_night_dim: the dark starting or ending re-bakes the picture on screen at once,
        // cross-fading the new brightness instead of waiting for the next picture. This tick is the
        // re-bake's; the rotation keeps its own clock and moves at its next one.
        if (nightDim > 0 && !displayed.isEmpty()) {
            int dim = dimNow();
            if (dim != bakedDim) {
                File picture = WallpaperRotation.find(files, displayed);
                if (picture != null) {
                    rebake(picture, dim);
                    return;
                }
            }
        }
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        load(selected);
    }

    /** Decodes a picture off the UI thread and puts it on screen once ready; the interval starts over from it. */
    private void load(File next) {
        int dim = dimNow();
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next, dim);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    drop(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                bakedDim = dim;
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    /**
     * Re-decodes the picture on screen with a new dim - wallpaper_night_dim crossing into or out of
     * the dark, or the grid slide lifting it - and cross-fades over the old bake. The caption and the
     * rotation's own clock are left alone: the same picture shows, only its brightness moved.
     */
    private void rebake(File picture, int dim) {
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(picture, dim);
            root.post(() -> {
                loading = false;
                if (closed || !active || bitmap == null) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                bakedDim = dim;
                show(bitmap);
            });
        });
    }

    /**
     * grid_hide_on_top_bar: the grid sliding away leaves the photo alone on the screen, so the night
     * dim lifts with it (wallpaper_night_dim); the grid coming back re-dims if it is still dark.
     */
    void setNightDimSuspended(boolean suspended) {
        if (nightDim <= 0 || dimSuspended == suspended) return;
        dimSuspended = suspended;
        if (loading || displayed.isEmpty()) return;
        int dim = dimNow();
        if (dim == bakedDim) return;
        File picture = WallpaperRotation.find(files, displayed);
        if (picture != null) rebake(picture, dim);
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits a gap
     * below the buttons' row rather than flush against it; the offset is fixed, so a taller top bar (font scale,
     * an extra date line) eats into that gap — and it has to stay clear of the clock block and of the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        // Room for the shadow (6 dp of blur, 1 dp down) inside the view: Home's root, and the layer a fade draws
        // through, cut everything at the view's edges, which left a hard-edged dark box around the text. The
        // margins below take the padding back, so the text stays where it was.
        int pad = Math.round(8 * dp);
        text.setPadding(pad, pad, pad, pad);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp) + 2 * pad);
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(137 * dp) - pad;
        params.setMarginEnd(Math.round(62 * dp) - pad);
        root.addView(text, params);
        return text;
    }

    /** The caption's date line: the same shape, smaller and dimmer, a little under the caption. */
    private static TextView makeDate(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTextColor(0x99FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(132 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name and date, faded together across a change; a picture without a name hides both. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        WallpaperCaptions.Caption entry = names.get(displayed);
        String name = entry == null ? null : entry.name;
        String date = entry == null || entry.date == null ? "" : entry.date;
        caption.animate().cancel();
        if (dateLine != null) dateLine.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            if (dateLine != null) dateLine.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        boolean dated = !date.isEmpty();
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            if (dateLine != null) {
                dateLine.setText(date);
                dateLine.setAlpha(dated ? 1f : 0f);
            }
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
        if (dateLine != null) {
            dateLine.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                dateLine.setText(date);
                dateLine.animate().alpha(dated ? 1f : 0f).setDuration(350).start();
            }).start();
        }
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen, cross-fading over the previous one when configured, and releases the old one. */
    private void show(Bitmap bitmap) {
        settle();
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        if (fader != null && old != null && old != bitmap) {
            // The old picture stays on the base view while the new one fades in over it.
            retired = old;
            fading = bitmap;
            fader.setImageBitmap(bitmap);
            fader.setAlpha(0f);
            fader.animate().alpha(1f).setDuration(crossfadeMs).withEndAction(this::settle).start();
            return;
        }
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    /**
     * Ends a running cross-fade at once: the fading picture moves to the base view and the covered
     * one is released. Called when the fade completes, when the next picture is ready before it does,
     * and when the wallpaper is dropped for memory.
     */
    private void settle() {
        if (fader == null) return;
        fader.animate().cancel();
        if (fading == null) return;
        Bitmap settled = fading;
        fading = null;
        image.setImageBitmap(settled);
        fader.setImageDrawable(null);
        fader.setAlpha(0f);
        Bitmap covered = retired;
        retired = null;
        if (covered != null && covered != settled) image.postDelayed(covered::recycle, 500);
    }

    private Bitmap decode(File file, int dim) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dim > 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dim > 0) new Canvas(decoded).drawColor((dim * 255 / 100) << 24);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
