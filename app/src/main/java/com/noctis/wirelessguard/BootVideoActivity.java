package com.noctis.wirelessguard;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;

public class BootVideoActivity extends AppCompatActivity {

    private boolean advanced = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_boot_video);

        VideoView videoView = findViewById(R.id.bootVideoView);
        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.noctis_boot);

        try {
            videoView.setVideoURI(videoUri);
            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                videoView.start();
            });
            videoView.setOnCompletionListener(mp -> proceed());
            videoView.setOnErrorListener((mp, what, extra) -> {
                proceed();
                return true;
            });
        } catch (Exception e) {
            proceed();
        }

        View skipArea = findViewById(R.id.skipTapArea);
        if (skipArea != null) {
            skipArea.setOnClickListener(v -> proceed());
        }
        videoView.setOnClickListener(v -> proceed());

        videoView.postDelayed(this::proceed, 15_000);
    }

    private void proceed() {
        if (advanced) return;
        advanced = true;

        if (canDrawOverlay()) {
            startOverlay();
        } else {
            startActivity(new Intent(this, OverlayPermissionActivity.class));
        }
        finish();
    }

    private boolean canDrawOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void startOverlay() {
        Intent svc = new Intent(this, DefenderOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }
}
