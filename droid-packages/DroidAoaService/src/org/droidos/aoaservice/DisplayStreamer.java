package org.droidos.aoaservice;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
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

    public synchronized void start(OutputStream outputStream, int width, int height, int dpi, int fps, int bitrate) {
        if (mIsStreaming) {
            stop();
        }

        mOutputStream = outputStream;
        if (width > 0) mWidth = width;
        if (height > 0) mHeight = height;
        if (dpi > 0) mDpi = dpi;
        if (fps > 0) mFps = fps;
        if (bitrate > 0) mBitrate = bitrate;

        Log.i(TAG, "Starting DisplayStreamer: " + mWidth + "x" + mHeight + " @" + mFps + "fps, " + mBitrate + " bps");

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            // Low-latency tuning
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);

            mCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mCodec.createInputSurface();
            mCodec.start();

            // Create Android 11 VirtualDisplay
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

            mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                    "droidOS-Desktop",
                    mWidth,
                    mHeight,
                    mDpi,
                    mInputSurface,
                    flags
            );

            mIsStreaming = true;
            mStreamThread = new Thread(this::drainLoop, "droidOS-VideoDrain");
            mStreamThread.start();
            Log.i(TAG, "VirtualDisplay created with ID: " + mVirtualDisplay.getDisplay().getDisplayId());

        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize MediaCodec", e);
            stop();
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

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }

        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
            } catch (Exception ignored) {}
            mCodec = null;
        }
        Log.i(TAG, "DisplayStreamer stopped.");
    }

    public int getVirtualDisplayId() {
        return mVirtualDisplay != null ? mVirtualDisplay.getDisplay().getDisplayId() : -1;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        byte[] headerBuffer = new byte[8];

        while (mIsStreaming) {
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
                        // Magic: 'D', 'O'
                        headerBuffer[0] = 'D';
                        headerBuffer[1] = 'O';
                        // Type: 0x01 (Video)
                        headerBuffer[2] = 0x01;
                        // Flags: Bit 0 for keyframe
                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        headerBuffer[3] = (byte) (isKeyFrame ? 1 : 0);
                        // Length (4 bytes big-endian)
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
                    mCodec.releaseOutputBuffer(outIndex, false);
                }
            } catch (Exception e) {
                if (mIsStreaming) {
                    Log.e(TAG, "Error streaming frame over USB", e);
                }
                break;
            }
        }
    }
}
