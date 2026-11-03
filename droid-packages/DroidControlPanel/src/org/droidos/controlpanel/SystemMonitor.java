package org.droidos.controlpanel;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class SystemMonitor {

    private final Context mContext;
    private long mPrevTotal = 0;
    private long mPrevIdle = 0;

    public static class Metrics {
        public int cpuLoadPercent;
        public int cpuFreqMhz;
        public long ramUsedMb;
        public long ramTotalMb;
        public int batteryPercent;
        public float batteryTempC;
        public boolean isCharging;
    }

    public SystemMonitor(Context context) {
        mContext = context;
    }

    public Metrics sample() {
        Metrics m = new Metrics();
        m.cpuLoadPercent = readCpuUsage();
        m.cpuFreqMhz = readCpuFrequency();
        readMemory(m);
        readBattery(m);
        return m;
    }

    private int readCpuFrequency() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))) {
            String line = reader.readLine();
            if (line != null) {
                return Integer.parseInt(line.trim()) / 1000;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int readCpuUsage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                long user = Long.parseLong(parts[1]);
                long nice = Long.parseLong(parts[2]);
                long system = Long.parseLong(parts[3]);
                long idle = Long.parseLong(parts[4]);
                long iowait = Long.parseLong(parts[5]);
                long irq = Long.parseLong(parts[6]);
                long softirq = Long.parseLong(parts[7]);

                long total = user + nice + system + idle + iowait + irq + softirq;
                long totalDiff = total - mPrevTotal;
                long idleDiff = idle - mPrevIdle;

                mPrevTotal = total;
                mPrevIdle = idle;

                if (totalDiff > 0) {
                    return (int) (100 * (totalDiff - idleDiff) / totalDiff);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void readMemory(Metrics m) {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            m.ramTotalMb = mi.totalMem / (1024 * 1024);
            m.ramUsedMb = (mi.totalMem - mi.availMem) / (1024 * 1024);
        }
    }

    private void readBattery(Metrics m) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            m.batteryPercent = (level * 100) / (scale > 0 ? scale : 100);

            int temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            m.batteryTempC = temp / 10.0f;

            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            m.isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL;
        }
    }
}
