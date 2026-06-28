package com.oleg.blur.mod;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "hun_test_channel";
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final int NOTIFICATION_ID = 2001;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status_text);
        Button notifyButton = findViewById(R.id.send_hun_button);
        Button permissionButton = findViewById(R.id.request_permission_button);

        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotificationPermissionIfNeeded();
            }
        });

        notifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (requestNotificationPermissionIfNeeded()) {
                    postHeadsUpNotification();
                }
            }
        });

        ensureChannel();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            updateStatus();
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                postHeadsUpNotification();
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS
        );
        return false;
    }

    private void ensureChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hun_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.hun_channel_description));
        channel.enableVibration(true);
        channel.enableLights(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private void postHeadsUpNotification() {
        ensureChannel();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, R.string.notification_manager_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.test_notification_title))
                .setContentText(getString(R.string.test_notification_content))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(getString(R.string.test_notification_big_text)))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .build();

        manager.notify(NOTIFICATION_ID, notification);
        Toast.makeText(this, R.string.notification_sent, Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean channelHigh = false;
        boolean crossWindowBlurEnabled = false;
        if (manager != null) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            channelHigh = channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
        }
        WindowManager windowManager = getSystemService(WindowManager.class);
        if (windowManager != null) {
            crossWindowBlurEnabled = windowManager.isCrossWindowBlurEnabled();
        }

        String status = getString(
                R.string.status_template,
                permissionGranted ? getString(R.string.status_granted) : getString(R.string.status_missing),
                channelHigh ? getString(R.string.status_high) : getString(R.string.status_not_high),
                crossWindowBlurEnabled ? getString(R.string.status_enabled) : getString(R.string.status_disabled)
        );
        statusView.setText(status);
    }
}
