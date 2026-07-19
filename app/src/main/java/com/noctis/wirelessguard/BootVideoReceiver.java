package com.noctis.wirelessguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootVideoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent launch = new Intent(context, BootVideoActivity.class);
            launch.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK |
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            );
            context.startActivity(launch);
        }
    }
}
