package io.github.hccullen.volumehud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the pill back after a reboot or an update of this app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            PillService.start(context);
        }
    }
}
