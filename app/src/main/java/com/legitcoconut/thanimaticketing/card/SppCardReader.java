package com.legitcoconut.thanimaticketing.card;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import com.legitcoconut.thanimaticketing.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Classic Bluetooth. One RFCOMM socket to a device the phone has already been paired with,
 * then a plain line reader. The ESP side is BluetoothSerial and SerialBT.println(uid).
 */
public final class SppCardReader extends BtCardReader {

    /** The standard serial port service, which is what BluetoothSerial advertises. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int ATTEMPTS = 3;
    private static final long RETRY_MS = 2000;

    private BluetoothSocket socket;
    private Thread worker;

    public SppCardReader(Activity activity) {
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
        repeat.clear();
        worker = new Thread(this::run, "spp-card-reader");
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        // Closing the socket is what breaks readLine out of its block.
        close();
        worker = null;
        onCard = null;
    }

    private void run() {
        String name = CardReaders.deviceName();
        for (int attempt = 1; running && attempt <= ATTEMPTS; attempt++) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(CardReaders.mac());
                state(string(R.string.reader_connecting, name), false);
                // Discovery running in the background makes connecting slow and flaky.
                adapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                state(string(R.string.reader_connected, name), true);
                read();
            } catch (SecurityException e) {
                state(string(R.string.reader_needs_permission), false);
                return;
            } catch (Exception e) {
                // Any failure is the same failure here: drop the socket and try again.
            }
            close();
            if (!running) return;
            if (attempt < ATTEMPTS) {
                state(string(R.string.reader_reconnecting), false);
                sleep(RETRY_MS);
            }
        }
        if (running) state(string(R.string.reader_not_connected), false);
    }

    private void read() throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while (running && (line = in.readLine()) != null) deliver(line);
    }

    private void close() {
        BluetoothSocket open = socket;
        socket = null;
        if (open == null) return;
        try {
            open.close();
        } catch (Exception ignored) {
        }
    }
}
