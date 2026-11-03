package org.droidos.desktop;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
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
                WindowManagerHelper.launchFreeformApp(this, intent, getDisplay());
            }
        });

        setupDesktopShortcuts();
        setupStartMenu();

        // Clicking desktop canvas closes start menu
        mDesktopCanvas.setOnClickListener(v -> hideStartMenu());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAppsAdapter.reloadApps();
        mHandler.post(mClockTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mClockTask);
    }

    private void setupDesktopShortcuts() {
        findViewById(R.id.icon_files).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType("*/*");
            launchAppOrFallback(intent, "com.android.documentsui");
        });

        findViewById(R.id.icon_browser).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
            launchAppOrFallback(intent, "org.lineageos.jelly");
        });

        findViewById(R.id.icon_terminal).setOnClickListener(v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.android.terminal");
            if (intent != null) {
                WindowManagerHelper.launchFreeformApp(this, intent, getDisplay());
            }
        });

        findViewById(R.id.icon_settings).setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            WindowManagerHelper.launchFreeformApp(this, intent, getDisplay());
        });

        mBtnShowDesktop.setOnClickListener(v -> {
            // Minimize all / show desktop
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_SECONDARY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        });
    }

    private void launchAppOrFallback(Intent intent, String preferredPkg) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(preferredPkg);
        if (launchIntent != null) {
            WindowManagerHelper.launchFreeformApp(this, launchIntent, getDisplay());
            try {
                mTaskbarManager.addOrUpdateTask(preferredPkg, getPackageManager().getApplicationIcon(preferredPkg));
            } catch (Exception ignored) {}
        } else {
            WindowManagerHelper.launchFreeformApp(this, intent, getDisplay());
        }
    }

    private void setupStartMenu() {
        mBtnStart.setOnClickListener(v -> {
            if (mStartMenuContainer.getVisibility() == View.VISIBLE) {
                hideStartMenu();
            } else {
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
                WindowManagerHelper.launchFreeformApp(this, entry.launchIntent, getDisplay());
                mTaskbarManager.addOrUpdateTask(entry.packageName, entry.icon);
                hideStartMenu();
            }
        });

        findViewById(R.id.btn_disconnect_desktop).setOnClickListener(v -> {
            // Disconnect desktop session
            hideStartMenu();
            finish();
        });
    }

    private void showStartMenu() {
        mStartMenuContainer.setVisibility(View.VISIBLE);
        mEtSearchApps.setText("");
        mEtSearchApps.requestFocus();
    }

    private void hideStartMenu() {
        mStartMenuContainer.setVisibility(View.GONE);
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
