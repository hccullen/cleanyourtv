package io.github.hccullen.volumehud;

import android.content.Context;
import android.content.SharedPreferences;

/** The card's one switch, persisted: whether the pill shows at all. On by default. */
final class Prefs {
    private Prefs() {
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", true);
    }

    static void enabled(Context context, boolean on) {
        prefs(context).edit().putBoolean("enabled", on).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("pill", Context.MODE_PRIVATE);
    }
}
