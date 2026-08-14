package com.legitcoconut.thanimaticketing.card;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;

import androidx.annotation.NonNull;

import com.legitcoconut.thanimaticketing.R;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bluetooth Low Energy, for the ESP32 variants that dropped the classic radio. Speaks the
 * Nordic UART service, which is what the stock Arduino BLE_uart sketch already advertises,
 * so the firmware side stays a println.
 */
@SuppressLint("MissingPermission")
public final class BleCardReader extends BtCardReader {

    private static final UUID SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NOTIFY = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final int ATTEMPTS = 3;
    private static final long RETRY_MS = 2000;
    private static final int MAX_BUFFER = 256;

    private BluetoothGatt gatt;
    private final StringBuilder buffer = new StringBuilder();
    private int attempt;

    public BleCardReader(Activity activity) {
        super(activity);
    }

    @Override
    public void start(OnCard listener) {
        if (running) return;
        String reason = unavailableReason();
        if (reason != null) {
            state(reason, false);
            return;
        }
        onCard = listener;
        running = true;
        attempt = 0;
        repeat.clear();
        buffer.setLength(0);
        connect();
    }

    @Override
    public void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        close();
        onCard = null;
    }

    private void connect() {
        if (!running) return;
        attempt++;
        try {
            state(string(R.string.reader_connecting, CardReaders.deviceName()), false);
            gatt = adapter.getRemoteDevice(CardReaders.mac())
                    .connectGatt(activity, false, callback);
        } catch (SecurityException e) {
            state(string(R.string.reader_needs_permission), false);
            running = false;
        } catch (Exception e) {
            retryOrGiveUp();
        }
    }

    private void retryOrGiveUp() {
        close();
        if (!running) return;
        if (attempt >= ATTEMPTS) {
            state(string(R.string.reader_not_connected), false);
            return;
        }
        state(string(R.string.reader_reconnecting), false);
        main.postDelayed(this::connect, RETRY_MS);
    }

    private void close() {
        BluetoothGatt open = gatt;
        gatt = null;
        if (open == null) return;
        try {
            open.disconnect();
            open.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Notifications arrive in whatever chunks the radio felt like, so bytes are appended and
     * split on newlines rather than treated as one message each.
     */
    private void onData(byte[] value) {
        if (value == null || value.length == 0) return;
        buffer.append(new String(value, StandardCharsets.US_ASCII));
        int nl;
        while ((nl = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, nl);
            buffer.delete(0, nl + 1);
            deliver(line);
        }
        // A reader that never sends a newline must not grow this without limit.
        if (buffer.length() > MAX_BUFFER) buffer.setLength(0);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices();
                } catch (SecurityException ignored) {
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post(BleCardReader.this::retryOrGiveUp);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService service = g.getService(SERVICE);
            BluetoothGattCharacteristic characteristic =
                    service == null ? null : service.getCharacteristic(NOTIFY);
            if (characteristic == null) {
                state(string(R.string.reader_wrong_service), false);
                running = false;
                main.post(BleCardReader.this::close);
                return;
            }
            try {
                g.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
                attempt = 0;
                state(string(R.string.reader_connected, CardReaders.deviceName()), true);
            } catch (SecurityException e) {
                state(string(R.string.reader_needs_permission), false);
            }
        }

        /** Android 13 and up hands the bytes straight to this one. */
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value) {
            if (NOTIFY.equals(characteristic.getUuid())) onData(value);
        }

        /** Everything below Android 13 comes through here instead. */
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            if (NOTIFY.equals(characteristic.getUuid())) onData(characteristic.getValue());
        }
    };
}
