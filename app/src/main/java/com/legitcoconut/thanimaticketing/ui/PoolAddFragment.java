package com.legitcoconut.thanimaticketing.ui;

import android.animation.Animator;
import android.animation.AnimatorInflater;
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
import com.legitcoconut.thanimaticketing.databinding.FragmentPoolAddBinding;
import com.legitcoconut.thanimaticketing.model.Ticket;
import com.legitcoconut.thanimaticketing.card.CardReader;
import com.legitcoconut.thanimaticketing.card.CardReaders;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Scan the ticket, confirm the person, then take their card. Six explicit steps, exactly one
 * visible at a time, every switch a crossfade.
 */
public class PoolAddFragment extends Fragment {

    private enum Step {SCAN_TICKET, VERIFYING, CONFIRM_USER, TAP_CARD, SUBMITTING, DONE}

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static PoolAddFragment newInstance(String eventId, String eventTitle) {
        PoolAddFragment f = new PoolAddFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentPoolAddBinding binding;
    private String eventId;
    private String eventTitle;
    private CardReader nfcReader;
    private Step step = Step.SCAN_TICKET;
    private String qrPayload;
    private final List<Animator> tapPulseAnimators = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        eventTitle = args.getString(ARG_EVENT_TITLE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentPoolAddBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        nfcReader = CardReaders.create(requireActivity());

        Ui.toolbar(this, binding.toolbar, eventTitle);

        binding.btnGrantCamera.setOnClickListener(v -> requestCameraAndStart());
        binding.btnContinue.setOnClickListener(v -> enterTapCard());
        binding.btnScanDifferent.setOnClickListener(v -> enterScanTicket());
        binding.btnTapBack.setOnClickListener(v -> {
            binding.groupNfcUnavailable.setVisibility(View.GONE);
            go(Step.CONFIRM_USER);
        });
        binding.btnAddAnother.setOnClickListener(v -> enterScanTicket());
        binding.btnDone.setOnClickListener(v -> Nav.back(requireActivity()));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        // Camera and NFC are always stopped in onPause, so whichever step is current needs
        // its hardware restarted here, whether this is the very first resume or a return trip.
        if (step == Step.SCAN_TICKET) enterScanTicket();
        else if (step == Step.TAP_CARD) enterTapCard();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (binding != null) binding.scanner.stop();
        if (nfcReader != null) nfcReader.stop();
        stopTapPulse();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) binding.scanner.stop();
        if (nfcReader != null) nfcReader.stop();
        stopTapPulse();
        binding = null;
    }

    // ------------------------------------------------------------------ step machine

    private void go(Step next) {
        step = next;
        if (binding == null) return;
        TransitionManager.beginDelayedTransition(binding.stepContainer, new Fade());
        binding.groupScan.setVisibility(next == Step.SCAN_TICKET ? View.VISIBLE : View.GONE);
        binding.groupVerifying.setVisibility(next == Step.VERIFYING ? View.VISIBLE : View.GONE);
        binding.groupConfirm.setVisibility(next == Step.CONFIRM_USER ? View.VISIBLE : View.GONE);
        binding.groupTap.setVisibility(next == Step.TAP_CARD ? View.VISIBLE : View.GONE);
        binding.groupSubmitting.setVisibility(next == Step.SUBMITTING ? View.VISIBLE : View.GONE);
        binding.groupDone.setVisibility(next == Step.DONE ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ SCAN_TICKET

    private void enterScanTicket() {
        go(Step.SCAN_TICKET);
        qrPayload = null;
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
        go(Step.VERIFYING);
        Api.verifyTicket(qr, eventId, (ticket, err) -> {
            if (binding == null) return;
            if (err != null) {
                Ui.error(binding.getRoot(), err);
                binding.scanner.resumeScanning();
                go(Step.SCAN_TICKET);
                return;
            }
            binding.scanner.stop();
            renderConfirm(ticket);
            go(Step.CONFIRM_USER);
        });
    }

    // ------------------------------------------------------------------ CONFIRM_USER

    private void renderConfirm(Ticket t) {
        if (binding == null) return;
        binding.tvConfirmName.setText(t.name);
        binding.tvConfirmRegNo.setText(t.regNo);
        binding.tvConfirmEmail.setText(t.email);
        if (t.phone == null || t.phone.isEmpty()) {
            binding.tvConfirmPhone.setVisibility(View.GONE);
        } else {
            binding.tvConfirmPhone.setVisibility(View.VISIBLE);
            binding.tvConfirmPhone.setText(t.phone);
        }
        int color = t.hasAttended
                ? ContextCompat.getColor(requireContext(), R.color.scan_success)
                : MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        binding.ivConfirmAttended.setImageResource(t.hasAttended ? R.drawable.ic_verified : R.drawable.ic_info);
        ImageViewCompat.setImageTintList(binding.ivConfirmAttended, ColorStateList.valueOf(color));
        binding.tvConfirmAttended.setTextColor(color);
        binding.tvConfirmAttended.setText(t.hasAttended ? R.string.pool_already_attended : R.string.pool_not_attended);
    }

    // ------------------------------------------------------------------ TAP_CARD

    private void enterTapCard() {
        go(Step.TAP_CARD);
        if (binding == null) return;
        String reason = nfcReader.unavailableReason();
        if (reason != null) {
            binding.groupNfcUnavailable.setVisibility(View.VISIBLE);
            binding.tvNfcUnavailable.setText(reason);
            return;
        }
        binding.groupNfcUnavailable.setVisibility(View.GONE);

        // The card goes somewhere different depending on the reader, so say which.
        binding.tvTapTitle.setText(CardReaders.tapPrompt());
        binding.ivNfc.setImageResource(CardReaders.tapIcon());
        binding.tvTapStatus.setVisibility(CardReaders.usingBluetooth() ? View.VISIBLE : View.GONE);
        nfcReader.setOnState((status, ready) -> {
            if (binding == null) return;
            binding.tvTapStatus.setText(status);
        });

        startTapPulse();
        nfcReader.stop();
        nfcReader.start(this::onCardTapped);
    }

    private void onCardTapped(String uid) {
        if (binding == null) return;
        nfcReader.stop();
        stopTapPulse();
        go(Step.SUBMITTING);
        Api.addToUserPool(eventId, qrPayload, uid, (res, err) -> {
            if (binding == null) return;
            if (res == null) {
                renderDoneError(err != null ? err : getString(R.string.pool_add_failed));
            } else if (res.ok() && res.flag("ok")) {
                Ui.feedback(requireContext(), true);
                JSONObject entry = res.obj("entry");
                renderDoneSuccess(entry.optString("name", ""), res.data.optInt("currentCount", 0));
            } else if (res.code == 409 && res.flag("alreadyInPool")) {
                Ui.feedback(requireContext(), false);
                JSONObject entry = res.obj("entry");
                renderDoneWarn(getString(R.string.pool_already_in_pool, entry.optString("name", "")),
                        getString(R.string.pool_held_label, Ui.formatDuration(entry.optLong("durationMs", 0L))));
            } else if (res.code == 409 && res.flag("cardInUse")) {
                Ui.feedback(requireContext(), false);
                JSONObject holder = res.obj("holder");
                renderDoneWarn(getString(R.string.pool_card_in_use,
                        holder.optString("name", ""), holder.optString("regNo", "")), null);
            } else if (res.flag("wrongEvent")) {
                renderDoneError(getString(R.string.pool_wrong_event));
            } else {
                Ui.feedback(requireContext(), false);
                renderDoneError(res.error(getString(R.string.pool_add_failed)));
            }
            go(Step.DONE);
        });
    }

    private void startTapPulse() {
        stopTapPulse();
        if (binding == null) return;
        Animator r1 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pool_ring_pulse);
        r1.setTarget(binding.ring1);
        r1.start();
        tapPulseAnimators.add(r1);

        Animator r2 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pool_ring_pulse);
        r2.setTarget(binding.ring2);
        r2.setStartDelay(800);
        r2.start();
        tapPulseAnimators.add(r2);

        Animator icon = AnimatorInflater.loadAnimator(requireContext(), R.animator.pulse);
        icon.setTarget(binding.ivNfc);
        icon.start();
        tapPulseAnimators.add(icon);
    }

    private void stopTapPulse() {
        for (Animator a : tapPulseAnimators) a.cancel();
        tapPulseAnimators.clear();
        if (binding == null) return;
        binding.ring1.setAlpha(0f);
        binding.ring2.setAlpha(0f);
        binding.ring1.setScaleX(1f);
        binding.ring1.setScaleY(1f);
        binding.ring2.setScaleX(1f);
        binding.ring2.setScaleY(1f);
        binding.ivNfc.setScaleX(1f);
        binding.ivNfc.setScaleY(1f);
    }

    // ------------------------------------------------------------------ DONE

    private void setDoneVisual(int iconRes, int colorRes, int containerColorRes) {
        int color = ContextCompat.getColor(requireContext(), colorRes);
        int container = ContextCompat.getColor(requireContext(), containerColorRes);
        binding.doneCircle.setBackgroundTintList(ColorStateList.valueOf(container));
        binding.ivDoneIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(binding.ivDoneIcon, ColorStateList.valueOf(color));
    }

    private void renderDoneSuccess(String name, int currentCount) {
        if (binding == null) return;
        setDoneVisual(R.drawable.ic_check_circle, R.color.scan_success, R.color.scan_success_container);
        binding.tvDoneTitle.setText(R.string.pool_card_taken);
        binding.tvDoneName.setVisibility(View.VISIBLE);
        binding.tvDoneName.setText(name);
        binding.tvDoneSubtitle.setVisibility(View.VISIBLE);
        binding.tvDoneSubtitle.setText(getString(R.string.pool_now_holding, currentCount));
    }

    private void renderDoneWarn(String title, @Nullable String subtitle) {
        if (binding == null) return;
        setDoneVisual(R.drawable.ic_error, R.color.scan_warn, R.color.scan_warn_container);
        binding.tvDoneTitle.setText(title);
        binding.tvDoneName.setVisibility(View.GONE);
        if (subtitle == null) {
            binding.tvDoneSubtitle.setVisibility(View.GONE);
        } else {
            binding.tvDoneSubtitle.setVisibility(View.VISIBLE);
            binding.tvDoneSubtitle.setText(subtitle);
        }
    }

    private void renderDoneError(String message) {
        if (binding == null) return;
        int color = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorError);
        int container = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorErrorContainer);
        binding.doneCircle.setBackgroundTintList(ColorStateList.valueOf(container));
        binding.ivDoneIcon.setImageResource(R.drawable.ic_error);
        ImageViewCompat.setImageTintList(binding.ivDoneIcon, ColorStateList.valueOf(color));
        binding.tvDoneTitle.setText(message);
        binding.tvDoneName.setVisibility(View.GONE);
        binding.tvDoneSubtitle.setVisibility(View.GONE);
    }
}
