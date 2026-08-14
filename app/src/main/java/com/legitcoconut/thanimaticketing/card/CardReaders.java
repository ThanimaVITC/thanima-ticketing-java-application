package com.legitcoconut.thanimaticketing.card;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.net.Store;
import com.legitcoconut.thanimaticketing.nfc.NfcReader;

/**
 * The one place that knows which reader is selected. Screens ask for a CardReader and get
 * whichever one the settings say, so adding a transport never touches a screen.
 *
 * The choice is not cleared on logout: a door phone paired to a reader should still be
 * paired to it after the next shift signs in.
 */
public final class CardReaders {

    public static final String SOURCE_NFC = "nfc";
    public static final String SOURCE_BT = "bt";

    public static final String PROTOCOL_SPP = "spp";
    public static final String PROTOCOL_BLE = "ble";

    private CardReaders() {
    }

    // ------------------------------------------------------------------ settings

    public static String source() {
        return Store.get(Store.KEY_READER_SOURCE, SOURCE_NFC);
    }

    public static void setSource(String source) {
        Store.put(Store.KEY_READER_SOURCE, source);
    }

    public static boolean usingBluetooth() {
        return SOURCE_BT.equals(source());
    }

    public static String protocol() {
        return Store.get(Store.KEY_READER_PROTOCOL, PROTOCOL_SPP);
    }

    public static void setProtocol(String protocol) {
        Store.put(Store.KEY_READER_PROTOCOL, protocol);
    }

    public static String mac() {
        return Store.get(Store.KEY_READER_MAC, null);
    }

    public static String deviceName() {
        return Store.get(Store.KEY_READER_NAME, null);
    }

    public static void setDevice(String mac, String name) {
        Store.put(Store.KEY_READER_MAC, mac);
        Store.put(Store.KEY_READER_NAME, name == null || name.isEmpty() ? mac : name);
    }

    public static boolean reverseUid() {
        return "1".equals(Store.get(Store.KEY_READER_REVERSE, "0"));
    }

    public static void setReverseUid(boolean reverse) {
        Store.put(Store.KEY_READER_REVERSE, reverse ? "1" : "0");
    }

    // ------------------------------------------------------------------ building

    public static CardReader create(Activity activity) {
        if (!usingBluetooth()) return new NfcReader(activity);
        return PROTOCOL_BLE.equals(protocol())
                ? new BleCardReader(activity)
                : new SppCardReader(activity);
    }

    /** Whether the user pool is worth offering at all on this phone. */
    public static boolean anyConfigured(Context context) {
        if (usingBluetooth()) return mac() != null;
        return NfcAdapter.getDefaultAdapter(context) != null;
    }

    /** What the settings row says under the title. */
    public static String describe(Context context) {
        if (!usingBluetooth()) {
            return context.getString(R.string.reader_phone_nfc);
        }
        String protocol = context.getString(PROTOCOL_BLE.equals(protocol())
                ? R.string.reader_protocol_ble_short : R.string.reader_protocol_spp_short);
        String name = deviceName();
        if (name == null) {
            return context.getString(R.string.reader_bt_no_device, protocol);
        }
        return context.getString(R.string.reader_bt_device, protocol, name);
    }

    /** The prompt shown while waiting for a card, which differs by where the card goes. */
    public static int tapPrompt() {
        return usingBluetooth() ? R.string.place_card_on_reader : R.string.tap_card;
    }

    public static int tapIcon() {
        return usingBluetooth() ? R.drawable.ic_bluetooth : R.drawable.ic_nfc;
    }

    // ------------------------------------------------------------------ permissions

    /** What a Bluetooth connection needs. Empty below Android 12, where the manifest is enough. */
    public static String[] connectPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return new String[0];
        return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
    }

    /** Scanning needs location below Android 12 and its own permission from 12 onwards. */
    public static String[] scanPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
    }

    public static boolean granted(Context context, String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
