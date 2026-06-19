package com.noctis.wirelessguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class WirelessMonitorService extends Service {

    public static final String ACTION_STATE_UPDATE = "com.noctis.wirelessguard.STATE_UPDATE";

    private static final String CHANNEL_ID    = "wireless_guard_channel";
    private static final String IPC_DIR       = "/sdcard/guardian";
    private static final String IPC_LOG_FILE  = IPC_DIR + "/wireless_events.jsonl";
    private static final int    NOTIF_ID      = 2001;

    private final SimpleDateFormat iso8601 = new SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;

    // =====================================
    // システムブロードキャスト受信
    // =====================================
    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                // Bluetooth アダプタ状態変化
                case BluetoothAdapter.ACTION_STATE_CHANGED: {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                                   BluetoothAdapter.ERROR);
                    handleBtStateChanged(state);
                    break;
                }
                // Bluetooth スキャンモード変化（Discoverable変化で発火）
                case BluetoothAdapter.ACTION_SCAN_MODE_CHANGED: {
                    int mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE,
                                                  BluetoothAdapter.SCAN_MODE_NONE);
                    handleBtScanModeChanged(mode);
                    break;
                }
                // Bluetooth デバイス発見（周辺端末検知）
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED: {
                    logAndBroadcast("BT_DISCOVERY_START", "BT探索開始", "bt_state",
                        "BT探索: 開始", null);
                    break;
                }
                // Wi-Fi 状態変化
                case WifiManager.WIFI_STATE_CHANGED_ACTION: {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                                   WifiManager.WIFI_STATE_UNKNOWN);
                    handleWifiStateChanged(state);
                    break;
                }
                // Wi-Fi P2P 状態変化（Quick Shareが使う基盤）
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 0);
                    handleP2pStateChanged(state);
                    break;
                }
                // P2P ピアリスト変化（周辺のQuick Share端末が現れた）
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                    handleP2pPeersChanged(intent);
                    break;
                }
                // P2P 接続状態変化
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                    WifiP2pInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    handleP2pConnectionChanged(info);
                    break;
                }
                // 位置情報プロバイダ変化
                case LocationManager.PROVIDERS_CHANGED_ACTION: {
                    handleLocationChanged();
                    break;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        iso8601.setTimeZone(TimeZone.getTimeZone("UTC"));

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("NOCTIS WirelessGuard: 監視中"));

        // IPCディレクトリ作成
        new File(IPC_DIR).mkdirs();

        // P2Pマネージャ初期化
        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (p2pManager != null) {
            p2pChannel = p2pManager.initialize(this, getMainLooper(), null);
        }

        registerSystemReceiver();
    }

    private void registerSystemReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(systemReceiver, filter);
        }
    }

    // =====================================
    // イベントハンドラ
    // =====================================

    private void handleBtStateChanged(int state) {
        String stateText;
        switch (state) {
            case BluetoothAdapter.STATE_ON:  stateText = "ON";  break;
            case BluetoothAdapter.STATE_OFF: stateText = "OFF"; break;
            default: stateText = "TRANSITIONING"; break;
        }
        logAndBroadcast("BT_STATE", "Bluetooth: " + stateText,
            "bt_state", "Bluetooth: " + stateText, null);
    }

    private void handleBtScanModeChanged(int mode) {
        String modeText;
        boolean alert = false;
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                modeText = "DISCOVERABLE"; alert = true; break;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                modeText = "CONNECTABLE"; break;
            default:
                modeText = "NONE"; break;
        }
        logAndBroadcast("BT_SCAN_MODE", "BT SCAN_MODE: " + modeText,
            "bt_scan_mode", (alert ? "⚠️ " : "") + "SCAN: " + modeText,
            alert ? "⚠️ BT DISCOVERABLE ON" : null);
    }

    private void handleWifiStateChanged(int state) {
        String text;
        switch (state) {
            case WifiManager.WIFI_STATE_ENABLED:  text = "ENABLED";  break;
            case WifiManager.WIFI_STATE_DISABLED: text = "DISABLED"; break;
            default: text = "TRANSITIONING"; break;
        }
        logAndBroadcast("WIFI_STATE", "WiFi: " + text,
            "wifi_state", "Wi-Fi: " + text, null);
    }

    private void handleP2pStateChanged(int state) {
        boolean enabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
        String text = enabled ? "ENABLED" : "DISABLED";
        logAndBroadcast("P2P_STATE", "P2P: " + text,
            "p2p_state", (enabled ? "⚠️ " : "") + "Wi-Fi P2P: " + text,
            enabled ? "⚠️ Wi-Fi P2P 有効（Quick Share基盤）" : null);
    }

    private void handleP2pPeersChanged(Intent intent) {
        WifiP2pDeviceList deviceList = intent.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
        int count = (deviceList != null) ? deviceList.getDeviceList().size() : 0;
        String msg = "⚠️ P2Pピア変化: " + count + "台検知";
        if (deviceList != null && count > 0) {
            StringBuilder sb = new StringBuilder();
            for (WifiP2pDevice d : deviceList.getDeviceList()) {
                sb.append(d.deviceName).append("(").append(d.deviceAddress).append(") ");
            }
            msg += " — " + sb.toString().trim();
        }
        logAndBroadcast("P2P_PEERS", msg, "p2p_state", msg, "⚠️ P2Pピア検知: " + count + "台");
    }

    private void handleP2pConnectionChanged(WifiP2pInfo info) {
        if (info != null && info.groupFormed) {
            String owner = info.groupOwnerAddress != null
                ? info.groupOwnerAddress.getHostAddress() : "unknown";
            String msg = "⚠️ P2P接続確立 GroupOwner=" + owner;
            logAndBroadcast("P2P_CONNECTED", msg, "p2p_state", msg, msg);
        } else {
            logAndBroadcast("P2P_DISCONNECTED", "P2P接続: 切断",
                "p2p_state", "P2P: 切断", null);
        }
    }

    private void handleLocationChanged() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        boolean gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        String msg = "位置情報 GPS=" + (gps ? "ON" : "OFF") + " NET=" + (net ? "ON" : "OFF");
        logAndBroadcast("LOCATION", msg, "location_state", msg,
            (gps || net) ? "⚠️ 位置情報ON" : null);
    }

    // =====================================
    // ユーティリティ
    // =====================================

    /**
     * JSONLファイルへ書き込み + LocalBroadcast送信 + (必要なら)通知表示
     */
    private void logAndBroadcast(String eventType, String logMsg,
                                  String broadcastKey, String broadcastValue,
                                  String alertMsg) {
        // 1. IPCファイルへJSONL書き込み
        writeIpcLog(eventType, logMsg);

        // 2. MainActivity へ LocalBroadcast
        Intent i = new Intent(ACTION_STATE_UPDATE);
        i.putExtra(broadcastKey, broadcastValue);
        i.putExtra("event_message", logMsg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);

        // 3. 警告レベルはシステム通知
        if (alertMsg != null) {
            showAlertNotification(alertMsg);
        }
    }

    private void writeIpcLog(String eventType, String message) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ts", iso8601.format(new Date()));
            obj.put("event", eventType);
            obj.put("message", message);
            obj.put("source", "WirelessGuard");

            FileWriter fw = new FileWriter(IPC_LOG_FILE, true);
            fw.write(obj.toString() + "\n");
            fw.close();
        } catch (Exception e) {
            // ログ書き込み失敗は無視（SDカードアクセス権がない場合など）
        }
    }

    private void showAlertNotification(String msg) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ WirelessGuard ALERT")
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build();

        nm.notify((int) System.currentTimeMillis(), n);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("NOCTIS WirelessGuard")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(systemReceiver); } catch (Exception ignored) {}
    }
}
