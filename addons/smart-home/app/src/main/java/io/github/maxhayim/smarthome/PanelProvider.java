package io.github.maxhayim.smarthome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The Home panel's rows (the launcher's panel_packages names this app; the authority rides in this
 * manifest's com.gathin.tvhome.panel meta-data). One query path, "lines", answers the now-playing
 * row: line1, line2, tone, art (the cover as a small JPEG, or null), art_id.
 *
 * Fetch on query, no service and no poller: a query serves the cache while it is fresh (20 s) and
 * otherwise reads Home Assistant once, synchronously, with short timeouts. A failed read serves the
 * stale cache, so a sleeping server costs nothing and shows nothing broken; with no cache there is
 * no row, and the launcher's panel simply is not there. The launcher re-queries when Home comes back
 * and once a minute while it is on screen, which is the only cadence this needs.
 */
public class PanelProvider extends ContentProvider {
    private static final String TAG = "PanelProvider";
    /** The one path the launcher asks for. */
    static final String PATH = "lines";
    /** The columns, in the order the launcher reads them (PanelPill.COLUMNS). */
    static final String[] COLUMNS = {"line1", "line2", "line3", "tone", "art", "art_id"};
    /** The cache answers this long without touching the server. */
    private static final long FRESH_MS = 20_000;
    /** A failed art fetch is retried at most this often: one broken URL must not cost its timeout every query. */
    private static final long ART_RETRY_MS = 60_000;
    /** Per-request timeouts for the panel's fetches: a slow Home Assistant must not hold the reader. */
    private static final int TIMEOUT_MS = 3000;
    /** The cover art is decoded and re-encoded at most this wide. */
    private static final int ART_SIZE = 256;

    /** The last good row, guarded by itself; art is kept when only the text changed and vice versa. */
    private static final class Cache {
        long at;
        String line1;
        String line2;
        String line3;
        int tone;
        byte[] art;
        String artId;
        /** What the art was fetched for, url plus title: a new track refetches, the same one does not. */
        String artKey;
        /** When the last art fetch failed: the retry clock for {@link #ART_RETRY_MS}. */
        long artFailedAt;
    }

    private static final Cache CACHE = new Cache();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!PATH.equals(uri.getLastPathSegment())) return null;
        Row row = row();
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (row != null) {
            out.addRow(new Object[]{row.line1, row.line2, row.line3, row.tone, row.art, row.artId});
        }
        return out;
    }

    /** The current row, from the cache while it is fresh, else from one read of Home Assistant. */
    private Row row() {
        android.content.Context ctx = getContext();
        Config config = ctx == null ? null : Config.load(ctx);
        if (config == null || !config.complete()) return cached();
        synchronized (CACHE) {
            if (CACHE.at > 0 && System.currentTimeMillis() - CACHE.at < FRESH_MS) return current();
            HaClient client = new HaClient(config.url, config.token, TIMEOUT_MS);
            // The art fetch gets the full timeout: the picture comes through HA's proxy.
            HaClient slow = new HaClient(config.url, config.token);
            HaClient.Reply states = client.get("/api/states");
            if (!states.ok()) return cached();   // a sleeping server shows its last row, or nothing
            try {
                NowPlaying.Player picked = NowPlaying.pick(players(states.body),
                        config.panelPreferred, NowPlaying.parseFallback(config.panelFallback));
                if (picked == null) {
                    CACHE.at = System.currentTimeMillis();
                    CACHE.line1 = null;
                    CACHE.line2 = null;
                    CACHE.line3 = null;
                    CACHE.art = null;
                    CACHE.artId = "";
                    return null;
                }
                CACHE.at = System.currentTimeMillis();
                CACHE.line1 = NowPlaying.line1(picked);
                CACHE.line2 = NowPlaying.line2(picked);
                CACHE.line3 = NowPlaying.line3(picked);
                CACHE.tone = 0;
                String key = picked.picture + "|" + CACHE.line1;
                // Refetch the art when the track changed, and also when the last fetch came up empty —
                // but a repeated failure retries at most once a minute, so one broken URL never turns
                // every query into its full timeout.
                boolean needArt = !key.equals(CACHE.artKey)
                        || (CACHE.art == null && !picked.picture.isEmpty()
                            && System.currentTimeMillis() - CACHE.artFailedAt >= ART_RETRY_MS);
                if (needArt) {
                    CACHE.artKey = key;
                    CACHE.art = art(slow, picked.picture);
                    if (CACHE.art == null && !picked.picture.isEmpty()) CACHE.artFailedAt = System.currentTimeMillis();
                    CACHE.artId = CACHE.art == null ? "" : digest(key);
                }
                return current();
            } catch (RuntimeException e) {
                Log.w(TAG, "panel read failed", e);
                return cached();
            }
        }
    }

    private static Row current() {
        if (CACHE.line1 == null) return null;
        return new Row(CACHE.line1, CACHE.line2, CACHE.line3, CACHE.tone, CACHE.art, CACHE.artId);
    }

    private static Row cached() {
        synchronized (CACHE) {
            return current();
        }
    }

    /** The media_player entities in a /api/states answer, in the order the server reported. */
    static List<NowPlaying.Player> players(String json) {
        List<NowPlaying.Player> out = new ArrayList<>();
        JSONArray all;
        try {
            all = new JSONArray(json);
        } catch (org.json.JSONException e) {
            return out;
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            NowPlaying.Player p = NowPlaying.player(o);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * The cover art: fetched from the entity_picture path (absolute URLs pass through), decoded,
     * downscaled to at most 256 px and re-encoded as a JPEG. Null when there is no picture or any
     * step fails; the row then shows text only. The caller hands in a client with the full timeout:
     * artwork comes through Home Assistant's proxy, which can be slower than the states call.
     */
    static byte[] art(HaClient client, String picture) {
        if (picture == null || picture.isEmpty()) return null;
        HaClient.ReplyBytes reply = client.getBytes(picture);
        if (!reply.ok() || reply.body.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(reply.body, 0, reply.body.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample(bounds.outWidth, bounds.outHeight);
            Bitmap decoded = BitmapFactory.decodeByteArray(reply.body, 0, reply.body.length, opts);
            if (decoded == null) return null;
            Bitmap scaled = decoded;
            if (decoded.getWidth() > ART_SIZE || decoded.getHeight() > ART_SIZE) {
                float scale = Math.min((float) ART_SIZE / decoded.getWidth(), (float) ART_SIZE / decoded.getHeight());
                scaled = Bitmap.createScaledBitmap(decoded,
                        Math.max(1, Math.round(decoded.getWidth() * scale)),
                        Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                // The power-of-two decode is the big one; drop it as soon as the small copy exists.
                if (scaled != decoded) decoded.recycle();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null;
            byte[] bytes = out.toByteArray();
            return bytes.length > 256 * 1024 ? null : bytes;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The power of two that lands the larger side between ART_SIZE and twice that, ready for the exact scale. */
    static int sample(int width, int height) {
        int max = Math.max(width, height);
        int size = 1;
        while (max / (size * 2) >= ART_SIZE) size *= 2;
        return size;
    }

    /** Twelve hex digits of SHA-1: the launcher re-decodes only when this changes. */
    static String digest(String of) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(of.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", hash[i]));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** One panel row: what the cursor carries, and what the launcher's tests see. */
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

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
