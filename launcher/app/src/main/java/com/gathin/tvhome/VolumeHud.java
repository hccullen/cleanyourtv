package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * volume_hud (config.xml, a local patch on top of this repo): a slim glass capsule at the top right
 * with the volume level as a thin bar and a percent, while Volume Up/Down is pressed, hiding a
 * moment after the last change. Firmware that delegates its sound to an amplifier over HDMI ARC
 * draws no volume UI of its own. Two triggers show it: the key services' Volume Up/Down (instant),
 * and the system's volume-changed broadcast (catches every change, from the remote, the
 * amplifier's own app or an injected key, and carries the level the amplifier just applied). The
 * percent is read from AudioManager, which on ARC firmware follows the amplifier's CEC-reported
 * volume. Drawn as TYPE_ACCESSIBILITY_OVERLAY, which needs a window context on Android 12 (see
 * {@link #serviceWindowContext}). Nothing here may throw: a volume pill must never take Home
 * down, so every entry point catches and logs instead.
 */
final class VolumeHud {
    private static final String TAG = "VolumeHud";
    /** How long the pill stays up after the last change, and how often the level is re-read meanwhile. */
    private static final long HIDE_AFTER_MS = 1600, RECHECK_MS = 120;
    /** Hidden action the audio service broadcasts on every volume change. */
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Added once and reused; null whenever nothing is on screen. */
    private static View pill;
    private static TextView label;
    private static View fill;
    private static int trackWidth;
    private static long hideAt;
    /** volume_hud, read once from config.xml. */
    private static Boolean enabled;
    /** The volume-changed receiver, on the application context so any service can remove it. */
    private static BroadcastReceiver receiver;
    private static Context receiverContext;
    /** The window manager the pill is added with, made once. */
    private static WindowManager windows;
    /** Set when every way of adding the overlay was refused: stop trying, until the next process. */
    private static boolean gaveUp;

    private VolumeHud() {
    }

    /** Key path: called from the key services on Volume Up/Down; every other key is left alone. */
    static void show(Context context, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return;
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return;
        Log.i(TAG, "volume key " + event.getKeyCode() + " reached the key service");
        try {
            nudge(context);
        } catch (RuntimeException any) {
            Log.i(TAG, "hud failed: " + any);   // a volume pill must never take Home down
        }
    }

    /** A volume change from anywhere: put the pill up with the level that just landed. */
    static void nudge(Context context) {
        if (enabled == null) enabled = context.getResources().getBoolean(R.bool.volume_hud);
        if (!enabled) return;
        if (pill == null && !gaveUp) add(context);
        if (pill == null) return;   // this firmware refused the overlay window
        UI.removeCallbacks(HIDE);
        hideAt = SystemClock.uptimeMillis() + HIDE_AFTER_MS;
        refresh(context);
        UI.postDelayed(HIDE, HIDE_AFTER_MS);
        UI.removeCallbacks(RECHECK);
        RECHECK.run();
    }

    /** Listen for volume changes, so the pill follows them even when the key path does not fire.
     *  The context kept is the service: its window manager carries the accessibility overlay token. */
    static void listen(Context context) {
        if (receiver != null) return;
        receiverContext = context;
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "volume-changed broadcast");
                    try {
                        nudge(receiverContext);
                    } catch (RuntimeException any) {
                        Log.i(TAG, "hud failed: " + any);   // an uncaught throw here kills the launcher
                    }
                }
            };
            receiverContext.registerReceiver(receiver, new IntentFilter(VOLUME_CHANGED));
            Log.i(TAG, "listening for " + VOLUME_CHANGED);
        } catch (RuntimeException refused) {
            receiver = null;   // no broadcast on this firmware; the key path still shows the pill
            Log.i(TAG, "broadcast refused: " + refused);
        }
    }

    /** Take the pill down and stop listening; the service that drew it is going away. */
    static void dismiss() {
        UI.removeCallbacks(HIDE);
        UI.removeCallbacks(RECHECK);
        remove();
        if (receiver != null && receiverContext != null) {
            try {
                receiverContext.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
        }
        receiver = null;
        receiverContext = null;
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
        label.setText(muted ? "MUTED" : Math.round(level * 100f / max) + "%");
        FrameLayout.LayoutParams fillParams = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fillParams.width = muted || max <= 0 ? 0 : Math.round(trackWidth * level / (float) max);
        fill.setLayoutParams(fillParams);
    }

    /**
     * The window manager the pill is added with, trying the candidates in order and keeping the first
     * whose addView succeeds. TYPE_ACCESSIBILITY_OVERLAY belongs to the accessibility service: its
     * own window manager carries the token (the canonical path), a window context of it covers
     * Android 12's stricter checks, and a display-bound window context is the last resort for a
     * service context without a display. Nothing here throws.
     */
    private static WindowManager plainWindowManager(Context context) {
        return (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private static WindowManager serviceWindowContext(Context context) {
        try {
            return (WindowManager) context.createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .getSystemService(Context.WINDOW_SERVICE);
        } catch (RuntimeException noDisplay) {
            Log.i(TAG, "no window context on this context: " + noDisplay);
            return null;
        }
    }

    private static WindowManager displayBoundWindowContext(Context context) {
        try {
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displays != null ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) return null;
            return (WindowManager) context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .getSystemService(Context.WINDOW_SERVICE);
        } catch (RuntimeException neither) {
            Log.i(TAG, "no display-bound window context either: " + neither);
            return null;
        }
    }

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
            Typeface outfit = ResourcesCompat.getFont(context, R.font.outfit_light);
            if (outfit != null) label.setTypeface(outfit);
        } catch (RuntimeException noFont) {
            // Outfit is this launcher's own resource; if a build drops it the label falls back to the default
        }

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(glass);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(barTrack, new LinearLayout.LayoutParams(trackWidth, barH));
        box.addView(label, new LinearLayout.LayoutParams(numberWidth, LinearLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(pillW, pillH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = screenW / 40;
        lp.y = screenH / 90;   // in the letterbox bar of a scope film, clear of the picture
        WindowManager[] candidates = Build.VERSION.SDK_INT >= 30
                ? new WindowManager[]{plainWindowManager(context), serviceWindowContext(context),
                        displayBoundWindowContext(context)}
                : new WindowManager[]{plainWindowManager(context)};
        for (WindowManager candidate : candidates) {
            if (candidate == null) continue;
            try {
                candidate.addView(box, lp);
                windows = candidate;
                pill = box;
                Log.i(TAG, "pill added " + pillW + "x" + pillH + " at " + lp.x + "," + lp.y);
                return;
            } catch (RuntimeException refused) {
                Log.i(TAG, "overlay refused: " + refused);   // try the next way in
            }
        }
        pill = null;   // no volume pill on this firmware; the keys keep working as before
        gaveUp = true;
        Log.i(TAG, "no window manager accepted the overlay");
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
