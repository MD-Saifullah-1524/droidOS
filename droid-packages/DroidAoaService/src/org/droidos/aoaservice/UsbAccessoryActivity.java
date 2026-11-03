package org.droidos.aoaservice;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent trampoline Activity that catches ACTION_USB_ACCESSORY_ATTACHED
 * and starts AoaAccessoryService in the background.
 */
public class UsbAccessoryActivity extends Activity {

    private static final String TAG = "droidOS-AoaActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(UsbManager.EXTRA_ACCESSORY)) {
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            Log.i(TAG, "USB Accessory attached: " + (accessory != null ? accessory.getModel() : "unknown"));

            Intent serviceIntent = new Intent(this, AoaAccessoryService.class);
            serviceIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            startForegroundService(serviceIntent);
        }

        finish();
    }
}
