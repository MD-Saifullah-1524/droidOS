package org.droidos.desktop;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;

public class WindowManagerHelper {

    private static final String TAG = "droidOS-DesktopWM";
    public static final int WINDOWING_MODE_FREEFORM = 5;

    public static void launchApp(Context context, Intent intent, Display display) {
        launchFreeformApp(context, intent, display);
    }

    public static void launchFreeformApp(Context context, Intent intent, Display display) {
        if (intent == null || context == null) return;

        int displayId = (display != null) ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
        if (displayId == Display.DEFAULT_DISPLAY) {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        displayId = d.getDisplayId();
                        display = d;
                        break;
                    }
                }
            }
        }

        int displayWidth = (display != null) ? display.getWidth() : 1280;
        int displayHeight = (display != null) ? display.getHeight() : 720;
        int winWidth = Math.min(displayWidth - 80, 850);
        int winHeight = Math.min(displayHeight - 120, 560);
        int left = (displayWidth - winWidth) / 2;
        int top = (displayHeight - winHeight) / 2;
        Rect bounds = new Rect(left, top, left + winWidth, top + winHeight);

        // Tier 1: Try Freeform windowing mode with bounds & MULTIPLE_TASK to prevent reuse of phone screen task
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            options.setLaunchBounds(bounds);

            try {
                Method setLaunchWindowingMode = ActivityOptions.class.getMethod(
                        "setLaunchWindowingMode", int.class);
                setLaunchWindowingMode.invoke(options, WINDOWING_MODE_FREEFORM);
            } catch (Throwable ignored) {}

            Intent freeformIntent = new Intent(intent);
            freeformIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(freeformIntent, options.toBundle());
            Log.i(TAG, "Launched freeform app on display " + displayId + ": " + intent.getComponent());
            return;
        } catch (Throwable t) {
            Log.w(TAG, "Tier 1 (Freeform) failed: " + t.getMessage() + ", falling back to Tier 2 (Standard target display)...");
        }

        // Tier 2: Standard target display launch (Fullscreen / native display windowing mode)
        try {
            ActivityOptions displayOptions = ActivityOptions.makeBasic();
            displayOptions.setLaunchDisplayId(displayId);

            Intent standardIntent = new Intent(intent);
            standardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(standardIntent, displayOptions.toBundle());
            Log.i(TAG, "Launched standard app on display " + displayId + ": " + intent.getComponent());
            return;
        } catch (Throwable t2) {
            Log.w(TAG, "Tier 2 failed: " + t2.getMessage() + ", falling back to Tier 3 (Unbounded)...");
        }

        // Tier 3: Unbounded launch fallback
        try {
            Intent fallbackIntent = new Intent(intent);
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallbackIntent);
            Log.i(TAG, "Launched fallback app: " + intent.getComponent());
        } catch (Throwable t3) {
            Log.e(TAG, "All launch tiers failed for intent: " + intent, t3);
        }
    }
}
