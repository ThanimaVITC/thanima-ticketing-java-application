package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentPoolScanBinding;
import com.legitcoconut.thanimaticketing.model.Ticket;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

/**
 * Both ends of a pool stay, which are the same screen with different words on the button:
 * scan a ticket, read back who it is, then confirm or cancel.
 *
 * A stay is keyed to the person, so nothing but their own ticket is needed at either end.
 */
public class PoolScanFragment extends Fragment {

    /** Which end of the stay this run is working. */
    public enum Mode {ADD, REMOVE}

    private enum Step {SCAN_TICKET, LOOKING_UP, CONFIRM, SUBMITTING, DONE}

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";
    private static final String ARG_MODE = "mode";

    public static PoolScanFragment newInstance(String eventId, String eventTitle, Mode mode) {
        PoolScanFragment f = new PoolScanFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        args.putString(ARG_MODE, mode.name());
        f.setArguments(args);
        return f;
    }

    private FragmentPoolScanBinding binding;
    private String eventId;
    private String eventTitle;
    private Mode mode;
    private Step step = Step.SCAN_TICKET;
    private String qrPayload;
    /** Set in REMOVE mode by the lookup, consumed by the remove call. */
    private String entryId;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        eventTitle = args.getString(ARG_EVENT_TITLE);
        mode = Mode.valueOf(args.getString(ARG_MODE, Mode.ADD.name()));
    }

    private boolean removing() {
        return mode == Mode.REMOVE;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPoolScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.toolbar(this, binding.toolbar, getString(removing() ? R.string.pool_remove : R.string.pool_add));
        binding.toolbar.setSubtitle(eventTitle);

        binding.btnConfirm.setText(removing() ? R.string.pool_remove_action : R.string.pool_add_action);
        binding.btnCancelScan.setText(R.string.cancel);
        binding.btnScanAnother.setText(removing() ? R.string.pool_remove_another : R.string.pool_add_another);

        binding.btnGrantCamera.setOnClickListener(v -> requestCameraAndStart());
        binding.btnConfirm.setOnClickListener(v -> submit());
        binding.btnCancelScan.setOnClickListener(v -> enterScanTicket());
        binding.btnScanAnother.setOnClickListener(v -> enterScanTicket());
        binding.btnDone.setOnClickListener(v -> Nav.back(requireActivity()));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        // The camera is stopped in onPause, so the scanning step has to restart it here
        // whether this is the first resume or a return trip.
        if (step == Step.SCAN_TICKET) enterScanTicket();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (binding != null) binding.scanner.stop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) binding.scanner.stop();
        binding = null;
    }

    // ------------------------------------------------------------------ step machine

    private void go(Step next) {
        step = next;
        if (binding == null) return;
        TransitionManager.beginDelayedTransition(binding.stepContainer, new Fade());
        binding.groupScan.setVisibility(next == Step.SCAN_TICKET ? View.VISIBLE : View.GONE);
        binding.groupVerifying.setVisibility(next == Step.LOOKING_UP ? View.VISIBLE : View.GONE);
        binding.groupConfirm.setVisibility(next == Step.CONFIRM ? View.VISIBLE : View.GONE);
        binding.groupSubmitting.setVisibility(next == Step.SUBMITTING ? View.VISIBLE : View.GONE);
        binding.groupDone.setVisibility(next == Step.DONE ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ SCAN_TICKET

    private void enterScanTicket() {
        go(Step.SCAN_TICKET);
        qrPayload = null;
        entryId = null;
        requestCameraAndStart();
    }

    private void requestCameraAndStart() {
        if (binding == null) return;
        MainActivity activity = (MainActivity) requireActivity();
        if (activity.hasCamera()) {
            binding.groupPermission.setVisibility(View.GONE);
            binding.scanner.setVisibility(View.VISIBLE);
            binding.tvScanHint.setVisibility(View.VISIBLE);
            binding.scanner.start(getViewLifecycleOwner(), this::onQrScanned);
        } else {
            binding.groupPermission.setVisibility(View.VISIBLE);
            binding.scanner.setVisibility(View.INVISIBLE);
            binding.tvScanHint.setVisibility(View.GONE);
            activity.requestCamera(granted -> {
                if (binding == null) return;
                if (granted) requestCameraAndStart();
            });
        }
    }

    private void onQrScanned(String qr) {
        if (binding == null) return;
        qrPayload = qr;
        binding.scanner.pauseScanning();
        go(Step.LOOKING_UP);
        if (removing()) {
            lookupForRemoval(qr);
        } else {
            verifyForAdd(qr);
        }
    }

    /** Adding: the ticket only has to be real and belong to this event. */
    private void verifyForAdd(String qr) {
        Api.verifyTicket(qr, eventId, (ticket, err) -> {
            if (binding == null) return;
            if (err != null) {
                backToScanning(err);
                return;
            }
            binding.scanner.stop();
            renderTicket(ticket);
            go(Step.CONFIRM);
        });
    }

    /** Removing: the ticket has to resolve to a stay that is still open. */
    private void lookupForRemoval(String qr) {
        Api.lookupPoolByTicket(eventId, qr, (res, err) -> {
            if (binding == null) return;
            if (err != null) {
                backToScanning(err);
                return;
            }
            if (!res.flag("found")) {
                // The server names the person even when they hold no stay, so the door can
                // see it scanned the right ticket rather than just being told "no".
                JSONObject who = res.obj("attendee");
                String name = who.optString("name", "");
                backToScanning(name.isEmpty()
                        ? res.error(getString(R.string.pool_not_in_pool))
                        : getString(R.string.pool_not_in_pool_named, name));
                return;
            }
            binding.scanner.stop();
            renderEntry(res.obj("entry"));
            go(Step.CONFIRM);
        });
    }

    private void backToScanning(String message) {
        Ui.feedback(requireContext(), false);
        Ui.error(binding.getRoot(), message);
        binding.scanner.resumeScanning();
        go(Step.SCAN_TICKET);
    }

    // ------------------------------------------------------------------ CONFIRM

    /** Adding: identity from the ticket, plus whether they were marked present. */
    private void renderTicket(Ticket t) {
        if (binding == null) return;
        fillIdentity(t.name, t.regNo, t.email, t.phone);

        int color = t.hasAttended
                ? ContextCompat.getColor(requireContext(), R.color.scan_success)
                : MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        setStatusLine(t.hasAttended ? R.drawable.ic_verified : R.drawable.ic_info, color,
                getString(t.hasAttended ? R.string.pool_already_attended : R.string.pool_not_attended));
    }

    /** Removing: identity from the open stay, plus how long they have been in. */
    private void renderEntry(JSONObject entry) {
        if (binding == null) return;
        entryId = entry.optString("_id", null);
        fillIdentity(entry.optString("name", ""), entry.optString("regNo", ""),
                entry.optString("email", ""), entry.optString("phone", ""));

        int color = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        setStatusLine(R.drawable.ic_info, color, getString(R.string.pool_in_for,
                Ui.formatDuration(entry.optLong("durationMs", 0L))));
    }

    private void fillIdentity(String name, String regNo, String email, String phone) {
        binding.tvConfirmName.setText(name);
        binding.tvConfirmRegNo.setText(regNo);
        binding.tvConfirmEmail.setText(email);
        if (phone == null || phone.isEmpty()) {
            binding.tvConfirmPhone.setVisibility(View.GONE);
        } else {
            binding.tvConfirmPhone.setVisibility(View.VISIBLE);
            binding.tvConfirmPhone.setText(phone);
        }
    }

    private void setStatusLine(int iconRes, int color, String text) {
        binding.ivConfirmAttended.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(binding.ivConfirmAttended, ColorStateList.valueOf(color));
        binding.tvConfirmAttended.setTextColor(color);
        binding.tvConfirmAttended.setText(text);
    }

    // ------------------------------------------------------------------ SUBMITTING

    private void submit() {
        go(Step.SUBMITTING);
        if (removing()) {
            Api.removeFromUserPool(eventId, entryId, this::onRemoveResult);
        } else {
            Api.addToUserPool(eventId, qrPayload, this::onAddResult);
        }
    }

    private void onAddResult(Res res, String err) {
        if (binding == null) return;
        if (res == null) {
            renderDoneError(err != null ? err : getString(R.string.pool_add_failed));
        } else if (res.ok() && res.flag("ok")) {
            Ui.feedback(requireContext(), true);
            JSONObject entry = res.obj("entry");
            setDoneVisual(R.drawable.ic_check_circle, R.color.scan_success, R.color.scan_success_container);
            binding.tvDoneTitle.setText(R.string.pool_added);
            showDoneName(entry.optString("name", ""));
            showDoneSubtitle(getString(R.string.pool_now_holding, res.data.optInt("currentCount", 0)));
        } else if (res.code == 409 && res.flag("alreadyInPool")) {
            Ui.feedback(requireContext(), false);
            JSONObject entry = res.obj("entry");
            renderDoneWarn(getString(R.string.pool_already_in_pool, entry.optString("name", "")),
                    getString(R.string.pool_held_label, Ui.formatDuration(entry.optLong("durationMs", 0L))));
        } else if (res.flag("wrongEvent")) {
            renderDoneError(getString(R.string.pool_wrong_event));
        } else {
            Ui.feedback(requireContext(), false);
            renderDoneError(res.error(getString(R.string.pool_add_failed)));
        }
        go(Step.DONE);
    }

    private void onRemoveResult(Res res, String err) {
        if (binding == null) return;
        if (res == null) {
            renderDoneError(err != null ? err : getString(R.string.pool_remove_failed));
        } else if (res.ok() && res.flag("ok")) {
            Ui.feedback(requireContext(), true);
            JSONObject entry = res.obj("entry");
            setDoneVisual(R.drawable.ic_check_circle, R.color.scan_success, R.color.scan_success_container);
            binding.tvDoneTitle.setText(R.string.pool_removed);
            showDoneName(entry.optString("name", ""));
            showDoneSubtitle(getString(R.string.pool_stay_length,
                    Ui.formatDuration(res.data.optLong("durationMs", 0L))));
        } else if (res.code == 409 && res.flag("alreadyRemoved")) {
            Ui.feedback(requireContext(), false);
            renderDoneWarn(getString(R.string.pool_already_removed), null);
        } else {
            Ui.feedback(requireContext(), false);
            renderDoneError(res.error(getString(R.string.pool_remove_failed)));
        }
        go(Step.DONE);
    }

    // ------------------------------------------------------------------ DONE

    private void setDoneVisual(int iconRes, int colorRes, int containerColorRes) {
        int color = ContextCompat.getColor(requireContext(), colorRes);
        int container = ContextCompat.getColor(requireContext(), containerColorRes);
        binding.doneCircle.setBackgroundTintList(ColorStateList.valueOf(container));
        binding.ivDoneIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(binding.ivDoneIcon, ColorStateList.valueOf(color));
    }

    private void showDoneName(String name) {
        binding.tvDoneName.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        binding.tvDoneName.setText(name);
    }

    private void showDoneSubtitle(@Nullable String subtitle) {
        binding.tvDoneSubtitle.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        if (subtitle != null) binding.tvDoneSubtitle.setText(subtitle);
    }

    private void renderDoneWarn(String title, @Nullable String subtitle) {
        if (binding == null) return;
        setDoneVisual(R.drawable.ic_error, R.color.scan_warn, R.color.scan_warn_container);
        binding.tvDoneTitle.setText(title);
        showDoneName("");
        showDoneSubtitle(subtitle);
    }

    private void renderDoneError(String message) {
        if (binding == null) return;
        int color = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorError);
        int container = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorErrorContainer);
        binding.doneCircle.setBackgroundTintList(ColorStateList.valueOf(container));
        binding.ivDoneIcon.setImageResource(R.drawable.ic_error);
        ImageViewCompat.setImageTintList(binding.ivDoneIcon, ColorStateList.valueOf(color));
        binding.tvDoneTitle.setText(message);
        showDoneName("");
        showDoneSubtitle(null);
    }
}
