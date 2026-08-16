package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentScanBinding;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Marks attendance from a ticket QR. Every scan pauses the camera, calls the server, shows a
 * result card for a couple of seconds, then resumes on its own so a volunteer can work through
 * a queue without touching the screen.
 */
public class ScanFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static ScanFragment newInstance(String eventId, String eventTitle) {
        ScanFragment fragment = new ScanFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        fragment.setArguments(args);
        return fragment;
    }

    private FragmentScanBinding binding;
    private String eventId;
    private Handler handler;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        String eventTitle = args.getString(ARG_EVENT_TITLE);
        handler = new Handler(Looper.getMainLooper());

        Ui.toolbar(this, binding.toolbar, getString(R.string.scan_qr));
        if (!TextUtils.isEmpty(eventTitle)) binding.toolbar.setSubtitle(eventTitle);

        binding.grantAccess.setOnClickListener(v -> requestCameraAndStart());
        binding.torch.setOnClickListener(v -> toggleTorch());

        if (((MainActivity) requireActivity()).hasCamera()) {
            showScanner();
        } else {
            binding.permissionPanel.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    private void requestCameraAndStart() {
        ((MainActivity) requireActivity()).requestCamera(granted -> {
            if (binding == null) return;
            if (granted) showScanner();
        });
    }

    private void showScanner() {
        binding.permissionPanel.setVisibility(View.GONE);
        binding.scanner.setOnCameraReady(() -> {
            if (binding == null) return;
            binding.torch.setVisibility(binding.scanner.hasTorch() ? View.VISIBLE : View.GONE);
        });
        binding.scanner.start(getViewLifecycleOwner(), this::onQr);
        binding.scanner.setHint(getString(R.string.point_at_qr));
    }

    private void toggleTorch() {
        if (binding == null) return;
        boolean on = !binding.scanner.isTorchOn();
        binding.scanner.setTorch(on);
        binding.torch.setActivated(on);
        binding.torch.setAlpha(on ? 1f : 0.6f);
    }

    private void onQr(String value) {
        if (binding == null) return;
        binding.scanner.pauseScanning();
        Api.verifyQrAttendance(value, eventId, (res, error) -> {
            if (binding == null) return;
            if (error != null) {
                showFailure(error);
                return;
            }
            if (res.ok()) {
                JSONObject attendance = res.obj("attendance");
                showSuccess(attendance);
                offerFoodSlot(res, attendance.optString("name"), attendance.optString("regNo"),
                        attendance.optString("email"));
            } else {
                showFailure(res.error(getString(R.string.scan_verification_failed)));
                // Already marked, but possibly still without a colour — the food block rides
                // along on the 409 precisely so a re-scan can finish the job.
                JSONObject attendee = res.obj("attendee");
                offerFoodSlot(res, attendee.optString("name"), attendee.optString("regNo"),
                        attendee.optString("email"));
            }
        });
    }

    /**
     * Marking someone present is only half the job when food sessions are running. The
     * camera stays paused for as long as the picker is up, otherwise the next ticket in the
     * queue would be read straight through the sheet.
     */
    private void offerFoodSlot(Res res, String name, String regNo, String email) {
        boolean shown = FoodSlotSheet.showIfNeeded(getParentFragmentManager(), eventId,
                res.data.optJSONObject("food"), name, regNo, email,
                (colorName, colorInt) -> {
                    if (binding == null) return;
                    binding.resultMeta.setText(getString(R.string.food_slot_assigned_format, colorName));
                    binding.resultMeta.setTextColor(colorInt);
                    binding.resultMeta.setVisibility(View.VISIBLE);
                },
                () -> {
                    if (binding == null) return;
                    handler.postDelayed(this::hideAndResume, 1500);
                });
        if (shown) handler.removeCallbacksAndMessages(null);
    }

    private void showSuccess(JSONObject attendance) {
        binding.scanner.setHint(null);
        Ui.feedback(requireContext(), true);
        binding.scanner.flash(true);

        int icon = ContextCompat.getColor(requireContext(), R.color.scan_success);
        int bg = ContextCompat.getColor(requireContext(), R.color.scan_success_container);
        styleResultHeader(R.drawable.ic_check_circle, icon, bg, getString(R.string.marked_present));

        binding.resultName.setText(attendance.optString("name"));
        binding.resultRegNo.setText(attendance.optString("regNo"));
        binding.resultMeta.setText(Ui.formatDateTime(attendance.optString("markedAt")));
        binding.resultName.setVisibility(View.VISIBLE);
        binding.resultRegNo.setVisibility(View.VISIBLE);
        binding.resultMeta.setVisibility(View.VISIBLE);
        binding.resultMessage.setVisibility(View.GONE);

        Ui.pop(binding.resultCard);
        handler.postDelayed(this::hideAndResume, 3000);
    }

    private void showFailure(String error) {
        binding.scanner.setHint(null);
        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);

        boolean already = error != null && error.toLowerCase(Locale.US).contains("already");
        if (already) {
            int icon = ContextCompat.getColor(requireContext(), R.color.on_scan_warn_container);
            int bg = ContextCompat.getColor(requireContext(), R.color.scan_warn_container);
            styleResultHeader(R.drawable.ic_error, icon, bg, getString(R.string.already_marked));
        } else {
            int icon = MaterialColors.getColor(binding.getRoot(),
                    com.google.android.material.R.attr.colorOnErrorContainer);
            int bg = MaterialColors.getColor(binding.getRoot(),
                    com.google.android.material.R.attr.colorErrorContainer);
            styleResultHeader(R.drawable.ic_error, icon, bg, getString(R.string.scan_verification_failed));
        }

        binding.resultName.setVisibility(View.GONE);
        binding.resultRegNo.setVisibility(View.GONE);
        binding.resultMeta.setVisibility(View.GONE);
        binding.resultMessage.setText(error);
        binding.resultMessage.setVisibility(View.VISIBLE);

        Ui.pop(binding.resultCard);
        handler.postDelayed(this::hideAndResume, 2000);
    }

    private void styleResultHeader(@DrawableRes int drawable, int iconColor, int bgColor, String title) {
        binding.resultIcon.setImageResource(drawable);
        ImageViewCompat.setImageTintList(binding.resultIcon, ColorStateList.valueOf(iconColor));
        ViewCompat.setBackgroundTintList(binding.resultIcon, ColorStateList.valueOf(bgColor));
        binding.resultTitle.setText(title);
        binding.resultTitle.setTextColor(iconColor);
    }

    private void hideAndResume() {
        if (binding == null) return;
        Ui.fadeOut(binding.resultCard);
        binding.scanner.resumeScanning();
        binding.scanner.setHint(getString(R.string.point_at_qr));
    }

    @Override
    public void onDestroyView() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (binding != null) binding.scanner.stop();
        binding = null;
        super.onDestroyView();
    }
}
