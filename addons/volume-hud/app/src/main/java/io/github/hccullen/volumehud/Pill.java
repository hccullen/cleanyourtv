package io.github.hccullen.volumehud;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A slim glass capsule at the top right with the volume level as a thin bar and a percent, shown
 * for a moment whenever the volume changes, over every app. Firmware that delegates its sound to
 * an amplifier over HDMI ARC draws no volume UI of its own; the percent is read from AudioManager,
 * which on ARC firmware follows the amplifier's CEC-reported volume. The trigger is the system's
 * volume-changed broadcast ({@link VolumeChangedReceiver}), so the pill appears when the level has
 * actually landed - a moment later than the key press, with the true level. Drawn as
 * TYPE_APPLICATION_OVERLAY, which needs the "display over other apps" permission. Nothing here may
 * throw: a volume pill must never disturb whatever is playing, so every entry point catches and
 * logs instead.
 */
final class Pill {
    private static final String TAG = "VolumePill";
    /** How long the pill stays up after the last change, and how often the level is re-read meanwhile. */
    private static final long HIDE_AFTER_MS = 1600, RECHECK_MS = 120;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Added once and reused; null whenever nothing is on screen. */
    private static View pill;
    private static TextView label;
    private static View fill;
    private static int trackWidth;
    private static long hideAt;
    /** The window manager the pill is added with, made once. */
    private static WindowManager windows;
    /** Set when the overlay window was refused: stop trying, until the next process. */
    private static boolean gaveUp;

    private Pill() {
    }

    /** A volume change from anywhere: put the pill up with the level that just landed. */
    static void nudge(Context context) {
        try {
            if (!Prefs.enabled(context)) {
                remove();
                return;
            }
            if (pill == null && !gaveUp) add(context);
            if (pill == null) return;   // the overlay window was refused; nothing to show
            UI.removeCallbacks(HIDE);
            hideAt = SystemClock.uptimeMillis() + HIDE_AFTER_MS;
            refresh(context);
            UI.postDelayed(HIDE, HIDE_AFTER_MS);
            UI.removeCallbacks(RECHECK);
            RECHECK.run();
        } catch (RuntimeException any) {
            Log.i(TAG, "pill failed: " + any);   // a volume pill must never disturb what is playing
        }
    }

    /** Take the pill down at once (the card's switch turning it off). */
    static void discard() {
        try {
            remove();
        } catch (RuntimeException any) {
            Log.i(TAG, "discard failed: " + any);
        }
    }

    /** Keeps the bar following the level while it settles (the amplifier answers slightly late). */
    private static final Runnable RECHECK = new Runnable() {
        @Override
        public void run() {
            if (pill == null) return;
            try {
                refresh(pill.getContext());
            } catch (RuntimeException any) {
                Log.i(TAG, "refresh failed: " + any);
                return;
            }
            if (SystemClock.uptimeMillis() < hideAt) UI.postDelayed(this, RECHECK_MS);
        }
    };

    private static final Runnable HIDE = new Runnable() {
        @Override
        public void run() {
            remove();
        }
    };

    /** The level the pill shows: the stream the ARC device plays on, which tracks the amplifier. */
    private static void refresh(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || label == null || fill == null) return;
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        label.setText(Levels.label(muted, level, max));
        FrameLayout.LayoutParams fillParams = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fillParams.width = Levels.width(trackWidth, level, max, muted);
        fill.setLayoutParams(fillParams);
    }

    /**
     * The pill, built once and added with the one window manager that can carry an application
     * overlay: the app's own, from the context the broadcast was delivered to. Unlike an
     * accessibility overlay this needs no service token, so there is no chain of candidate window
     * managers - one way in. Nothing here throws; a refusal only ends the pill.
     */
    private static void add(Context context) {
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int pillW = screenW / 6, pillH = screenW / 40, pad = pillH / 4, barH = pillH / 6;
        int numberWidth = pillW / 5;
        trackWidth = pillW - 2 * pad - numberWidth - pad;
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x99141418);
        glass.setCornerRadius(pillH / 2f);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x2EFFFFFF);
        track.setCornerRadius(barH / 2f);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(0xE6FFFFFF);
        bar.setCornerRadius(barH / 2f);

        fill = new View(context);
        fill.setBackground(bar);
        FrameLayout barTrack = new FrameLayout(context);
        barTrack.setBackground(track);
        barTrack.addView(fill, new FrameLayout.LayoutParams(0, barH));

        label = new TextView(context);
        label.setTextColor(0xD9FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, pillH / 2.4f);   // pixel units: sp would scale with the board's density and overflow the pill
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.END);
        try {
            label.setTypeface(context.getResources().getFont(R.font.outfit_light));
        } catch (RuntimeException noFont) {
            // Outfit is this add-on's own resource; without it the label falls back to the default
        }

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(glass);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(barTrack, new LinearLayout.LayoutParams(trackWidth, barH));
        box.addView(label, new LinearLayout.LayoutParams(numberWidth, LinearLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(pillW, pillH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = screenW / 40;
        lp.y = screenH / 90;   // in the letterbox bar of a scope film, clear of the picture
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        try {
            windows.addView(box, lp);
            pill = box;
        } catch (RuntimeException refused) {
            pill = null;
            gaveUp = true;   // usually the missing "display over other apps"; the card's screen explains
            Log.i(TAG, "overlay refused: " + refused);
        }
    }

    private static void remove() {
        if (pill == null) return;
        try {
            if (windows != null) windows.removeView(pill);
        } catch (RuntimeException alreadyGone) {
        }
        pill = null;
    }
}
