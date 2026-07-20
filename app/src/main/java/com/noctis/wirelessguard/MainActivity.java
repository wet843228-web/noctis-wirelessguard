package com.noctis.wirelessguard;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // ★ FLAG_SECURE: スクリーンショット・画面共有・録画を完全ブロック
    //    これはアプリ内のどのActivityにも適用される
    //    Quick Shareで画面を共有しようとしても黒画面になる

    private static final int MAX_LOG_LINES = 30;
    private static final int UI_REFRESH_MS = 3000;

    private TextView tvBtState, tvBtScanMode, tvWifiState, tvP2pState;
    private TextView tvLocationState, tvEventLog, tvLogPath, tvStatusLine;

    private final Deque<String> eventBuffer = new ArrayDeque<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ローカルブロードキャスト受信（サービスから）
    private final BroadcastReceiver localReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WirelessMonitorService.ACTION_STATE_UPDATE.equals(action)) {
                updateFromIntent(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Bluetooth実行時権限リクエスト（Android 12+ / API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            java.util.List<String> needed = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), 1001);
            }
        }

        // ★★★ FLAG_SECURE 設定 ★★★
        // これによりこの画面は：
        //   - スクリーンショット不可
        //   - 画面録画に黒画面として表示
        //   - Quick Share / AirDrop の画面共有で黒画面
        //   - Recents（タスクスイッチャー）でもサムネイルが黒くなる
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        tvBtState      = findViewById(R.id.tv_bt_state);
        tvBtScanMode   = findViewById(R.id.tv_bt_scan_mode);
        tvWifiState    = findViewById(R.id.tv_wifi_state);
        tvP2pState     = findViewById(R.id.tv_p2p_state);
        tvLocationState= findViewById(R.id.tv_location_state);
        tvEventLog     = findViewById(R.id.tv_event_log);
        tvLogPath      = findViewById(R.id.tv_log_path);
        tvStatusLine   = findViewById(R.id.tv_status_line);

        File logDir = getExternalFilesDir("guardian");
        String logPathText = (logDir != null)
            ? new File(logDir, "wireless_events.jsonl").getAbsolutePath()
            : "(内部ストレージ)/guardian/wireless_events.jsonl";
        tvLogPath.setText("IPC → " + logPathText);

        // フォアグラウンドサービス起動
        Intent svcIntent = new Intent(this, WirelessMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }

        // ローカルブロードキャスト登録
        IntentFilter filter = new IntentFilter(WirelessMonitorService.ACTION_STATE_UPDATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(localReceiver, filter);

        // 初期状態取得
        refreshCurrentState();

        findViewById(R.id.btn_open_noctis).setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, NoctisWebActivity.class));
        });

        // 定期UIリフレッシュ（ログ再読み込み）
        uiHandler.postDelayed(this::periodicRefresh, UI_REFRESH_MS);
    }

    private void updateFromIntent(Intent intent) {
        String btState    = intent.getStringExtra("bt_state");
        String btScanMode = intent.getStringExtra("bt_scan_mode");
        String wifiState  = intent.getStringExtra("wifi_state");
        String p2pState   = intent.getStringExtra("p2p_state");
        String locState   = intent.getStringExtra("location_state");
        String event      = intent.getStringExtra("event_message");

        if (btState    != null) updateField(tvBtState,    btState,    isWarning(btState));
        if (btScanMode != null) updateField(tvBtScanMode, btScanMode, isWarning(btScanMode));
        if (wifiState  != null) updateField(tvWifiState,  wifiState,  false);
        if (p2pState   != null) updateField(tvP2pState,   p2pState,   isWarning(p2pState));
        if (locState   != null) updateField(tvLocationState, locState, isWarning(locState));

        if (event != null) {
            String ts = sdf.format(new Date());
            eventBuffer.addFirst("[" + ts + "] " + event);
            if (eventBuffer.size() > 20) eventBuffer.pollLast();
            tvEventLog.setText(String.join("\n", eventBuffer));
        }
    }

    private void updateField(TextView tv, String text, boolean warn) {
        tv.setText(text);
        tv.setTextColor(warn ? Color.parseColor("#FF4444") : Color.parseColor("#CCFFEE"));
    }

    private boolean isWarning(String text) {
        return text != null && (
            text.contains("DISCOVERABLE") ||
            text.contains("⚠") ||
            text.contains("P2P: 有効") ||
            text.contains("接続確立") ||
            text.contains("HIGH_ACCURACY")
        );
    }

    private void refreshCurrentState() {
        // BluetoothAdapter 現在状態
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        boolean btPermOk = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            || (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
        if (bt != null && btPermOk) {
            updateBtState(bt.getState());
            updateBtScanMode(bt.getScanMode());
        }

        // Wi-Fi 現在状態
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            updateWifiState(wm.getWifiState());
        }

        // 位置情報 現在状態
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm != null) {
            boolean gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            String locText = "GPS=" + (gpsOn ? "ON" : "OFF") + " NET=" + (netOn ? "ON" : "OFF");
            updateField(tvLocationState, locText, gpsOn || netOn);
        }

        tvStatusLine.setText("MONITORING ACTIVE — " + sdf.format(new Date()));
    }

    private void periodicRefresh() {
        refreshCurrentState();
        uiHandler.postDelayed(this::periodicRefresh, UI_REFRESH_MS);
    }

    void updateBtState(int state) {
        String text;
        switch (state) {
            case BluetoothAdapter.STATE_ON:        text = "Bluetooth: ON";        break;
            case BluetoothAdapter.STATE_OFF:       text = "Bluetooth: OFF";       break;
            case BluetoothAdapter.STATE_TURNING_ON: text = "Bluetooth: TURNING ON"; break;
            case BluetoothAdapter.STATE_TURNING_OFF:text = "Bluetooth: TURNING OFF";break;
            default: text = "Bluetooth: UNKNOWN(" + state + ")"; break;
        }
        updateField(tvBtState, text, state == BluetoothAdapter.STATE_ON);
    }

    void updateBtScanMode(int mode) {
        String text;
        boolean warn;
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                text = "⚠️ SCAN: DISCOVERABLE（全端末から検出可能）"; warn = true; break;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                text = "SCAN: CONNECTABLE（ペアリング済みのみ）"; warn = false; break;
            case BluetoothAdapter.SCAN_MODE_NONE:
            default:
                text = "SCAN: NONE（非公開）"; warn = false; break;
        }
        updateField(tvBtScanMode, text, warn);
    }

    void updateWifiState(int state) {
        String text;
        switch (state) {
            case WifiManager.WIFI_STATE_ENABLED:    text = "Wi-Fi: ENABLED";   break;
            case WifiManager.WIFI_STATE_DISABLED:   text = "Wi-Fi: DISABLED";  break;
            case WifiManager.WIFI_STATE_ENABLING:   text = "Wi-Fi: ENABLING";  break;
            case WifiManager.WIFI_STATE_DISABLING:  text = "Wi-Fi: DISABLING"; break;
            default: text = "Wi-Fi: UNKNOWN"; break;
        }
        updateField(tvWifiState, text, false);
    }

    void updateP2pState(int state) {
        String text;
        boolean warn;
        switch (state) {
            case WifiP2pManager.WIFI_P2P_STATE_ENABLED:
                text = "⚠️ Wi-Fi P2P: ENABLED（Quick Share基盤: 有効）"; warn = true; break;
            case WifiP2pManager.WIFI_P2P_STATE_DISABLED:
            default:
                text = "Wi-Fi P2P: DISABLED"; warn = false; break;
        }
        updateField(tvP2pState, text, warn);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localReceiver);
        uiHandler.removeCallbacksAndMessages(null);
    }
}
