package com.legitcoconut.thanimaticketing.nfc;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.legitcoconut.thanimaticketing.card.CardReader;

/**
 * Reader mode wrapper around the framework NFC stack. Returns the card UID as uppercase
 * hex with no separators, which is the exact spelling the server stores after
 * normalizeNfcId strips punctuation and uppercases.
 */
public final class NfcReader implements CardReader {

    private static final int FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    private final Activity activity;
    private final NfcAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening;

    public NfcReader(Activity activity) {
        this.activity = activity;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);
    }

    @Override
    public boolean isAvailable() {
        return adapter != null;
    }

    /** Staff readable reason, or null when NFC is usable. */
    @Override
    public String unavailableReason() {
        if (adapter == null) return "This phone has no NFC reader.";
        if (!adapter.isEnabled()) return "NFC is switched off. Turn it on in settings.";
        return null;
    }

    /** Nothing to connect to, so the state is settled the moment anyone asks. */
    @Override
    public void setOnState(OnState listener) {
        String reason = unavailableReason();
        listener.onState(reason == null ? "" : reason, reason == null);
    }

    @Override
    public void start(OnCard listener) {
        if (adapter == null || listening) return;
        Bundle extras = new Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 200);
        adapter.enableReaderMode(activity, tag -> {
            String uid = toHex(tag);
            if (uid != null) main.post(() -> listener.onCard(uid));
        }, FLAGS, extras);
        listening = true;
    }

    @Override
    public void stop() {
        if (adapter == null || !listening) return;
        try {
            adapter.disableReaderMode(activity);
        } catch (Exception ignored) {
        }
        listening = false;
    }

    private static String toHex(Tag tag) {
        byte[] id = tag == null ? null : tag.getId();
        if (id == null || id.length == 0) return null;
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
