package com.legitcoconut.thanimaticketing.ui;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.card.BtDevices;
import com.legitcoconut.thanimaticketing.card.CardReader;
import com.legitcoconut.thanimaticketing.card.CardReaders;
import com.legitcoconut.thanimaticketing.databinding.DialogReaderProtocolBinding;
import com.legitcoconut.thanimaticketing.databinding.SheetReaderSettingsBinding;

import java.util.List;

/**
 * Picking the card reader. Every choice is saved the moment it is made, so Done only closes
 * the sheet.
 */
final class ReaderSettingsSheet {

    private final MainActivity activity;
    private final Runnable onChanged;
    private SheetReaderSettingsBinding b;
    private BottomSheetDialog dialog;

    ReaderSettingsSheet(MainActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    void show() {
        b = SheetReaderSettingsBinding.inflate(LayoutInflater.from(activity));
        dialog = new BottomSheetDialog(activity);
        dialog.setContentView(b.getRoot());

        boolean hasNfc = NfcAdapter.getDefaultAdapter(activity) != null;
        b.rbNfc.setEnabled(hasNfc);
        if (hasNfc) {
            b.tvNfcReason.setVisibility(View.GONE);
        } else {
            b.tvNfcReason.setVisibility(View.VISIBLE);
            b.tvNfcReason.setText(R.string.nfc_none);
        }

        b.rbNfc.setChecked(!CardReaders.usingBluetooth());
        b.rbBt.setChecked(CardReaders.usingBluetooth());
        b.swReverse.setChecked(CardReaders.reverseUid());
        refresh();

        b.sourceGroup.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.rbBt) {
                chooseBluetooth();
            } else {
                CardReaders.setSource(CardReaders.SOURCE_NFC);
                refresh();
            }
        });
        b.rowProtocol.setOnClickListener(v -> showProtocolDialog(false));
        b.rowDevice.setOnClickListener(v -> chooseDevice());
        b.swReverse.setOnCheckedChangeListener((v, checked) -> CardReaders.setReverseUid(checked));
        b.btnTest.setOnClickListener(v -> showTestDialog());
        b.btnDone.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> onChanged.run());

        dialog.show();
    }

    /** Switching to the reader needs the permission first, then the protocol question. */
    private void chooseBluetooth() {
        activity.request(granted -> {
            if (!granted) {
                b.rbNfc.setChecked(true);
                CardReaders.setSource(CardReaders.SOURCE_NFC);
                refresh();
                return;
            }
            CardReaders.setSource(CardReaders.SOURCE_BT);
            refresh();
            showProtocolDialog(true);
        }, CardReaders.connectPermissions());
    }

    private void refresh() {
        boolean bt = CardReaders.usingBluetooth();
        b.groupBt.setVisibility(bt ? View.VISIBLE : View.GONE);
        b.tvProtocol.setText(CardReaders.PROTOCOL_BLE.equals(CardReaders.protocol())
                ? R.string.reader_protocol_ble : R.string.reader_protocol_spp);
        String name = CardReaders.deviceName();
        if (name == null) {
            b.tvDevice.setText(R.string.reader_no_device);
        } else {
            b.tvDevice.setText(name);
        }
        b.btnTest.setEnabled(name != null);
    }

    // ------------------------------------------------------------------ protocol

    /** After picking a protocol for the first time, go straight on to picking the device. */
    private void showProtocolDialog(boolean thenChooseDevice) {
        DialogReaderProtocolBinding pb = DialogReaderProtocolBinding.inflate(LayoutInflater.from(activity));
        boolean ble = CardReaders.PROTOCOL_BLE.equals(CardReaders.protocol());
        pb.rbSpp.setChecked(!ble);
        pb.rbBle.setChecked(ble);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reader_protocol_title)
                .setView(pb.getRoot())
                .setPositiveButton(R.string.continue_label, (d, w) -> {
                    String picked = pb.rbBle.isChecked()
                            ? CardReaders.PROTOCOL_BLE : CardReaders.PROTOCOL_SPP;
                    if (!picked.equals(CardReaders.protocol())) {
                        // A device found over one transport is meaningless on the other.
                        CardReaders.setProtocol(picked);
                        CardReaders.setDevice(null, null);
                    }
                    refresh();
                    if (thenChooseDevice) chooseDevice();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ device

    /**
     * The reader is picked from the devices this phone is already paired with, which is the
     * same list staff see in Android settings. Low energy readers often never get paired, so
     * that protocol keeps a scan as a way out.
     */
    private void chooseDevice() {
        boolean ble = CardReaders.PROTOCOL_BLE.equals(CardReaders.protocol());
        List<BtDevices.Entry> paired = BtDevices.bonded(activity);

        if (paired.isEmpty()) {
            MaterialAlertDialogBuilder empty = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.reader_choose_device)
                    .setMessage(R.string.reader_none_paired)
                    .setPositiveButton(R.string.reader_open_bt_settings, (d, w) ->
                            activity.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton(R.string.cancel, null);
            if (ble) empty.setNeutralButton(R.string.reader_scan_nearby, (d, w) -> requestScan());
            empty.show();
            return;
        }
        showDeviceList(paired, ble);
    }

    private void requestScan() {
        activity.request(granted -> {
            if (granted) scanForDevices();
        }, CardReaders.scanPermissions());
    }

    private void scanForDevices() {
        AlertDialog waiting = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reader_choose_device)
                .setMessage(R.string.reader_scanning)
                .setCancelable(true)
                .show();

        BtDevices.scan(activity, found -> {
            waiting.dismiss();
            if (found.isEmpty()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.reader_choose_device)
                        .setMessage(R.string.reader_none_found)
                        .setPositiveButton(R.string.reader_scan_again, (d, w) -> scanForDevices())
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return;
            }
            showDeviceList(found, false);
        });
    }

    private void showDeviceList(List<BtDevices.Entry> devices, boolean offerScan) {
        CharSequence[] labels = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) labels[i] = devices.get(i).label();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reader_pick_from_paired)
                .setItems(labels, (d, which) -> {
                    BtDevices.Entry picked = devices.get(which);
                    CardReaders.setDevice(picked.mac, picked.name);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null);
        if (offerScan) builder.setNeutralButton(R.string.reader_scan_nearby, (d, w) -> requestScan());
        builder.show();
    }

    // ------------------------------------------------------------------ test

    /**
     * The calibration step. Read one card here, read the same card with phone NFC, and if
     * the two differ only in byte order the reverse switch fixes it without a reflash.
     */
    private void showTestDialog() {
        CardReader reader = CardReaders.create(activity);
        AlertDialog test = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reader_test)
                .setMessage(R.string.reader_test_waiting)
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(d -> reader.stop())
                .show();

        reader.setOnState((status, ready) -> {
            if (!ready && !status.isEmpty()) test.setMessage(status);
        });
        reader.start(uid -> test.setMessage(activity.getString(R.string.reader_test_result, uid)));
    }
}
