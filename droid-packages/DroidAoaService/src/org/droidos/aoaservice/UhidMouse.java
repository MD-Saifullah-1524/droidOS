package org.droidos.aoaservice;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UhidMouse {
    private static final String TAG = "droidOS-UhidMouse";
    private static final String UHID_PATH = "/dev/uhid";

    // UHID event types from <linux/uhid.h>
    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;

    // Standard 5-button HID mouse report descriptor with relative X/Y and vertical/horizontal wheel
    private static final byte[] MOUSE_REPORT_DESC = new byte[] {
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x09, 0x02,                    // Usage (Mouse)
        (byte) 0xa1, 0x01,             // Collection (Application)
        (byte) 0x85, 0x01,             //   Report ID (1)
        0x09, 0x01,                    //   Usage (Pointer)
        (byte) 0xa1, 0x00,             //   Collection (Physical)
        0x05, 0x09,                    //     Usage Page (Button)
        0x19, 0x01,                    //     Usage Minimum (1)
        0x29, 0x05,                    //     Usage Maximum (5)
        0x15, 0x00,                    //     Logical Minimum (0)
        0x25, 0x01,                    //     Logical Maximum (1)
        0x75, 0x01,                    //     Report Size (1)
        (byte) 0x95, 0x05,             //     Report Count (5)
        (byte) 0x81, 0x02,             //     Input (Data, Variable, Absolute) - 5 buttons
        0x75, 0x03,                    //     Report Size (3)
        (byte) 0x95, 0x01,             //     Report Count (1)
        (byte) 0x81, 0x01,             //     Input (Constant) - 3 padding bits
        0x05, 0x01,                    //     Usage Page (Generic Desktop)
        0x09, 0x30,                    //     Usage (X)
        0x09, 0x31,                    //     Usage (Y)
        0x16, 0x01, (byte) 0x80,       //     Logical Minimum (-32767)
        0x26, (byte) 0xff, 0x7f,       //     Logical Maximum (32767)
        0x75, 0x10,                    //     Report Size (16)
        (byte) 0x95, 0x02,             //     Report Count (2)
        (byte) 0x81, 0x06,             //     Input (Data, Variable, Relative) - X & Y
        0x09, 0x38,                    //     Usage (Wheel)
        0x15, (byte) 0x81,             //     Logical Minimum (-127)
        0x25, 0x7f,                    //     Logical Maximum (127)
        0x75, 0x08,                    //     Report Size (8)
        (byte) 0x95, 0x01,             //     Report Count (1)
        (byte) 0x81, 0x06,             //     Input (Data, Variable, Relative) - Wheel
        0x05, 0x0c,                    //     Usage Page (Consumer)
        0x0a, 0x38, 0x02,              //     Usage (AC Pan)
        0x15, (byte) 0x81,             //     Logical Minimum (-127)
        0x25, 0x7f,                    //     Logical Maximum (127)
        0x75, 0x08,                    //     Report Size (8)
        (byte) 0x95, 0x01,             //     Report Count (1)
        (byte) 0x81, 0x06,             //     Input (Data, Variable, Relative) - Pan
        (byte) 0xc0,                   //   End Collection
        (byte) 0xc0                    // End Collection
    };

    private RandomAccessFile mUhidFile;
    private volatile boolean mIsOpen = false;
    private Thread mReaderThread;
    private int mCurrentButtons = 0;

    public synchronized boolean open() {
        if (mIsOpen) return true;

        File dev = new File(UHID_PATH);
        if (!dev.exists()) {
            Log.e(TAG, UHID_PATH + " does not exist!");
            return false;
        }

        if (!dev.canWrite()) {
            Log.w(TAG, UHID_PATH + " is not writable, attempting chmod 666 via su...");
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 /dev/uhid /dev/uinput"});
                p.waitFor();
            } catch (Throwable t) {
                Log.w(TAG, "su chmod failed: " + t.getMessage());
            }
        }

        try {
            mUhidFile = new RandomAccessFile(dev, "rw");
            createDevice();
            mIsOpen = true;

            mReaderThread = new Thread(this::readLoop, "droidOS-UhidReader");
            mReaderThread.setDaemon(true);
            mReaderThread.start();

            Log.i(TAG, "UHID virtual mouse opened successfully");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed opening /dev/uhid: " + t.getMessage(), t);
            close();
            return false;
        }
    }

    public boolean isOpen() {
        return mIsOpen;
    }

    private void createDevice() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(4376).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(UHID_CREATE2);

        byte[] nameBytes = "droidOS Virtual Mouse".getBytes("UTF-8");
        byte[] nameField = new byte[128];
        System.arraycopy(nameBytes, 0, nameField, 0, Math.min(nameBytes.length, 127));
        buf.put(nameField);

        byte[] physField = new byte[64];
        byte[] physBytes = "droidos/uhid0".getBytes("UTF-8");
        System.arraycopy(physBytes, 0, physField, 0, Math.min(physBytes.length, 63));
        buf.put(physField);

        byte[] uniqField = new byte[64];
        buf.put(uniqField);

        buf.putShort((short) MOUSE_REPORT_DESC.length); // rd_size
        buf.putShort((short) 3); // bus = BUS_USB
        buf.putInt(0x18d1); // vendor = Google
        buf.putInt(0x4ee2); // product
        buf.putInt(1); // version
        buf.putInt(0); // country

        byte[] descField = new byte[4096];
        System.arraycopy(MOUSE_REPORT_DESC, 0, descField, 0, MOUSE_REPORT_DESC.length);
        buf.put(descField);

        mUhidFile.write(buf.array());
    }

    public synchronized void sendReport(int buttons, int dx, int dy, int vScroll, int hScroll) {
        if (!mIsOpen || mUhidFile == null) {
            if (!open()) return;
        }

        mCurrentButtons = buttons;
        try {
            ByteBuffer buf = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(UHID_INPUT2);
            buf.putShort((short) 8);

            buf.put((byte) 0x01); // Report ID 1
            buf.put((byte) (buttons & 0x1F)); // 5 buttons
            buf.putShort((short) Math.max(-32767, Math.min(32767, dx)));
            buf.putShort((short) Math.max(-32767, Math.min(32767, dy)));
            buf.put((byte) Math.max(-127, Math.min(127, vScroll)));
            buf.put((byte) Math.max(-127, Math.min(127, hScroll)));

            mUhidFile.write(buf.array());
        } catch (Throwable t) {
            Log.w(TAG, "sendReport failed: " + t.getMessage());
        }
    }

    public synchronized int getCurrentButtons() {
        return mCurrentButtons;
    }

    public synchronized void setButtons(int buttons) {
        sendReport(buttons, 0, 0, 0, 0);
    }

    public void sendMove(int dx, int dy) {
        sendReport(mCurrentButtons, dx, dy, 0, 0);
    }

    public void setButton(int buttonIndex, boolean pressed) {
        int mask = (1 << buttonIndex);
        int newButtons = pressed ? (mCurrentButtons | mask) : (mCurrentButtons & ~mask);
        sendReport(newButtons, 0, 0, 0, 0);
    }

    public void sendScroll(int vScroll) {
        sendReport(mCurrentButtons, 0, 0, vScroll, 0);
    }

    private void readLoop() {
        byte[] buffer = new byte[4376];
        try {
            while (mIsOpen && mUhidFile != null) {
                int read = mUhidFile.read(buffer);
                if (read <= 0) break;
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized void close() {
        mIsOpen = false;
        if (mUhidFile != null) {
            try {
                ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                buf.putInt(UHID_DESTROY);
                mUhidFile.write(buf.array());
            } catch (Throwable ignored) {}
            try {
                mUhidFile.close();
            } catch (Throwable ignored) {}
            mUhidFile = null;
        }
    }
}
