package org.droidos.controlpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "droidOS-ControlPanel";

    private TextView mClockView;
    private TextView mUsbStatusView;
    private TextView mUsbDetailsView;
    private TextView mCpuLoadView;
    private TextView mCpuFreqView;
    private TextView mRamUsageView;
    private TextView mBatteryTempView;
    private Button mBtnToggleDesktop;
    private Button mBtnOpenTrackpad;
    private Button mBtnToggleTorch;
    private Button mBtnToggleWifi;
    private Button mBtnLockdown;
    private Button mBtnRebootMenu;

    private SystemMonitor mMonitor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsDesktopActive = false;
    private boolean mIsTorchOn = false;

    private PowerManager mPowerManager;
    private WifiManager mWifiManager;
    private CameraManager mCameraManager;
    private String mCameraId;

    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.US);

    private final Runnable mUpdateTask = new Runnable() {
        @Override
        public void run() {
            updateMetrics();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                setUsbConnectedState(true);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                setUsbConnectedState(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_panel);

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        mClockView = findViewById(R.id.tv_clock);
        mUsbStatusView = findViewById(R.id.tv_usb_status);
        mUsbDetailsView = findViewById(R.id.tv_usb_details);
        mCpuLoadView = findViewById(R.id.tv_cpu_load);
        mCpuFreqView = findViewById(R.id.tv_cpu_freq);
        mRamUsageView = findViewById(R.id.tv_ram_usage);
        mBatteryTempView = findViewById(R.id.tv_battery_temp);
        mBtnToggleDesktop = findViewById(R.id.btn_toggle_desktop);
        mBtnOpenTrackpad = findViewById(R.id.btn_open_trackpad);
        mBtnToggleTorch = findViewById(R.id.btn_toggle_torch);
        mBtnToggleWifi = findViewById(R.id.btn_toggle_wifi);
        mBtnLockdown = findViewById(R.id.btn_lockdown);
        mBtnRebootMenu = findViewById(R.id.btn_reboot_menu);

        mMonitor = new SystemMonitor(this);

        mBtnToggleDesktop.setOnClickListener(v -> toggleDesktopSession());
        mBtnOpenTrackpad.setOnClickListener(v -> {
            Intent intent = new Intent(this, TrackpadActivity.class);
            startActivity(intent);
        });

        // Quick Controls
        initCameraTorch();
        mBtnToggleTorch.setOnClickListener(v -> toggleTorch());
        mBtnToggleWifi.setOnClickListener(v -> toggleWifi());

        // System Hardware Controls
        mBtnLockdown.setOnClickListener(v -> executeInstantLockdown());
        mBtnRebootMenu.setOnClickListener(v -> showAdvancedRebootDialog());

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);
    }

    private void initCameraTorch() {
        try {
            if (mCameraManager != null) {
                String[] ids = mCameraManager.getCameraIdList();
                if (ids.length > 0) {
                    mCameraId = ids[0];
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize CameraManager for Torch", e);
        }
    }

    private void toggleTorch() {
        if (mCameraManager == null || mCameraId == null) {
            Toast.makeText(this, "Torch unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mIsTorchOn = !mIsTorchOn;
            mCameraManager.setTorchMode(mCameraId, mIsTorchOn);
            mBtnToggleTorch.setText(mIsTorchOn ? "TORCH: ON" : "TORCH: OFF");
        } catch (Exception e) {
            Log.e(TAG, "Error toggling torch", e);
            mIsTorchOn = false;
            mBtnToggleTorch.setText("TORCH: OFF");
        }
    }

    private void toggleWifi() {
        if (mWifiManager != null) {
            try {
                boolean enabled = !mWifiManager.isWifiEnabled();
                mWifiManager.setWifiEnabled(enabled);
                mBtnToggleWifi.setText(enabled ? "WI-FI: ON" : "WI-FI: OFF");
            } catch (Exception e) {
                Log.e(TAG, "Error toggling WiFi", e);
            }
        }
    }

    private void executeInstantLockdown() {
        if (mPowerManager != null) {
            try {
                // Go to sleep / immediate screen lock
                mPowerManager.goToSleep(SystemClock.uptimeMillis());
            } catch (Exception e) {
                Log.e(TAG, "Lockdown failed", e);
            }
        }
    }

    private void showAdvancedRebootDialog() {
        String[] options = {
            "Reboot System",
            "Reboot to Recovery (TWRP)",
            "Reboot to Bootloader (Fastboot)",
            "Power Off"
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ADVANCED REBOOT // droidOS")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            rebootDevice(null);
                            break;
                        case 1:
                            rebootDevice("recovery");
                            break;
                        case 2:
                            rebootDevice("bootloader");
                            break;
                        case 3:
                            powerOffDevice();
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rebootDevice(String reason) {
        if (mPowerManager != null) {
            try {
                mPowerManager.reboot(reason);
            } catch (Exception e) {
                Log.e(TAG, "Reboot failed", e);
                Toast.makeText(this, "Reboot failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void powerOffDevice() {
        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWifiManager != null) {
            mBtnToggleWifi.setText(mWifiManager.isWifiEnabled() ? "WI-FI: ON" : "WI-FI: OFF");
        }
        mHandler.post(mUpdateTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mUpdateTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
        if (mIsTorchOn) {
            toggleTorch();
        }
    }

    private void updateMetrics() {
        mClockView.setText(mTimeFormat.format(new Date()));

        SystemMonitor.Metrics m = mMonitor.sample();
        mCpuLoadView.setText(m.cpuLoadPercent + " %");
        mCpuFreqView.setText(String.format(Locale.US, "%.1f GHz", m.cpuFreqMhz / 1000.0f));
        mRamUsageView.setText(m.ramUsedMb + " / " + m.ramTotalMb + " MB");
        mBatteryTempView.setText(String.format(Locale.US, "%d%% ( %.1f°C ) %s",
                m.batteryPercent, m.batteryTempC, m.isCharging ? "⚡" : ""));
    }

    private void setUsbConnectedState(boolean connected) {
        if (connected) {
            mUsbStatusView.setText(R.string.status_streaming);
            mUsbDetailsView.setText("High-Speed AOA bulk link established. 60 FPS H.264 stream active.");
            mBtnToggleDesktop.setText(R.string.btn_stop_desktop);
            mIsDesktopActive = true;
        } else {
            mUsbStatusView.setText(R.string.status_disconnected);
            mUsbDetailsView.setText("Connect USB cable to host (macOS / PC) to stream.");
            mBtnToggleDesktop.setText(R.string.btn_start_desktop);
            mIsDesktopActive = false;
        }
    }

    private void toggleDesktopSession() {
        mIsDesktopActive = !mIsDesktopActive;
        setUsbConnectedState(mIsDesktopActive);
    }
}
