package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The Home panel pill (config.xml home_panel_pill, panel_packages): the now-playing line an add-on
 * serves, left-aligned under the clock where the race panel sits (the panel stacks below it when a
 * race weekend is showing). Each named package publishes rows through a read-only provider whose
 * authority rides in the package's com.gathin.tvhome.panel meta-data, the contract the game_day
 * add-ons follow (GameDay); naming the package is the owner's opt-in, and the provider must belong to
 * the package named, so another app cannot claim the authority.
 *
 * No polling and no network: the pill is read when Home comes back and once a minute with the clock
 * (the add-on fetches on query and caches), and nothing runs while Home is off screen. The launcher
 * stays offline; the add-on's cover art arrives as bytes in the row. Everything from a provider is
 * bounded here, and a provider that is gone, slow or broken costs its row, never the home screen.
 *
 * A row reads: line1 the title, line2 the artist and line3 the speaker, which the launcher joins as
 * "Artist · Speaker" with the speaker dimmed; tone an optional colour for the title; art the cover as
 * bytes, with art_id gating when it is re-decoded.
 */
final class PanelPill {
    static final String META = "com.gathin.tvhome.panel";
    /** The columns, in the order the panel contract serves them (the smart-home add-on's PanelProvider). */
    static final String[] COLUMNS = {"line1", "line2", "line3", "tone", "art", "art_id"};
    /** At most this many rows on screen, so several add-ons cannot crowd the corner. */
    static final int MAX_ROWS = 3;
    /** A longer line is cut: the corner stays a caption, never an essay. */
    static final int MAX_LINE = 80;
    /** A larger blob is dropped, art and all: the row shows text only. */
    static final int MAX_ART = 256 * 1024;
    /** The decoded cover: at most this many pixels on a side, in the cheap 16-bit config. */
    private static final int ART_PX = 128;
    /** The text column is much narrower than the caption's 520 dp, and longer text marquees. */
    private static final int TEXT_DP = 260;

    /** One row as the launcher renders it, already bounded. */
    static final class Row {
        final String line1;
        final String line2;
        final String line3;
        final int tone;
        final byte[] art;
        final String artId;

        Row(String line1, String line2, String line3, int tone, byte[] art, String artId) {
            this.line1 = line1;
            this.line2 = line2;
            this.line3 = line3;
            this.tone = tone;
            this.art = art;
            this.artId = artId;
        }
    }

    private static final class Source {
        final String pkg;
        final String authority;
        final String uri;

        Source(String pkg, String authority) {
            this.pkg = pkg;
            this.authority = authority;
            this.uri = new android.net.Uri.Builder()
                    .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                    .authority(authority).appendPath("lines").build().toString();
        }
    }

    private final Context ctx;
    private final ViewGroup root;
    private final View racePanel;
    private final Handler main;
    private final List<Source> sources;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** The last answer, kept so a focus change can re-show it without a re-read. */
    private List<Row> rows = Collections.emptyList();
    /** Focus on the top bar: the cog, the wallpaper alone on screen — the panel steps aside. */
    private boolean topBarHidden;
    /** What is on screen: the signature it was built from. An unchanged minute costs nothing. */
    private String shown = "";
    private LinearLayout panel;
    private boolean stopped = true;

    private PanelPill(Context ctx, ViewGroup root, View racePanel, Handler main, List<Source> sources) {
        this.ctx = ctx;
        this.root = root;
        this.racePanel = racePanel;
        this.main = main;
        this.sources = sources;
    }

    /**
     * Null when the key is off or no named package publishes a panel. A package that is not
     * installed, names no provider, or points at another app's provider is skipped without a word.
     */
    static PanelPill create(Context ctx, Config cfg, ViewGroup root, View racePanel, Handler main) {
        if (!cfg.homePanelPill || cfg.panelPackages.isEmpty()) return null;
        List<Source> sources = new ArrayList<>();
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String pkg : cfg.panelPackages) {
            String name = pkg.trim();
            if (name.isEmpty()) continue;
            try {
                android.content.pm.ApplicationInfo info = pm.getApplicationInfo(name,
                        android.content.pm.PackageManager.GET_META_DATA);
                String authority = info.metaData == null ? null : info.metaData.getString(META);
                if (authority == null || authority.trim().isEmpty()) continue;
                // The provider must be the named package's own, not another app's that claims the authority.
                android.content.pm.ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
                if (provider == null || !name.equals(provider.packageName)) continue;
                sources.add(new Source(name, authority.trim()));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // Not installed: the pill shows nothing from it, and everything else keeps working.
            } catch (RuntimeException e) {
                // A package that cannot be read at all costs itself, never the home screen.
            }
        }
        return sources.isEmpty() ? null : new PanelPill(ctx, root, racePanel, main, sources);
    }

    /** Home is on screen: read now, and let the minute tick keep it current. */
    void start() {
        stopped = false;
        read();
    }

    /** Home is off screen: an in-flight read is dropped, nothing applies until it is back. */
    void stop() {
        stopped = true;
    }

    /**
     * Focus moved to or from the top bar (the cog; with grid_hide_on_top_bar the wallpaper alone on
     * screen). The panel steps aside while the bar has focus and comes straight back when a tile
     * does, from the rows it already has — no re-read.
     */
    void setTopBarHidden(boolean hidden) {
        if (topBarHidden == hidden) return;
        topBarHidden = hidden;
        render();   // the focus callback runs on the main thread
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    /** One query per source off the main thread (each provider lives in its add-on's process). */
    void read() {
        if (stopped) return;
        try {
            io.execute(() -> {
                List<Row> fresh = new ArrayList<>();
                for (Source source : sources) {
                    try (Cursor c = ctx.getContentResolver().query(android.net.Uri.parse(source.uri),
                            COLUMNS, null, null, null)) {
                        if (c == null) continue;
                        while (c.moveToNext() && fresh.size() < MAX_ROWS) {
                            Row row = row(c);
                            if (row != null) fresh.add(row);
                        }
                    } catch (RuntimeException gone) {
                        // Uninstalled or crashed mid-query: the other sources carry on.
                    }
                }
                main.post(() -> apply(fresh));
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** One cursor row, bounded; null when it has nothing to say. */
    static Row row(Cursor c) {
        String line1 = bounded(c.getString(0));
        if (line1 == null) return null;
        String line2 = bounded(c.getString(1));
        String line3 = bounded(c.getString(2));
        int tone = c.getInt(3);
        byte[] art = c.getBlob(4);
        if (art != null && art.length > MAX_ART) art = null;
        String artId = c.getString(5);
        if (artId == null) artId = "";
        return new Row(line1, line2 == null ? "" : line2, line3 == null ? "" : line3, tone, art, artId);
    }

    /** Trimmed, capped, and empty-means-absent: the corner stays a caption. */
    static String bounded(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_LINE ? t.substring(0, MAX_LINE) : t;
    }

    /**
     * A provider's rows as Rows, the JVM-testable half of {@link #row}: a row without a big line is
     * dropped, everything else is trimmed, capped and passed through as it came.
     */
    static List<Row> fromRows(List<Object[]> all) {
        List<Row> out = new ArrayList<>();
        for (Object[] r : all) {
            if (r == null || r.length < 6) continue;
            String line1 = bounded(r[0] instanceof String ? (String) r[0] : null);
            if (line1 == null) continue;
            String line2 = bounded(r[1] instanceof String ? (String) r[1] : null);
            String line3 = bounded(r[2] instanceof String ? (String) r[2] : null);
            int tone = r[3] instanceof Number ? ((Number) r[3]).intValue() : 0;
            byte[] art = r[4] instanceof byte[] ? (byte[]) r[4] : null;
            if (art != null && art.length > MAX_ART) art = null;
            String artId = r[5] instanceof String ? (String) r[5] : "";
            out.add(new Row(line1, line2 == null ? "" : line2, line3 == null ? "" : line3,
                    tone, art, artId == null ? "" : artId));
            if (out.size() == MAX_ROWS) break;
        }
        return out;
    }

    /** What a row decodes to: null when there is no art or it will not decode. */
    static Bitmap art(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            int size = 1;
            while (bounds.outWidth / (size * 2) >= ART_PX && bounds.outHeight / (size * 2) >= ART_PX) size *= 2;
            opts.inSampleSize = size;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What is on screen, per row: an unchanged answer never touches a view. */
    private static String signature(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        for (Row r : rows) b.append(r.line1).append('\u0001').append(r.line2).append('\u0001')
                .append(r.line3).append('\u0001').append(r.tone).append('\u0001')
                .append(r.artId).append('\u0002');
        return b.toString();
    }

    /** The rows on screen, rebuilt only when the answer changed. */
    private void apply(List<Row> fresh) {
        if (stopped) return;
        reflow();
        String sig = signature(fresh);
        if (!sig.equals(shown)) {
            shown = sig;
            rows = fresh;
            rebuild();
        }
        render();
    }

    /** Puts the current rows' views in the panel, dropping whatever was there. */
    private void rebuild() {
        if (panel == null) return;
        panel.removeAllViews();
        float dp = ctx.getResources().getDisplayMetrics().density;
        for (Row r : rows) panel.addView(rowView(r, dp));
    }

    /** Shows or hides the panel as the rows and the focus decide, fading between the states. */
    private void render() {
        boolean show = !rows.isEmpty() && !topBarHidden;
        if (!show) {
            if (panel != null && panel.getVisibility() != View.GONE) {
                panel.animate().alpha(0f).setDuration(250).withEndAction(() -> panel.setVisibility(View.GONE)).start();
            }
            return;
        }
        if (panel == null) panel = buildPanel();
        if (panel.getChildCount() != rows.size()) rebuild();
        boolean wasHidden = panel.getVisibility() == View.GONE || panel.getAlpha() < 1f;
        panel.setVisibility(View.VISIBLE);
        if (wasHidden) {
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(250).start();
        }
    }

    /**
     * Left-aligned under the clock, a little below it; while the race panel is showing, this panel
     * slides below it instead of over it. Runs before the signature check, so a race weekend
     * appearing or leaving reflows the panel even when the playing row did not change.
     */
    private void reflow() {
        if (panel == null) return;
        float dp = ctx.getResources().getDisplayMetrics().density;
        int top = Math.round(130 * dp);
        if (racePanel != null && racePanel.getVisibility() == View.VISIBLE && racePanel.getHeight() > 0) {
            top += racePanel.getHeight() + Math.round(10 * dp);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (params.topMargin != top) {
            params.topMargin = top;
            panel.setLayoutParams(params);
        }
    }

    /** The panel column: left under the clock, left-aligned text. */
    private LinearLayout buildPanel() {
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFocusable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        params.topMargin = Math.round(130 * dp);
        params.setMarginStart(Math.round(60 * dp));
        panel.setAlpha(0f);
        panel.setVisibility(View.GONE);
        root.addView(panel, params);
        return panel;
    }

    /** One row: the cover, then the title with "Artist · Speaker" (speaker dimmed) under it. */
    private LinearLayout rowView(Row r, float dp) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);   // the text centres against the cover's height
        int tone = r.tone == 0 ? 0xD9FFFFFF : r.tone;

        if (r.artId != null && !r.artId.isEmpty() && r.art != null) {
            ImageView art = new ImageView(ctx);
            Bitmap bitmap = art(r.art);
            if (bitmap != null) {
                GradientDrawable plate = new GradientDrawable();
                plate.setColor(0xFF14161B);
                plate.setCornerRadius(8 * dp);
                art.setBackground(plate);
                art.setClipToOutline(true);
                art.setImageBitmap(bitmap);
                LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(
                        Math.round(48 * dp), Math.round(48 * dp));
                artParams.setMarginEnd(Math.round(10 * dp));
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(art, artParams);
            }
        }

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView line1 = new TextView(ctx);
        style(line1, 16, tone);
        line1.setText(r.line1);
        marquee(line1);
        line1.setContentDescription(r.line1 + secondLineDescription(r));
        text.addView(line1);
        if (!r.line2.isEmpty() || !r.line3.isEmpty()) {
            TextView line2 = new TextView(ctx);
            style(line2, 13, 0xB3FFFFFF);
            line2.setText(secondLine(r));
            marquee(line2);
            text.addView(line2);
        }
        row.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = Math.round(6 * dp);
        row.setLayoutParams(rowParams);
        return row;
    }

    /** "Artist · Speaker": the artist in the line's colour, the dot and the speaker dimmed. */
    private static CharSequence secondLine(Row r) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!r.line2.isEmpty()) {
            b.append(r.line2);
            b.setSpan(new ForegroundColorSpan(0xB3FFFFFF), 0, r.line2.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!r.line2.isEmpty() && !r.line3.isEmpty()) b.append(" \u00B7 ");
        if (!r.line3.isEmpty()) {
            int start = b.length();
            b.append(r.line3);
            b.setSpan(new ForegroundColorSpan(0x59FFFFFF), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private static String secondLineDescription(Row r) {
        if (r.line2.isEmpty() && r.line3.isEmpty()) return "";
        if (r.line2.isEmpty()) return ", " + r.line3;
        if (r.line3.isEmpty()) return ", " + r.line2;
        return ", " + r.line2 + ", " + r.line3;
    }

    /** Marquee without focus: the view only has to be selected for the text to keep walking. */
    private static void marquee(TextView text) {
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setSelected(true);
    }

    /** The caption's type: Outfit, a soft shadow, one line, cut at the panel's narrower width. */
    private void style(TextView text, int sp, int colour) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(colour);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(TEXT_DP * dp));
        text.setFocusable(false);
        try {
            text.setTypeface(UiFont.get(ctx, R.string.font_regular), Typeface.NORMAL);
        } catch (Exception ignored) {
            // the system font is fine
        }
    }
}
