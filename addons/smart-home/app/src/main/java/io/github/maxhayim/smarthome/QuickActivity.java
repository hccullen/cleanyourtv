package io.github.maxhayim.smarthome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * One press of a remote button mapped to it (the launcher's app_keys, for example
 * "KEYCODE_1:io.github.maxhayim.smarthome/.QuickActivity") runs a quick action from the pushed config
 * on the configured Home Assistant: a scene or script turns on, an automation triggers, a light,
 * switch, fan or input boolean toggles. A number button runs "quick_<digit>" (the key's code rides in
 * as an extra from the launcher's key services); any other mapped button runs "quick". A value that is
 * a link instead of an entity id opens on the TV rather than calling Home Assistant (a YouTube video,
 * say). A toast reports the outcome; nothing else appears on screen and the activity leaves at once,
 * so the app under the button stays where it was.
 */
public class QuickActivity extends Activity {
    /** The pressed key's code, attached by the launcher's key services (RemoteKeys.EXTRA_KEYCODE). */
    static final String EXTRA_KEYCODE = "com.gathin.tvhome.extra.keycode";
    /** The TV's own YouTube, aimed at directly for youtube.com and vnd.youtube: links, so no browser or
     *  chooser answers first. */
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(this);
        int digit = Ha.digit(getIntent().getIntExtra(EXTRA_KEYCODE, 0));
        String quick = digit >= 0 ? config.quicks[digit] : config.quick;
        if (Ha.isUriTarget(quick)) {
            Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(quick));
            // A link needs no Home Assistant server, so it comes before the setup check.
            if (quick.contains("youtu")) play.setPackage(YOUTUBE);
            try {
                startActivity(play);
            } catch (ActivityNotFoundException e) {
                say(getString(R.string.quick_no_app));
            }
            finish();
            return;
        }
        if (!config.complete()) {
            say(getString(R.string.quick_no_server));
            finish();
            return;
        }
        final Ha.Call call = Ha.quick(quick);
        if (call == null) {
            int missing = quick.isEmpty() ? (digit >= 0 ? R.string.quick_digit_none : R.string.quick_none)
                    : R.string.quick_unknown;
            say(digit >= 0 && quick.isEmpty() ? getString(missing, digit) : getString(missing));
            finish();
            return;
        }
        final String url = config.url;
        final String token = config.token;
        finish();
        new Thread(() -> {
            // Silent when it works: the house changing is the feedback. A toast only for what went wrong.
            HaClient.Reply reply = new HaClient(url, token).post(call.path, call.body);
            if (!reply.ok()) say(Ha.problem(reply.code));
        }, "quick-action").start();
    }

    /** A toast from the app context: it shows over whatever is on screen and outlives this activity. */
    private void say(final String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }
}
