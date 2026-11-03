package org.droidos.aoaservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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

    private volatile boolean mIsRunning = false;
    private Thread mReadThread;

    @Override
    public void onCreate() {
        super.onCreate();
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mDisplayStreamer = new DisplayStreamer(this);
        mInputBridge = new InputEventBridge();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Awaiting USB Accessory connection..."));

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

        return START_STICKY;
    }

    private void openAccessory(UsbAccessory accessory) {
        if (accessory == null) return;

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
        mDisplayStreamer.start(mOutputStream, 1280, 720, 160, 60, 12_000_000);
        mInputBridge.setTargetDisplay(mDisplayStreamer.getVirtualDisplayId(), 1280, 720);

        // Start reverse tethering VPN (routes phone network traffic over USB to host)
        ReverseTetherService.start(this, mOutputStream);

        mIsRunning = true;
        mReadThread = new Thread(this::readLoop, "droidOS-AoaReader");
        mReadThread.start();
    }

    private void readLoop() {
        byte[] headerBuffer = new byte[8];
        while (mIsRunning) {
            try {
                int read = mInputStream.read(headerBuffer, 0, 8);
                if (read < 8) break;

                // Validate magic 'D', 'O'
                if (headerBuffer[0] != 'D' || headerBuffer[1] != 'O') {
                    continue;
                }

                int type = headerBuffer[2] & 0xFF;
                int flags = headerBuffer[3] & 0xFF;
                int length = ((headerBuffer[4] & 0xFF) << 24) |
                             ((headerBuffer[5] & 0xFF) << 16) |
                             ((headerBuffer[6] & 0xFF) << 8)  |
                              (headerBuffer[7] & 0xFF);

                if (length > 0 && length < 1_000_000) {
                    byte[] payload = new byte[length];
                    int totalRead = 0;
                    while (totalRead < length) {
                        int r = mInputStream.read(payload, totalRead, length - totalRead);
                        if (r < 0) break;
                        totalRead += r;
                    }

                    handlePacket(type, flags, payload);
                }
            } catch (IOException e) {
                if (mIsRunning) {
                    Log.e(TAG, "USB Read error", e);
                }
                break;
            }
        }
        closeAccessory();
    }

    private void handlePacket(int type, int flags, byte[] payload) {
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
                mDisplayStreamer.start(mOutputStream, w, h, dpi, fps, bitrate);
                mInputBridge.setTargetDisplay(mDisplayStreamer.getVirtualDisplayId(), w, h);
            }
        } else if (type == 0x06) { // Reverse Tether Network packet from Host (Mac)
            ReverseTetherService.injectInboundPacket(payload);
        }
    }

    private synchronized void closeAccessory() {
        mIsRunning = false;
        ReverseTetherService.stop(this);
        mDisplayStreamer.stop();

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
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "droidOS AOA Service",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("droidOS Desktop Engine")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildNotification(text));
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
