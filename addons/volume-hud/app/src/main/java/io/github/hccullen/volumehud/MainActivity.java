package io.github.hccullen.volumehud;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The card's screen: a line about what the pill does, whether it is on, one switch, and the state
 * of the "display over other apps" permission the pill needs to be seen at all. Opening the screen
 * also starts the listening service, so a fresh install needs one visit to the card (or a reboot)
 * before the pill can appear.
 */
public class MainActivity extends Activity {
    private TextView state;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int side = dp(72);
        box.setPadding(side, side, side, side);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(34);
        title.setPadding(0, 0, 0, dp(12));
        box.addView(title);

        TextView about = new TextView(this);
        about.setText(R.string.about);
        about.setTextSize(16);
        about.setLineSpacing(dp(2), 1.0f);
        about.setPadding(0, 0, 0, dp(28));
        box.addView(about);

        state = new TextView(this);
        state.setTextSize(20);
        state.setPadding(0, 0, 0, dp(20));
        box.addView(state);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> flip());
        box.addView(toggle);

        TextView permission = new TextView(this);
        permission.setTextSize(14);
        permission.setLineSpacing(dp(2), 1.0f);
        permission.setPadding(0, dp(28), 0, 0);
        permission.setText(Settings.canDrawOverlays(this) ? R.string.overlay_allowed : R.string.overlay_missing);
        box.addView(permission);

        setContentView(box);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PillService.start(this);   // self-heal: a service the system killed comes back on the next open
        show();
    }

    private void flip() {
        boolean now = !Prefs.enabled(this);
        Prefs.enabled(this, now);
        if (now) {
            PillService.start(this);
        } else {
            PillService.stop(this);
            Pill.discard();   // no pill left hanging for its last 1.6 s
        }
        show();
    }

    private void show() {
        boolean on = Prefs.enabled(this);
        state.setText(on ? R.string.on : R.string.off);
        toggle.setText(on ? R.string.turn_off : R.string.turn_on);
    }

    private int dp(int at100) {
        return Math.round(at100 * getResources().getDisplayMetrics().density);
    }
}
