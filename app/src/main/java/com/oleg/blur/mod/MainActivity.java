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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "hun_test_channel";
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final int NOTIFICATION_ID = 2001;
    private static final int DEFAULT_TINT_ALPHA = 177;
    private static final int DEFAULT_BLUR_RADIUS = 65;
    private static final int MAX_TINT_ALPHA = 255;
    private static final int MAX_BLUR_RADIUS = 150;

    private TextView statusView;
    private TextView tintValueView;
    private TextView blurRadiusValueView;
    private SeekBar tintAlphaSeekBar;
    private SeekBar blurRadiusSeekBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status_text);
        tintValueView = findViewById(R.id.tint_alpha_value);
        blurRadiusValueView = findViewById(R.id.blur_radius_value);
        tintAlphaSeekBar = findViewById(R.id.tint_alpha_seekbar);
        blurRadiusSeekBar = findViewById(R.id.blur_radius_seekbar);

        Button notifyButton = findViewById(R.id.send_hun_button);
        Button permissionButton = findViewById(R.id.request_permission_button);
        Button restartSystemUiButton = findViewById(R.id.restart_systemui_button);

        BlurConfig config = readConfig();
        tintAlphaSeekBar.setMax(MAX_TINT_ALPHA);
        blurRadiusSeekBar.setMax(MAX_BLUR_RADIUS);
        tintAlphaSeekBar.setProgress(config.tintAlpha);
        blurRadiusSeekBar.setProgress(config.blurRadius);
        updateConfigLabels(config.tintAlpha, config.blurRadius);

        tintAlphaSeekBar.setOnSeekBarChangeListener(new ConfigSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateConfigLabels(progress, blurRadiusSeekBar.getProgress());
            }
        });
        blurRadiusSeekBar.setOnSeekBarChangeListener(new ConfigSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateConfigLabels(tintAlphaSeekBar.getProgress(), progress);
            }
        });

        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotificationPermissionIfNeeded();
            }
        });

        notifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                persistConfigWithFeedback(false);
                if (requestNotificationPermissionIfNeeded()) {
                    postHeadsUpNotification();
                }
            }
        });

        restartSystemUiButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                persistConfigWithFeedback(false);
                restartSystemUi();
            }
        });

        ensureChannel();
        persistConfigWithFeedback(false);
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

    private void restartSystemUi() {
        String[] commands = new String[]{
                "pkill com.android.systemui",
                "killall com.android.systemui",
                "am crash com.android.systemui"
        };

        for (String command : commands) {
            CommandResult result = runRootCommand(command);
            if (result.success) {
                Toast.makeText(this, getString(R.string.systemui_restart_sent_command, command), Toast.LENGTH_SHORT).show();
                return;
            }
            if (result.rootMissing) {
                Toast.makeText(this, R.string.systemui_restart_root_missing, Toast.LENGTH_LONG).show();
                return;
            }
        }

        Toast.makeText(this, getString(R.string.systemui_restart_failed_detail,
                "pkill/killall/am crash all failed"), Toast.LENGTH_LONG).show();
    }

    private CommandResult runRootCommand(String command) {
        try {
            Process process = new ProcessBuilder("su", "-c", command).start();
            int exitCode = process.waitFor();
            String error = readStream(process.getErrorStream());
            String output = readStream(process.getInputStream());
            boolean success = exitCode == 0;
            if (!success) {
                String detail = !error.isEmpty() ? error : output;
                if (!detail.isEmpty()) {
                    statusView.setText(getString(R.string.status_command_failed, command, exitCode, detail));
                }
            }
            return new CommandResult(success, false);
        } catch (IOException e) {
            return new CommandResult(false, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Toast.makeText(this, R.string.systemui_restart_interrupted, Toast.LENGTH_SHORT).show();
            return new CommandResult(false, false);
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        byte[] data = inputStream.readAllBytes();
        return new String(data).trim();
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

        BlurConfig config = readConfig();
        String status = getString(
                R.string.status_template,
                permissionGranted ? getString(R.string.status_granted) : getString(R.string.status_missing),
                channelHigh ? getString(R.string.status_high) : getString(R.string.status_not_high),
                crossWindowBlurEnabled ? getString(R.string.status_enabled) : getString(R.string.status_disabled),
                config.tintAlpha,
                config.blurRadius,
                getConfigFile().getAbsolutePath()
        );
        statusView.setText(status);
    }

    private void updateConfigLabels(int tintAlpha, int blurRadius) {
        tintValueView.setText(getString(R.string.tint_alpha_value, tintAlpha));
        blurRadiusValueView.setText(getString(R.string.blur_radius_value, blurRadius));
    }

    private void persistConfigWithFeedback(boolean showToast) {
        BlurConfig config = new BlurConfig(tintAlphaSeekBar.getProgress(), blurRadiusSeekBar.getProgress());
        boolean success = writeConfig(config);
        if (showToast) {
            Toast.makeText(
                    this,
                    success ? R.string.config_saved : R.string.config_save_failed,
                    Toast.LENGTH_SHORT
            ).show();
        }
        updateStatus();
    }

    private boolean writeConfig(BlurConfig config) {
        File file = getConfigFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        Properties properties = new Properties();
        properties.setProperty("tint_alpha", String.valueOf(config.tintAlpha));
        properties.setProperty("blur_radius", String.valueOf(config.blurRadius));

        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            properties.store(outputStream, "BlurNotification config");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private BlurConfig readConfig() {
        File file = getConfigFile();
        if (!file.exists()) {
            return new BlurConfig(DEFAULT_TINT_ALPHA, DEFAULT_BLUR_RADIUS);
        }
        Properties properties = new Properties();
        try (java.io.FileInputStream inputStream = new java.io.FileInputStream(file)) {
            properties.load(inputStream);
            return new BlurConfig(
                    clamp(parseInt(properties.getProperty("tint_alpha"), DEFAULT_TINT_ALPHA), 0, MAX_TINT_ALPHA),
                    clamp(parseInt(properties.getProperty("blur_radius"), DEFAULT_BLUR_RADIUS), 0, MAX_BLUR_RADIUS)
            );
        } catch (IOException e) {
            return new BlurConfig(DEFAULT_TINT_ALPHA, DEFAULT_BLUR_RADIUS);
        }
    }

    private File getConfigFile() {
        File[] mediaDirs = getExternalMediaDirs();
        if (mediaDirs != null) {
            for (File dir : mediaDirs) {
                if (dir != null) {
                    return new File(dir, "blur_config.properties");
                }
            }
        }
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir != null) {
            return new File(externalFilesDir, "blur_config.properties");
        }
        return new File(getFilesDir(), "blur_config.properties");
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private abstract class ConfigSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            persistConfigWithFeedback(true);
        }
    }

    private static final class BlurConfig {
        final int tintAlpha;
        final int blurRadius;

        BlurConfig(int tintAlpha, int blurRadius) {
            this.tintAlpha = tintAlpha;
            this.blurRadius = blurRadius;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US, "BlurConfig(alpha=%d, radius=%d)", tintAlpha, blurRadius);
        }
    }

    private static final class CommandResult {
        final boolean success;
        final boolean rootMissing;

        CommandResult(boolean success, boolean rootMissing) {
            this.success = success;
            this.rootMissing = rootMissing;
        }
    }
}
