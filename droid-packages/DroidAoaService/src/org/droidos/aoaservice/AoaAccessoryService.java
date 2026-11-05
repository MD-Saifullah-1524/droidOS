package org.droidos.aoaservice;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class AoaAccessoryService extends Service {

    private static final String TAG = "droidOS-AoaService";
    private static final String CHANNEL_ID = "droidos_aoa_channel";
    private static final int NOTIF_ID = 4040;

    private UsbManager mUsbManager;
    private ParcelFileDescriptor mFileDescriptor;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;

    private DisplayStreamer mDisplayStreamer;
    private InputEventBridge mInputBridge;
    private DisplayManager mDisplayManager;
    private CursorOverlayController mCursorOverlay;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mIsRunning = false;
    private Thread mReadThread;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "force_desktop_mode_on_external_displays", 1);
            android.provider.Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
            android.provider.Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
        } catch (Throwable t) {
            Log.w(TAG, "Settings.Global putInt failed: " + t.getMessage());
        }

        try {
            mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            mDisplayStreamer = new DisplayStreamer(this);
            mInputBridge = new InputEventBridge();
            mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (mDisplayManager != null) {
                mDisplayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                        if (mDisplayStreamer != null && displayId == mDisplayStreamer.getVirtualDisplayId()) {
                            Log.i(TAG, "DisplayListener.onDisplayAdded for virtual display " + displayId);
                            setupCursorOverlay(displayId, mDisplayStreamer.getWidth(), mDisplayStreamer.getHeight());
                            launchDesktopActivity(displayId);
                        }
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {}

                    @Override
                    public void onDisplayChanged(int displayId) {}
                }, mMainHandler);
            }
            createNotificationChannel();
        } catch (Throwable t) {
            Log.e(TAG, "Error in onCreate: " + t.getMessage(), t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification("Awaiting USB Accessory connection..."));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to startForeground: " + t.getMessage());
        }

        try {
            if (intent != null && intent.hasExtra(UsbManager.EXTRA_ACCESSORY)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                openAccessory(accessory);
            } else {
                // Check connected accessories
                UsbAccessory[] accessories = mUsbManager.getAccessoryList();
                if (accessories != null && accessories.length > 0) {
                    openAccessory(accessories[0]);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onStartCommand: " + t.getMessage(), t);
        }

        return START_STICKY;
    }

    private synchronized void openAccessory(UsbAccessory accessory) {
        if (accessory == null) return;
        if (mFileDescriptor != null) {
            Log.i(TAG, "Accessory already open, resetting previous session...");
            closeAccessory();
        }

        try {
            Log.i(TAG, "Opening accessory: " + accessory.getModel() + " by " + accessory.getManufacturer());
            mFileDescriptor = mUsbManager.openAccessory(accessory);
            if (mFileDescriptor == null) {
                Log.e(TAG, "Failed to open UsbAccessory file descriptor");
                return;
            }

            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);

            updateNotification("Streaming desktop session via USB (AOA 2.0)");

            // Start default display stream (1280x720 @ 60fps)
            boolean ok = mDisplayStreamer.start(mOutputStream, 1280, 720, 160, 60, 12_000_000);
            if (ok) {
                int displayId = mDisplayStreamer.getVirtualDisplayId();
                int width = mDisplayStreamer.getWidth();
                int height = mDisplayStreamer.getHeight();
                mInputBridge.setTargetDisplay(displayId, width, height);
                setupCursorOverlay(displayId, width, height);
                launchDesktopActivity(displayId);
            }

            // Start reverse tethering VPN safely
            try {
                ReverseTetherService.start(this, mOutputStream);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to start ReverseTetherService: " + t.getMessage());
            }

            mIsRunning = true;
            mReadThread = new Thread(this::readLoop, "droidOS-AoaReader");
            mReadThread.start();
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in openAccessory: " + t.getMessage(), t);
            closeAccessory();
        }
    }

    private void readLoop() {
        byte[] rxBuffer = new byte[16384];
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

        while (mIsRunning && mInputStream != null) {
            try {
                int bytesRead = mInputStream.read(rxBuffer, 0, rxBuffer.length);
                if (bytesRead < 0) {
                    Log.i(TAG, "End of USB input stream reached");
                    break;
                }
                if (bytesRead == 0) continue;

                accumulator.write(rxBuffer, 0, bytesRead);
                byte[] data = accumulator.toByteArray();
                int offset = 0;

                while (offset + 8 <= data.length) {
                    // Check magic 'D', 'O'
                    if (data[offset] != 'D' || data[offset + 1] != 'O') {
                        offset++;
                        continue;
                    }

                    int type = data[offset + 2] & 0xFF;
                    int flags = data[offset + 3] & 0xFF;
                    int length = ((data[offset + 4] & 0xFF) << 24) |
                                 ((data[offset + 5] & 0xFF) << 16) |
                                 ((data[offset + 6] & 0xFF) << 8)  |
                                  (data[offset + 7] & 0xFF);

                    if (length < 0 || length > 1_000_000) {
                        // Invalid length, skip past corrupted magic
                        offset += 2;
                        continue;
                    }

                    if (offset + 8 + length > data.length) {
                        // Incomplete payload, wait for next USB transfer
                        break;
                    }

                    byte[] payload = new byte[length];
                    System.arraycopy(data, offset + 8, payload, 0, length);
                    handlePacket(type, flags, payload);

                    offset += 8 + length;
                }

                // Keep remaining unprocessed bytes in accumulator
                accumulator.reset();
                if (offset < data.length) {
                    accumulator.write(data, offset, data.length - offset);
                }
            } catch (Throwable e) {
                if (mIsRunning) {
                    Log.w(TAG, "USB Read error: " + e.getMessage());
                }
                break;
            }
        }
        closeAccessory();
    }

    private void handlePacket(int type, int flags, byte[] payload) {
        try {
            if (type == 0x03) { // Mouse
                mInputBridge.handleMousePacket(payload);
            } else if (type == 0x04) { // Keyboard
                mInputBridge.handleKeyboardPacket(payload);
            } else if (type == 0x05) { // Config (resolution renegotiation)
                if (payload.length >= 12) {
                    int w = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    int h = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                    int dpi = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
                    int fps = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
                    int bitrate = ((payload[8] & 0xFF) << 24) | ((payload[9] & 0xFF) << 16) |
                                  ((payload[10] & 0xFF) << 8) | (payload[11] & 0xFF);

                    Log.i(TAG, "Host requested display config: " + w + "x" + h + " @" + fps + "fps");
                    boolean ok = mDisplayStreamer.start(mOutputStream, w, h, dpi, fps, bitrate);
                    if (ok) {
                        int displayId = mDisplayStreamer.getVirtualDisplayId();
                        int width = mDisplayStreamer.getWidth();
                        int height = mDisplayStreamer.getHeight();
                        mInputBridge.setTargetDisplay(displayId, width, height);
                        setupCursorOverlay(displayId, width, height);
                        launchDesktopActivity(displayId);
                    }
                }
            } else if (type == 0x06) { // Reverse Tether Network packet from Host (Mac)
                ReverseTetherService.injectInboundPacket(payload);
            }
        } catch (MediaCodec.CodecException ce) {
            Log.e(TAG, "MediaCodec.CodecException while handling packet type " + type
                    + " (error=" + ce.getErrorCode() + " / 0x" + Integer.toHexString(ce.getErrorCode()) + "): " + ce.getMessage(), ce);
        } catch (Throwable t) {
            Log.w(TAG, "Error handling packet type " + type + ": " + t.getMessage(), t);
        }
    }

    private void launchDesktopActivity(int displayId) {
        if (displayId <= 0) return;
        mMainHandler.post(() -> doLaunchDesktop(displayId));
        mMainHandler.postDelayed(() -> doLaunchDesktop(displayId), 250);
    }

    private void doLaunchDesktop(int displayId) {
        if (displayId <= 0 || !mIsRunning) return;
        try {
            Log.i(TAG, "Launching DroidDesktop on display " + displayId);
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName("org.droidos.desktop", "org.droidos.desktop.DesktopActivity"));
            // Launch as a standard top-level application so WindowManager does not abort via
            // canStartHomeOnDisplayArea if secondary home restrictions are active
            intent.addCategory(Intent.CATEGORY_SECONDARY_HOME);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);

            startActivity(intent, options.toBundle());
            Log.i(TAG, "Successfully started DroidDesktop on display " + displayId);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch DroidDesktop on display " + displayId + ": " + t.getMessage(), t);
        }
    }

    private void setupCursorOverlay(int displayId, int width, int height) {
        if (mInputBridge != null && mInputBridge.hasNativeMouse()) {
            // Hardware cursor sprite is natively composited on the secondary display by SurfaceFlinger
            if (mCursorOverlay != null) {
                mCursorOverlay.hide();
                mCursorOverlay = null;
            }
            mInputBridge.setCursorOverlay(null);
            return;
        }

        if (mDisplayManager != null && displayId > 0) {
            Display display = mDisplayManager.getDisplay(displayId);
            if (display != null) {
                if (mCursorOverlay != null) {
                    mCursorOverlay.hide();
                }
                mCursorOverlay = new CursorOverlayController(this, display, width, height);
                mCursorOverlay.show();
                mInputBridge.setCursorOverlay(mCursorOverlay);
            }
        }
    }

    private synchronized void closeAccessory() {
        mIsRunning = false;
        if (mCursorOverlay != null) {
            try { mCursorOverlay.hide(); } catch (Throwable ignored) {}
            mCursorOverlay = null;
        }
        mInputBridge.setCursorOverlay(null);
        mInputBridge.close();
        try { ReverseTetherService.stop(this); } catch (Throwable ignored) {}
        try { mDisplayStreamer.stop(); } catch (Throwable ignored) {}

        try {
            if (mInputStream != null) mInputStream.close();
            if (mOutputStream != null) mOutputStream.close();
            if (mFileDescriptor != null) mFileDescriptor.close();
        } catch (IOException ignored) {}

        mInputStream = null;
        mOutputStream = null;
        mFileDescriptor = null;
        updateNotification("Accessory disconnected");
        Log.i(TAG, "Accessory session terminated.");
    }

    private void createNotificationChannel() {
        try {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "droidOS AOA Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to createNotificationChannel: " + t.getMessage());
        }
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("droidOS Desktop Engine")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_aoa_stat)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to updateNotification: " + t.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        closeAccessory();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
