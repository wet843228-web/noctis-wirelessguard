package com.noctis.wirelessguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class DefenderOverlayService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private boolean isMinimized = false;

    private static final String NOCTIS_URL = "http://127.0.0.1:9780/";
    private static final String NOTIF_CHANNEL_ID = "noctis_overlay_channel";
    private static final int NOTIF_ID = 4401;
    private static final long RETRY_INTERVAL_MS = 5000;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private Runnable retryRunnable;
    private boolean backendOnline = false;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showFloatingWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "NOCTIS DEFENDER Overlay",
                    NotificationManager.IMPORTANCE_MIN
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }

        Intent closeIntent = new Intent(this, DefenderOverlayService.class);
        closeIntent.setAction(ACTION_STOP);
        PendingIntent closePending = PendingIntent.getService(
                this, 0, closeIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("NOCTIS DEFENDER 稼働中")
                .setContentText("フローティングウィンドウ表示中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(0, "終了", closePending)
                .setOngoing(true)
                .build();
    }

    private void showFloatingWindow() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.overlay_floating_window, null);
        floatingView = view;

        WebView webView = view.findViewById(R.id.overlayWebView);
        View header = view.findViewById(R.id.overlayHeader);
        ImageButton closeBtn = view.findViewById(R.id.btnClose);
        ImageButton minimizeBtn = view.findViewById(R.id.btnMinimize);
        TextView errorText = view.findViewById(R.id.overlayErrorText);
        FrameLayout webContainer = view.findViewById(R.id.overlayWebContainer);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                backendOnline = true;
                errorText.setVisibility(View.GONE);
                webContainer.setVisibility(View.VISIBLE);
                stopRetryLoop();
            }

            @Override
            public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                backendOnline = false;
                errorText.setVisibility(View.VISIBLE);
                errorText.setText("NOCTIS BACKEND OFFLINE\n再接続を試みています…");
                webContainer.setVisibility(View.GONE);
                startRetryLoop(webView);
            }
        });
        webView.loadUrl(NOCTIS_URL);

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                760,
                1200,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 120;

        windowManager.addView(view, params);

        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];

        header.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX[0] = params.x;
                    initialY[0] = params.y;
                    touchX[0] = event.getRawX();
                    touchY[0] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = initialX[0] + (int) (event.getRawX() - touchX[0]);
                    params.y = initialY[0] + (int) (event.getRawY() - touchY[0]);
                    windowManager.updateViewLayout(view, params);
                    return true;
                default:
                    return false;
            }
        });

        closeBtn.setOnClickListener(v -> stopSelf());

        minimizeBtn.setOnClickListener(v -> {
            isMinimized = !isMinimized;
            if (isMinimized) {
                params.width = 200;
                params.height = 120;
                webContainer.setVisibility(View.GONE);
            } else {
                params.width = 760;
                params.height = 1200;
                if (backendOnline) {
                    webContainer.setVisibility(View.VISIBLE);
                }
            }
            windowManager.updateViewLayout(view, params);
        });
    }

    private void startRetryLoop(WebView webView) {
        stopRetryLoop();
        retryRunnable = () -> {
            if (!backendOnline) {
                webView.loadUrl(NOCTIS_URL);
            }
            retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
        };
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void stopRetryLoop() {
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRetryLoop();
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // already removed
            }
        }
    }

    public static final String ACTION_STOP = "com.noctis.wirelessguard.overlay.ACTION_STOP";
}
