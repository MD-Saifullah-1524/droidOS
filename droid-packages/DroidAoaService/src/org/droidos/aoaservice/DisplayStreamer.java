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

    // Snapdragon 450 (msm8953) hardware constraints from media_codecs_8937.xml:
    // Max macroblocks per second: 244,800 (16x16 blocks)
    private static final int MAX_HW_BLOCKS_PER_SECOND = 244_800;
    // Hardware encoder max bitrate: 20 Mbps
    private static final int MAX_HW_BITRATE = 20_000_000;
    // Software encoder safe max bitrate: 10 Mbps
    private static final int MAX_SW_BITRATE = 10_000_000;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private MediaCodec mCodec;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private OutputStream mOutputStream;
    private volatile boolean mIsStreaming = false;
    private Thread mStreamThread;
    private byte[] mCodecConfig = null;

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
        try {
            if (mIsStreaming) {
                stop();
            }

            mOutputStream = outputStream;

            // Clamp and ensure dimensions are multiples of 16 (required by Qualcomm hardware encoders)
            if (width > 0) mWidth = Math.min(1920, Math.max(320, (width / 16) * 16));
            if (height > 0) mHeight = Math.min(1080, Math.max(240, (height / 16) * 16));
            if (dpi > 0) mDpi = Math.min(480, Math.max(120, dpi));

            // Macroblocks (16x16 blocks)
            int mb = ((mWidth + 15) / 16) * ((mHeight + 15) / 16);
            int maxHwFps = (mb > 0) ? (MAX_HW_BLOCKS_PER_SECOND / mb) : 30;
            int reqFps = (fps > 0) ? Math.min(60, Math.max(15, fps)) : 60;
            mFps = Math.min(reqFps, maxHwFps);

            int reqBitrate = (bitrate > 0) ? Math.min(MAX_HW_BITRATE, Math.max(1_000_000, bitrate)) : 12_000_000;
            mBitrate = reqBitrate;

            Log.i(TAG, "Starting DisplayStreamer: requested " + width + "x" + height + " @" + fps + "fps, adjusted to "
                    + mWidth + "x" + mHeight + " @" + mFps + "fps, " + mBitrate + " bps");

            // Tier 1: Try requested dimensions with default (hardware) encoder
            boolean success = tryInitCodec(null, mWidth, mHeight, mFps, mBitrate);

            // Tier 2: Fallback to baseline 1280x720 with hardware encoder if Tier 1 failed or was non-baseline
            if (!success && (mWidth != 1280 || mHeight != 720 || mFps != 60)) {
                Log.w(TAG, "Tier 1 failed, retrying Tier 2: standard 1280x720 baseline @ 60fps with hardware encoder...");
                mWidth = 1280;
                mHeight = 720;
                mFps = 60;
                mBitrate = Math.min(12_000_000, reqBitrate);
                success = tryInitCodec(null, 1280, 720, 60, mBitrate);
            }

            // Tier 3: Software Codec 2 encoder (c2.android.avc.encoder)
            if (!success) {
                Log.w(TAG, "Hardware encoder failed, trying Tier 3: software Codec 2 (c2.android.avc.encoder)...");
                mWidth = 1280;
                mHeight = 720;
                mFps = 30;
                mBitrate = Math.min(MAX_SW_BITRATE, 8_000_000);
                success = tryInitCodec("c2.android.avc.encoder", 1280, 720, 30, mBitrate);
            }

            // Tier 4: Software OpenMAX encoder (OMX.google.h264.encoder)
            if (!success) {
                Log.w(TAG, "Codec 2 failed, trying Tier 4: software OpenMAX (OMX.google.h264.encoder)...");
                mWidth = 1280;
                mHeight = 720;
                mFps = 30;
                mBitrate = Math.min(MAX_SW_BITRATE, 6_000_000);
                success = tryInitCodec("OMX.google.h264.encoder", 1280, 720, 30, mBitrate);
            }

            if (!success) {
                Log.e(TAG, "Failed to initialize any AVC encoder. Desktop streaming aborted.");
                stop();
                return false;
            }

            return true;
        } catch (MediaCodec.CodecException ce) {
            Log.e(TAG, "Unhandled CodecException in DisplayStreamer.start (error=" + ce.getErrorCode()
                    + " / 0x" + Integer.toHexString(ce.getErrorCode()) + "): " + ce.getMessage(), ce);
            stop();
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in DisplayStreamer.start: " + t.getMessage(), t);
            stop();
            return false;
        }
    }

    private boolean tryInitCodec(String codecName, int width, int height, int fps, int bitrate) {
        try {
            cleanupCodec();

            // Strict alignment to 16 pixels (required by Qualcomm hardware encoders)
            int alignedWidth = Math.max(16, (width / 16) * 16);
            int alignedHeight = Math.max(16, (height / 16) * 16);

            // Mandatory AVC encoder format keys
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, alignedWidth, alignedHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // VBR is universally supported across Qualcomm and software encoders
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

            try {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
            } catch (Throwable ignored) {}

            try {
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            } catch (Throwable ignored) {}

            if (codecName != null) {
                mCodec = MediaCodec.createByCodecName(codecName);
            } else {
                mCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            }

            // Explicit try/catch around MediaCodec.configure to catch and log CodecException
            try {
                mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (MediaCodec.CodecException ce) {
                Log.w(TAG, "MediaCodec.configure failed with CodecException (error=" + ce.getErrorCode()
                        + " / 0x" + Integer.toHexString(ce.getErrorCode()) + ", recoverable=" + ce.isRecoverable()
                        + ", transient=" + ce.isTransient() + "): " + ce.getMessage());
                cleanupCodec();
                return false;
            }

            mInputSurface = mCodec.createInputSurface();
            mCodec.start();

            // Create Android 11 VirtualDisplay with complete system decoration & secure desktop support
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;

            mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                    "droidOS-Desktop",
                    alignedWidth,
                    alignedHeight,
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
            Log.i(TAG, "VirtualDisplay created with ID: " + mVirtualDisplay.getDisplay().getDisplayId()
                    + " using codec " + (codecName != null ? codecName : "default-hw") + " at "
                    + alignedWidth + "x" + alignedHeight + " @" + fps + "fps, " + bitrate + " bps");
            return true;

        } catch (MediaCodec.CodecException ce) {
            Log.w(TAG, "tryInitCodec CodecException (codec=" + codecName + ", "
                    + width + "x" + height + " @" + fps + "fps, " + bitrate + " bps): error=" + ce.getErrorCode()
                    + " / 0x" + Integer.toHexString(ce.getErrorCode()) + " - " + ce.getMessage());
            cleanupCodec();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Codec initialization attempt failed (codec=" + codecName + ", "
                    + width + "x" + height + " @" + fps + "fps, " + bitrate + " bps): " + t.getMessage());
            cleanupCodec();
            return false;
        }
    }

    private void cleanupCodec() {
        mCodecConfig = null;
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

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
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

                        boolean isCodecConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (isCodecConfig) {
                            mCodecConfig = chunk.clone();
                        }

                        byte[] payload = chunk;

                        // If it's a keyframe and does not already include SPS, prepend cached codec config
                        if (isKeyFrame && mCodecConfig != null && !hasSpsPrefix(chunk)) {
                            payload = new byte[mCodecConfig.length + chunk.length];
                            System.arraycopy(mCodecConfig, 0, payload, 0, mCodecConfig.length);
                            System.arraycopy(chunk, 0, payload, mCodecConfig.length, chunk.length);
                        }

                        // Build 8-byte droidOS packet header
                        headerBuffer[0] = 'D';
                        headerBuffer[1] = 'O';
                        headerBuffer[2] = 0x01; // Video
                        // Both codec config (SPS/PPS) and keyframes must be marked with flags=1
                        // so macOS VideoToolbox parses SPS/PPS and initializes CMVideoFormatDescription
                        headerBuffer[3] = (byte) ((isKeyFrame || isCodecConfig) ? 1 : 0);
                        int len = payload.length;
                        headerBuffer[4] = (byte) ((len >> 24) & 0xFF);
                        headerBuffer[5] = (byte) ((len >> 16) & 0xFF);
                        headerBuffer[6] = (byte) ((len >> 8) & 0xFF);
                        headerBuffer[7] = (byte) (len & 0xFF);

                        // Synchronize on the shared OutputStream instance to prevent packet interleaving
                        OutputStream out = mOutputStream;
                        if (out != null) {
                            synchronized (out) {
                                out.write(headerBuffer);
                                out.write(payload);
                                out.flush();
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

    private static boolean hasSpsPrefix(byte[] data) {
        if (data == null || data.length < 5) return false;
        // Check for 00 00 00 01 NAL header with nal_unit_type == 7 (SPS)
        if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            int nalType = data[4] & 0x1F;
            return nalType == 7;
        }
        // Check for 00 00 01 NAL header with nal_unit_type == 7 (SPS)
        if (data[0] == 0 && data[1] == 0 && data[2] == 1) {
            int nalType = data[3] & 0x1F;
            return nalType == 7;
        }
        return false;
    }
}
