package org.droidos.desktop;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DesktopActivity extends Activity {

    private View mDesktopRoot;
    private View mDesktopCanvas;
    private LinearLayout mStartMenuContainer;
    private View mBtnStart;
    private View mBtnShowDesktop;
    private EditText mEtSearchApps;
    private GridView mGvStartApps;
    private TextView mTrayClock;
    private TextView mTrayBattery;
    private LinearLayout mRunningTasksContainer;

    private ImageView mDesktopWallpaper;
    private LinearLayout mQuickSettingsContainer;
    private ImageView mBtnQuickSettings;
    private SeekBar mSeekBrightness;
    private SeekBar mSeekVolume;
    private TextView mTvBrightnessVal;
    private TextView mTvVolumeVal;
    private AudioManager mAudioManager;

    private final BroadcastReceiver mWallpaperReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            syncPrimaryWallpaper();
        }
    };

    private AppDrawerAdapter mAppsAdapter;
    private TaskbarManager mTaskbarManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.US);

    private final Runnable mClockTask = new Runnable() {
        @Override
        public void run() {
            updateClockAndBattery();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (Throwable ignored) {}

        // Enable freeform window management and secondary display launch support dynamically
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
            android.provider.Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
            android.provider.Settings.Global.putInt(getContentResolver(), "force_desktop_mode_on_external_displays", 1);
        } catch (Throwable t) {
            android.util.Log.w("droidOS-Desktop", "Settings.Global putInt failed: " + t.getMessage());
        }

        setContentView(R.layout.activity_desktop);

        mDesktopRoot = findViewById(R.id.desktop_root);
        mDesktopCanvas = findViewById(R.id.desktop_canvas);
        mStartMenuContainer = findViewById(R.id.start_menu_container);
        mBtnStart = findViewById(R.id.btn_start);
        mBtnShowDesktop = findViewById(R.id.btn_show_desktop);
        mEtSearchApps = findViewById(R.id.et_search_apps);
        mGvStartApps = findViewById(R.id.gv_start_apps);
        mTrayClock = findViewById(R.id.tray_clock);
        mTrayBattery = findViewById(R.id.tray_battery);
        mRunningTasksContainer = findViewById(R.id.running_tasks_container);

        mAppsAdapter = new AppDrawerAdapter(this);
        mGvStartApps.setAdapter(mAppsAdapter);

        mTaskbarManager = new TaskbarManager(this, mRunningTasksContainer);
        mTaskbarManager.setOnTaskClickListener(pkg -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                WindowManagerHelper.launchApp(this, intent, getDisplay());
            }
        });

        mDesktopWallpaper = findViewById(R.id.desktop_wallpaper);
        syncPrimaryWallpaper();
        try {
            registerReceiver(mWallpaperReceiver, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        } catch (Throwable ignored) {}

        mQuickSettingsContainer = findViewById(R.id.quick_settings_container);
        mBtnQuickSettings = findViewById(R.id.btn_quick_settings);
        mSeekBrightness = findViewById(R.id.seek_brightness);
        mSeekVolume = findViewById(R.id.seek_volume);
        mTvBrightnessVal = findViewById(R.id.tv_brightness_val);
        mTvVolumeVal = findViewById(R.id.tv_volume_val);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        setupDesktopShortcuts();
        setupStartMenu();
        setupQuickSettings();

        // Clicking desktop canvas closes start menu and quick settings
        mDesktopCanvas.setOnClickListener(v -> {
            hideStartMenu();
            hideQuickSettings();
        });
    }

    private void syncPrimaryWallpaper() {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            Drawable wallpaper = wm.getDrawable();
            if (wallpaper != null && mDesktopWallpaper != null) {
                mDesktopWallpaper.setImageDrawable(wallpaper);
            }
        } catch (Throwable t) {
            android.util.Log.w("droidOS-Desktop", "Failed to sync primary wallpaper: " + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mWallpaperReceiver);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAppsAdapter.reloadApps();
        loadDesktopShortcutIcons();
        syncPrimaryWallpaper();
        refreshQuickSettingsValues();
        mHandler.post(mClockTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mClockTask);
    }

    private void setupDesktopShortcuts() {
        Intent filesFallback = new Intent(Intent.ACTION_VIEW);
        filesFallback.setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"), "vnd.android.document/root");

        Intent browserFallback = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"));
        Intent settingsFallback = new Intent(android.provider.Settings.ACTION_SETTINGS);

        loadDesktopShortcutIcons();

        findViewById(R.id.icon_files).setOnClickListener(v -> {
            launchAppOrFallback(filesFallback, "com.android.documentsui", "com.google.android.documentsui");
        });

        findViewById(R.id.icon_browser).setOnClickListener(v -> {
            launchAppOrFallback(browserFallback, "org.lineageos.jelly", "com.android.chrome", "com.android.browser");
        });

        findViewById(R.id.icon_terminal).setOnClickListener(v -> {
            launchAppOrFallback(null, "com.android.terminal", "org.lineageos.exactcalculator", "com.android.calculator2", "com.google.android.calculator");
        });

        findViewById(R.id.icon_settings).setOnClickListener(v -> {
            WindowManagerHelper.launchApp(this, settingsFallback, getDisplay());
            try {
                mTaskbarManager.addOrUpdateTask("com.android.settings", getPackageManager().getApplicationIcon("com.android.settings"));
            } catch (Exception ignored) {}
        });

        mBtnShowDesktop.setOnClickListener(v -> {
            // Minimize all / show desktop
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_SECONDARY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        });
    }

    private void loadDesktopShortcutIcons() {
        Intent filesFallback = new Intent(Intent.ACTION_VIEW);
        filesFallback.setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"), "vnd.android.document/root");
        loadShortcutIcon(findViewById(R.id.icon_files_img), filesFallback, "com.android.documentsui", "com.google.android.documentsui");

        Intent browserFallback = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"));
        loadShortcutIcon(findViewById(R.id.icon_browser_img), browserFallback, "org.lineageos.jelly", "com.android.chrome", "com.android.browser");

        loadShortcutIcon(findViewById(R.id.icon_terminal_img), null, "com.android.terminal", "org.lineageos.exactcalculator", "com.android.calculator2", "com.google.android.calculator");

        loadShortcutIcon(findViewById(R.id.icon_settings_img), new Intent(android.provider.Settings.ACTION_SETTINGS), "com.android.settings");
    }

    private void loadShortcutIcon(ImageView imageView, Intent fallbackIntent, String... candidatePackages) {
        if (imageView == null) return;
        PackageManager pm = getPackageManager();
        Drawable icon = null;

        for (String pkg : candidatePackages) {
            if (pkg == null) continue;
            try {
                icon = pm.getApplicationIcon(pkg);
                if (icon != null) break;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        if (icon == null && fallbackIntent != null) {
            try {
                ResolveInfo ri = pm.resolveActivity(fallbackIntent, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null) {
                    icon = ri.loadIcon(pm);
                }
            } catch (Throwable ignored) {}
        }

        if (icon != null) {
            imageView.setImageDrawable(icon);
            imageView.setImageTintList(null);
        }
    }

    private void launchAppOrFallback(Intent fallbackIntent, String... candidatePackages) {
        for (String pkg : candidatePackages) {
            if (pkg == null) continue;
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                WindowManagerHelper.launchApp(this, launchIntent, getDisplay());
                try {
                    mTaskbarManager.addOrUpdateTask(pkg, getPackageManager().getApplicationIcon(pkg));
                } catch (Exception ignored) {}
                return;
            }
        }
        if (fallbackIntent != null) {
            try {
                WindowManagerHelper.launchApp(this, fallbackIntent, getDisplay());
            } catch (Throwable t) {
                android.util.Log.e("droidOS-Desktop", "Failed to launch fallback intent: " + fallbackIntent, t);
            }
        }
    }

    private void setupStartMenu() {
        mBtnStart.setOnClickListener(v -> {
            if (mStartMenuContainer.getVisibility() == View.VISIBLE) {
                hideStartMenu();
            } else {
                hideQuickSettings();
                showStartMenu();
            }
        });

        mEtSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAppsAdapter.filter(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        mGvStartApps.setOnItemClickListener((parent, view, position, id) -> {
            AppDrawerAdapter.AppEntry entry = mAppsAdapter.getItem(position);
            if (entry != null && entry.launchIntent != null) {
                WindowManagerHelper.launchApp(this, entry.launchIntent, getDisplay());
                mTaskbarManager.addOrUpdateTask(entry.packageName, entry.icon);
                hideStartMenu();
            }
        });

        findViewById(R.id.btn_disconnect_desktop).setOnClickListener(v -> {
            // Disconnect desktop session
            hideStartMenu();
            hideQuickSettings();
            finish();
        });
    }

    private void showStartMenu() {
        hideQuickSettings();
        mStartMenuContainer.setVisibility(View.VISIBLE);
        mEtSearchApps.setText("");
        mEtSearchApps.requestFocus();
    }

    private void hideStartMenu() {
        mStartMenuContainer.setVisibility(View.GONE);
    }

    private void setupQuickSettings() {
        if (mBtnQuickSettings != null) {
            mBtnQuickSettings.setOnClickListener(v -> toggleQuickSettings());
        }

        View tileWifi = findViewById(R.id.qs_tile_wifi);
        if (tileWifi != null) {
            tileWifi.setOnClickListener(v -> {
                WindowManagerHelper.launchApp(this, new Intent(Settings.ACTION_WIFI_SETTINGS), getDisplay());
                hideQuickSettings();
            });
        }

        View tileBluetooth = findViewById(R.id.qs_tile_bluetooth);
        if (tileBluetooth != null) {
            tileBluetooth.setOnClickListener(v -> {
                WindowManagerHelper.launchApp(this, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), getDisplay());
                hideQuickSettings();
            });
        }

        View tileControlPanel = findViewById(R.id.qs_tile_control_panel);
        if (tileControlPanel != null) {
            tileControlPanel.setOnClickListener(v -> {
                launchAppOrFallback(null, "org.droidos.controlpanel");
                hideQuickSettings();
            });
        }

        View tileFiles = findViewById(R.id.qs_tile_files);
        if (tileFiles != null) {
            tileFiles.setOnClickListener(v -> {
                launchAppOrFallback(null, "com.android.documentsui", "com.google.android.documentsui");
                hideQuickSettings();
            });
        }

        View btnSettings = findViewById(R.id.qs_btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                WindowManagerHelper.launchApp(this, new Intent(Settings.ACTION_SETTINGS), getDisplay());
                hideQuickSettings();
            });
        }

        if (mSeekBrightness != null) {
            mSeekBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int safeProgress = Math.max(5, progress);
                        if (mTvBrightnessVal != null) {
                            mTvBrightnessVal.setText(safeProgress + "%");
                        }
                        try {
                            WindowManager.LayoutParams lp = getWindow().getAttributes();
                            lp.screenBrightness = safeProgress / 100.0f;
                            getWindow().setAttributes(lp);
                        } catch (Throwable ignored) {}

                        try {
                            Settings.System.putInt(getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS,
                                    (int) (safeProgress * 255.0f / 100.0f));
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (mSeekVolume != null && mAudioManager != null) {
            mSeekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        try {
                            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int pct = maxVol > 0 ? (int) ((progress * 100.0f) / maxVol) : 0;
                            if (mTvVolumeVal != null) {
                                mTvVolumeVal.setText(pct + "%");
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        refreshQuickSettingsValues();
    }

    private void refreshQuickSettingsValues() {
        try {
            int curBrightness = 180;
            try {
                curBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            } catch (Throwable ignored) {}
            int brightnessPct = Math.max(5, Math.min(100, (int) (curBrightness * 100.0f / 255.0f)));
            if (mSeekBrightness != null) {
                mSeekBrightness.setProgress(brightnessPct);
            }
            if (mTvBrightnessVal != null) {
                mTvBrightnessVal.setText(brightnessPct + "%");
            }
        } catch (Throwable ignored) {}

        try {
            if (mAudioManager != null) {
                int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (mSeekVolume != null) {
                    mSeekVolume.setMax(Math.max(1, maxVol));
                    mSeekVolume.setProgress(curVol);
                }
                if (mTvVolumeVal != null) {
                    int pct = maxVol > 0 ? (int) ((curVol * 100.0f) / maxVol) : 0;
                    mTvVolumeVal.setText(pct + "%");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void toggleQuickSettings() {
        if (mQuickSettingsContainer == null) return;
        if (mQuickSettingsContainer.getVisibility() == View.VISIBLE) {
            hideQuickSettings();
        } else {
            hideStartMenu();
            showQuickSettings();
        }
    }

    private void showQuickSettings() {
        if (mQuickSettingsContainer != null) {
            refreshQuickSettingsValues();
            mQuickSettingsContainer.setVisibility(View.VISIBLE);
        }
    }

    private void hideQuickSettings() {
        if (mQuickSettingsContainer != null) {
            mQuickSettingsContainer.setVisibility(View.GONE);
        }
    }

    private void updateClockAndBattery() {
        mTrayClock.setText(mTimeFormat.format(new Date()));

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = (level * 100) / (scale > 0 ? scale : 100);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL;
            mTrayBattery.setText(pct + "%" + (isCharging ? "⚡" : ""));
        }
    }
}
