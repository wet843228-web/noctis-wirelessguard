package com.noctis.wirelessguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * 新規アプリインストール・更新を検知するレシーバー
 * Android標準のPACKAGE_ADDEDブロードキャストを利用（正規API、root不要）
 */
public class PackageMonitorReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Uri uri = intent.getData();
        String packageName = (uri != null) ? uri.getSchemeSpecificPart() : "unknown";
        boolean isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

        String appLabel = packageName;
        boolean isSystemApp = false;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            appLabel = pm.getApplicationLabel(info).toString();
            isSystemApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        // システムアプリの更新は通知から除外（OSアップデートのたびに大量発生するため）
        if (isSystemApp) return;

        String eventType = isUpdate ? "PACKAGE_UPDATED" : "PACKAGE_ADDED";
        String msg = (isUpdate ? "アプリ更新: " : "⚠️ 新規アプリ検知: ")
            + appLabel + " (" + packageName + ")";

        Intent svcIntent = new Intent(context, WirelessMonitorService.class);
        svcIntent.setAction(WirelessMonitorService.ACTION_LOG_EXTERNAL_EVENT);
        svcIntent.putExtra("event_type", eventType);
        svcIntent.putExtra("event_message", msg);
        try {
            context.startForegroundService(svcIntent);
        } catch (Exception ignored) {
        }
    }
}
