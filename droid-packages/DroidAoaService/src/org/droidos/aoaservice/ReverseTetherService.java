package org.droidos.aoaservice;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ReverseTetherService extends VpnService {

    private static final String TAG = "droidOS-ReverseTether";
    private static final int MTU = 1500;

    private static ReverseTetherService sInstance;
    private static OutputStream sUsbOutputStream;

    private ParcelFileDescriptor mVpnInterface;
    private FileInputStream mTunInputStream;
    private FileOutputStream mTunOutputStream;
    private Thread mOutgoingThread;
    private volatile boolean mIsRunning = false;

    public static synchronized void start(Context context, OutputStream usbOut) {
        sUsbOutputStream = usbOut;
        Intent intent = new Intent(context, ReverseTetherService.class);
        context.startService(intent);
    }

    public static synchronized void stop(Context context) {
        if (sInstance != null) {
            sInstance.stopVpn();
        }
        sUsbOutputStream = null;
    }

    public static void injectInboundPacket(byte[] packet) {
        if (sInstance != null && sInstance.mTunOutputStream != null && packet != null) {
            try {
                synchronized (sInstance.mTunOutputStream) {
                    sInstance.mTunOutputStream.write(packet);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to write inbound packet to TUN interface", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sInstance = this;
        startVpn();
        return START_STICKY;
    }

    private synchronized void startVpn() {
        if (mIsRunning) return;

        try {
            Log.i(TAG, "Configuring droidOS Reverse Tether VPN interface...");
            Builder builder = new Builder();
            builder.setSession("droidOS USB Reverse Tether")
                   .setMtu(MTU)
                   .addAddress("10.0.0.2", 24)
                   .addRoute("0.0.0.0", 0)       // Capture all IPv4 traffic
                   .addDnsServer("1.1.1.1")
                   .addDnsServer("8.8.8.8");

            // Allow the service itself to bypass VPN so it can communicate directly
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {}

            mVpnInterface = builder.establish();
            if (mVpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface (null PFD)");
                return;
            }

            mTunInputStream = new FileInputStream(mVpnInterface.getFileDescriptor());
            mTunOutputStream = new FileOutputStream(mVpnInterface.getFileDescriptor());

            mIsRunning = true;
            mOutgoingThread = new Thread(this::outgoingTrafficLoop, "droidOS-TunToUsb");
            mOutgoingThread.start();

            Log.i(TAG, "Reverse Tether VPN active. Outbound traffic now routed via USB to host.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting Reverse Tether VPN", e);
            stopVpn();
        }
    }

    private void outgoingTrafficLoop() {
        byte[] packetBuffer = new byte[MTU];
        byte[] headerBuffer = new byte[8];

        while (mIsRunning) {
            try {
                int length = mTunInputStream.read(packetBuffer);
                if (length <= 0) continue;

                // Build 8-byte droidOS packet header
                // Magic: 'D', 'O'
                headerBuffer[0] = 'D';
                headerBuffer[1] = 'O';
                // Type: 0x06 (Network IP packet Device -> Host)
                headerBuffer[2] = 0x06;
                // Flags: 0x00
                headerBuffer[3] = 0x00;
                // Length (4 bytes big-endian)
                headerBuffer[4] = (byte) ((length >> 24) & 0xFF);
                headerBuffer[5] = (byte) ((length >> 16) & 0xFF);
                headerBuffer[6] = (byte) ((length >> 8) & 0xFF);
                headerBuffer[7] = (byte) (length & 0xFF);

                OutputStream usb = sUsbOutputStream;
                if (usb != null) {
                    synchronized (usb) {
                        usb.write(headerBuffer);
                        usb.write(packetBuffer, 0, length);
                        usb.flush();
                    }
                }
            } catch (IOException e) {
                if (mIsRunning) {
                    Log.w(TAG, "TUN read error", e);
                }
                break;
            }
        }
    }

    private synchronized void stopVpn() {
        mIsRunning = false;

        if (mOutgoingThread != null) {
            mOutgoingThread.interrupt();
            mOutgoingThread = null;
        }

        if (mTunInputStream != null) {
            try { mTunInputStream.close(); } catch (Exception ignored) {}
            mTunInputStream = null;
        }

        if (mTunOutputStream != null) {
            try { mTunOutputStream.close(); } catch (Exception ignored) {}
            mTunOutputStream = null;
        }

        if (mVpnInterface != null) {
            try { mVpnInterface.close(); } catch (Exception ignored) {}
            mVpnInterface = null;
        }

        sInstance = null;
        stopSelf();
        Log.i(TAG, "Reverse Tether VPN stopped.");
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
