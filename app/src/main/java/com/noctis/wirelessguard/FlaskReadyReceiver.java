package com.noctis.wirelessguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class FlaskReadyReceiver extends BroadcastReceiver {

    public static final String ACTION_FLASK_READY =
            "com.noctis.wirelessguard.FLASK_READY";

    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.e("NOCTIS_DEBUG", "onReceive called, action=" + intent.getAction());
        if (ACTION_FLASK_READY.equals(intent.getAction())) {
            Intent svc = new Intent(context, DefenderOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }
}
