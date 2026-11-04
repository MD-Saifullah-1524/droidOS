package org.droidos.aoaservice;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class DisplayStreamer {

    private static final String TAG = "droidOS-Streamer";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private MediaCodec mCodec;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private OutputStream mOutputStream;
    private volatile boolean mIsStreaming = false;
    private Thread mStreamThread;

    private int mWidth = 1280;
    private int mHeight = 720;
    private int mDpi = 160;
    private int mFps = 60;
    private int mBitrate = 12_000_000;

    public DisplayStreamer(Context context) {
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    public synchronized boolean start(OutputStream outputStream, int width, int height, int dpi, int fps, int bitrate) {
        if (mIsStreaming) {
            stop();
        }

        mOutputStream = outputStream;

        // Clamp and align dimensions to 16 pixels (required by Qualcomm hardware encoders)
        if (width > 0) mWidth = Math.min(1920, Math.max(320, (width / 16) * 16));
        if (height > 0) mHeight = Math.min(1080, Math.max(240, (height / 16) * 16));
        if (dpi > 0) mDpi = Math.min(480, Math.max(120, dpi));
        if (fps > 0) mFps = Math.min(60, Math.max(15, fps));
        if (bitrate > 0) mBitrate = Math.min(25_000_000, Math.max(1_000_000, bitrate));

        Log.i(TAG, "Starting DisplayStreamer: " + mWidth + "x" + mHeight + " @" + mFps + "fps, " + mBitrate + " bps");

        // Tier 1: Try requested dimensions with default (hardware) encoder
        boolean success = tryInitCodec(mWidth, mHeight, false);

        // Tier 2: If custom resolution failed, fallback to standard 1280x720 baseline
        if (!success && (mWidth != 1280 || mHeight != 720)) {
            Log.w(TAG, "Custom resolution failed, retrying with standard 1280x720 baseline...");
            mWidth = 1280;
            mHeight = 720;
            success = tryInitCodec(1280, 720, false);
        }

        // Tier 3: If hardware encoder still failed, fallback to software Google AVC encoder
        if (!success) {
            Log.w(TAG, "Hardware encoder failed, falling back to software AVC encoder...");
            success = tryInitCodec(mWidth, mHeight, true);
        }

        if (!success) {
            Log.e(TAG, "Failed to initialize any AVC encoder. Desktop streaming aborted.");
            stop();
            return false;
        }

        return true;
    }

    private boolean tryInitCodec(int width, int height, boolean useSoftwareFallback) {
        try {
            cleanupCodec();

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // VBR is universally supported across Qualcomm and software encoders
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

            try {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
            } catch (Throwable ignored) {}

            if (useSoftwareFallback) {
                try {
                    mCodec = MediaCodec.createByCodecName("c2.android.avc.encoder");
                } catch (Throwable t) {
                    mCodec = MediaCodec.createByCodecName("OMX.google.h264.encoder");
                }
            } else {
                mCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            }

            mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mCodec.createInputSurface();
            mCodec.start();

            // Create Android 11 VirtualDisplay
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

            mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                    "droidOS-Desktop",
                    width,
                    height,
                    mDpi,
                    mInputSurface,
                    flags
            );

            if (mVirtualDisplay == null || mVirtualDisplay.getDisplay() == null) {
                Log.e(TAG, "DisplayManager.createVirtualDisplay returned null");
                cleanupCodec();
                return false;
            }

            mIsStreaming = true;
            mStreamThread = new Thread(this::drainLoop, "droidOS-VideoDrain");
            mStreamThread.start();
            Log.i(TAG, "VirtualDisplay created with ID: " + mVirtualDisplay.getDisplay().getDisplayId());
            return true;

        } catch (Throwable t) {
            Log.w(TAG, "Codec initialization attempt failed (sw=" + useSoftwareFallback + "): " + t.getMessage());
            cleanupCodec();
            return false;
        }
    }

    private void cleanupCodec() {
        if (mVirtualDisplay != null) {
            try { mVirtualDisplay.release(); } catch (Throwable ignored) {}
            mVirtualDisplay = null;
        }
        if (mInputSurface != null) {
            try { mInputSurface.release(); } catch (Throwable ignored) {}
            mInputSurface = null;
        }
        if (mCodec != null) {
            try { mCodec.stop(); } catch (Throwable ignored) {}
            try { mCodec.release(); } catch (Throwable ignored) {}
            mCodec = null;
        }
    }

    public synchronized void stop() {
        mIsStreaming = false;
        if (mStreamThread != null) {
            try {
                mStreamThread.join(500);
            } catch (InterruptedException ignored) {}
            mStreamThread = null;
        }

        cleanupCodec();
        Log.i(TAG, "DisplayStreamer stopped.");
    }

    public int getVirtualDisplayId() {
        return (mVirtualDisplay != null && mVirtualDisplay.getDisplay() != null)
                ? mVirtualDisplay.getDisplay().getDisplayId()
                : -1;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        byte[] headerBuffer = new byte[8];

        while (mIsStreaming && mCodec != null) {
            try {
                int outIndex = mCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIndex >= 0) {
                    ByteBuffer encodedData = mCodec.getOutputBuffer(outIndex);
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);

                        byte[] chunk = new byte[bufferInfo.size];
                        encodedData.get(chunk);

                        // Build 8-byte droidOS packet header
                        headerBuffer[0] = 'D';
                        headerBuffer[1] = 'O';
                        headerBuffer[2] = 0x01; // Video
                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        headerBuffer[3] = (byte) (isKeyFrame ? 1 : 0);
                        int len = bufferInfo.size;
                        headerBuffer[4] = (byte) ((len >> 24) & 0xFF);
                        headerBuffer[5] = (byte) ((len >> 16) & 0xFF);
                        headerBuffer[6] = (byte) ((len >> 8) & 0xFF);
                        headerBuffer[7] = (byte) (len & 0xFF);

                        synchronized (this) {
                            if (mOutputStream != null) {
                                mOutputStream.write(headerBuffer);
                                mOutputStream.write(chunk);
                                mOutputStream.flush();
                            }
                        }
                    }
                    if (mCodec != null) {
                        mCodec.releaseOutputBuffer(outIndex, false);
                    }
                }
            } catch (Throwable t) {
                if (mIsStreaming) {
                    Log.w(TAG, "Video drain loop encountered error: " + t.getMessage());
                }
                break;
            }
        }
    }
}
