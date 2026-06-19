package com.noctis.wirelessguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * マニフェスト登録ブロードキャスト受信
 * アプリが閉じていてもBoot後に状態変化を受信できるようにする
 */
public class WirelessStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // サービスが死んでいたら再起動
        Intent svc = new Intent(context, WirelessMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
