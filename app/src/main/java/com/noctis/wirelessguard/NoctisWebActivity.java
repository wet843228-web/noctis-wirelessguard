package com.noctis.wirelessguard;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * NOCTIS DEFENDER (Flask UI) をWebViewでフルスクリーン表示するActivity。
 * MainActivityのボタンから手動で開かれる想定。
 * Flaskが未起動でも自動リトライで待つので、ユーザーは何もしなくてよい。
 */
public class NoctisWebActivity extends AppCompatActivity {

    private static final String NOCTIS_URL = "http://127.0.0.1:9780/";
    private static final long RETRY_INTERVAL_MS = 3000;

    private WebView webView;
    private TextView errorText;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private Runnable retryRunnable;
    private boolean online = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_noctis_web);

        webView = findViewById(R.id.noctisWebView);
        errorText = findViewById(R.id.noctisErrorText);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                online = true;
                errorText.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                stopRetry();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                online = false;
                errorText.setVisibility(View.VISIBLE);
                errorText.setText("NOCTIS起動待機中…\n(Flaskサーバー接続待ち)");
                webView.setVisibility(View.GONE);
                startRetry();
            }
        });

        webView.loadUrl(NOCTIS_URL);
    }

    private void startRetry() {
        stopRetry();
        retryRunnable = () -> {
            if (!online) {
                webView.loadUrl(NOCTIS_URL);
            }
            retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
        };
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void stopRetry() {
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRetry();
    }
}
