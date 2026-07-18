package com.noctis.wirelessguard;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 未登録Bluetoothデバイスの検知・強制切断ヘルパー
 * Termux側 bt_guard.sh と同一の設計思想（受けて溶かす）を
 * アプリ内で常駐実行することで、Termuxプロセスが死んでいても機能する
 */
public class BtGuardHelper {

    private static final String TAG = "BtGuardHelper";

    // Termux ~/storage/downloads/NoctisGuardian/ と共有される公開領域
    private static String whitelistPath() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "NoctisGuardian");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "bt_whitelist.txt").getAbsolutePath();
    }

    private static Set<String> loadWhitelist() {
        Set<String> set = new HashSet<>();
        File f = new File(whitelistPath());
        if (!f.exists()) return set; // 空リスト = 全デバイス未登録扱い
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    set.add(line.toUpperCase(java.util.Locale.US));
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "whitelist read failed: " + e.getMessage(), e);
        }
        return set;
    }

    public static boolean isWhitelisted(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) return true; // 不明なら誤爆回避
        Set<String> wl = loadWhitelist();
        if (wl.isEmpty()) return true; // ホワイトリスト未設置時は何もしない（安全側）
        return wl.contains(device.getAddress().toUpperCase(java.util.Locale.US));
    }

    /**
     * 未登録デバイスなら各プロファイル経由で強制切断を試みる
     */
    public static void disconnectIfUnknown(Context context, BluetoothDevice device) {
        if (isWhitelisted(device)) return;

        android.util.Log.w(TAG, "未登録デバイス切断試行: " + device.getAddress());

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        tryDisconnectProfile(context, adapter, device, BluetoothProfile.A2DP);
        tryDisconnectProfile(context, adapter, device, BluetoothProfile.HEADSET);
        tryDisconnectProfile(context, adapter, device, BluetoothProfile.HID_HOST);
    }

    private static void tryDisconnectProfile(Context context, BluetoothAdapter adapter,
                                              BluetoothDevice device, int profileId) {
        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    Method m = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                    m.setAccessible(true);
                    Object result = m.invoke(proxy, device);
                    android.util.Log.w(TAG, "profile=" + profile + " disconnect result=" + result);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "disconnect reflection failed profile=" + profile
                        + " : " + e.getMessage(), e);
                } finally {
                    try {
                        adapter.closeProfileProxy(profileId, proxy);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                // 何もしない
            }
        }, profileId);
    }
}
