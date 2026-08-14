package com.legitcoconut.thanimaticketing.card;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.os.Handler;
import android.os.Looper;

import com.legitcoconut.thanimaticketing.R;

/**
 * What the two Bluetooth transports share: finding the adapter, deciding whether they could
 * work at all, and getting lines and status back onto the main thread. The transports
 * themselves have nothing else in common, which is why there are two of them.
 */
abstract class BtCardReader implements CardReader {

    final Activity activity;
    final BluetoothAdapter adapter;
    final Handler main = new Handler(Looper.getMainLooper());
    final UidLine.Repeat repeat = new UidLine.Repeat();

    OnState onState;
    OnCard onCard;
    volatile boolean running;

    BtCardReader(Activity activity) {
        this.activity = activity;
        BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    @Override
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (adapter == null) return activity.getString(R.string.reader_no_bluetooth);
        // The permission check comes before isEnabled because from Android 12 that call
        // needs BLUETOOTH_CONNECT and throws without it.
        if (!CardReaders.granted(activity, CardReaders.connectPermissions())) {
            return activity.getString(R.string.reader_needs_permission);
        }
        try {
            if (!adapter.isEnabled()) return activity.getString(R.string.reader_bluetooth_off);
        } catch (SecurityException e) {
            return activity.getString(R.string.reader_needs_permission);
        }
        if (CardReaders.mac() == null) return activity.getString(R.string.reader_pick_first);
        return null;
    }

    @Override
    public void setOnState(OnState listener) {
        onState = listener;
    }

    void state(String status, boolean ready) {
        main.post(() -> {
            if (onState != null) onState.onState(status, ready);
        });
    }

    /** One line in, at most one card event out. */
    void deliver(String line) {
        String uid = UidLine.parse(line, CardReaders.reverseUid());
        if (uid == null || repeat.isRepeat(uid)) return;
        main.post(() -> {
            if (onCard != null) onCard.onCard(uid);
        });
    }

    String string(int res, Object... args) {
        return activity.getString(res, args);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
