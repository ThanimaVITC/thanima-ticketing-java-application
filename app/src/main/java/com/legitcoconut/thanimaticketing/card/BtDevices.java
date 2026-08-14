package com.legitcoconut.thanimaticketing.card;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finding a reader to pick. Classic devices must already be paired in Android settings,
 * because an app cannot pair silently, so that side is just a list. Low energy devices are
 * found with a short scan.
 */
@SuppressLint("MissingPermission")
public final class BtDevices {

    /** How long the scan runs before the list is shown. */
    private static final long SCAN_MS = 6000;

    public static final class Entry {
        public final String name;
        public final String mac;

        Entry(String name, String mac) {
            this.name = name == null || name.isEmpty() ? mac : name;
            this.mac = mac;
        }

        public String label() {
            return name.equals(mac) ? mac : name + "\n" + mac;
        }
    }

    private BtDevices() {
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    public static List<Entry> bonded(Context context) {
        List<Entry> out = new ArrayList<>();
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) return out;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                out.add(new Entry(device.getName(), device.getAddress()));
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    /** Scans for a few seconds, then reports every named device that answered, once each. */
    public static void scan(Context context, Consumer<List<Entry>> done) {
        BluetoothAdapter adapter = adapter(context);
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            done.accept(new ArrayList<>());
            return;
        }

        Map<String, Entry> found = new LinkedHashMap<>();
        Handler main = new Handler(Looper.getMainLooper());

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null) return;
                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {
                }
                // An unnamed address is no help to someone choosing their own reader.
                if (name == null || name.isEmpty()) return;
                found.put(device.getAddress(), new Entry(name, device.getAddress()));
            }
        };

        try {
            scanner.startScan(callback);
        } catch (SecurityException e) {
            done.accept(new ArrayList<>());
            return;
        }

        main.postDelayed(() -> {
            try {
                scanner.stopScan(callback);
            } catch (SecurityException ignored) {
            }
            done.accept(new ArrayList<>(found.values()));
        }, SCAN_MS);
    }
}
