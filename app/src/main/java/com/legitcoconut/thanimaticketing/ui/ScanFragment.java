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
    /**
     * Whether this event hands out food colours, learned once on open so a plain event never
     * pays for the extra lookups. A scan that beats the answer marks outright and picks the
     * colour afterwards, which is the old behaviour and loses nothing.
     */
    private boolean foodRunning;

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

        Api.getFoodSessions(eventId, (sessions, error) -> {
            if (binding == null) return;
            foodRunning = error == null && !sessions.isEmpty();
        });

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
        // On a food event the colour comes first, so nothing is marked until a slot is
        // picked and backing out of the picker leaves the ticket untouched.
        if (foodRunning) {
            askSlotThenMark(value);
        } else {
            markNow(value);
        }
    }

    /** The one call path: marks and reads the food block back off the same response. */
    private void markNow(String qr) {
        Api.verifyQrAttendance(qr, eventId, (res, error) -> {
            if (binding == null) return;
            if (error != null) {
                showFailure(error);
                return;
            }
            if (res.ok()) {
                JSONObject attendance = res.obj("attendance");
                showSuccess(attendance);
                offerFoodSlot(res, attendance.optString("name"), attendance.optString("regNo"),
                        attendance.optString("email"), attendance.optString("phone"));
            } else {
                showFailure(res.error(getString(R.string.scan_verification_failed)));
                // Already marked, but possibly still without a colour — the food block rides
                // along on the 409 precisely so a re-scan can finish the job.
                JSONObject attendee = res.obj("attendee");
                offerFoodSlot(res, attendee.optString("name"), attendee.optString("regNo"),
                        attendee.optString("email"), attendee.optString("phone"));
            }
        });
    }

    /**
     * Reads the ticket without marking it, asks for the colour, and only then spends both.
     * Anything that stops the picker from opening falls back to marking outright, since a
     * food lookup must never be what holds up the door.
     */
    private void askSlotThenMark(String qr) {
        Api.verifyTicket(qr, eventId, (ticket, error) -> {
            if (binding == null) return;
            if (error != null) {
                showFailure(error);
                return;
            }
            // Already present: that mark cannot be taken back, so the after-the-fact path
            // collects whatever colour they are still owed.
            if (ticket.hasAttended) {
                markNow(qr);
                return;
            }
            // Fetched per scan rather than reused, so the seat counts on the circles are
            // the server's and not this screen's memory of them.
            Api.getFoodSessions(eventId, (sessions, sessionsError) -> {
                if (binding == null) return;
                if (sessionsError != null || sessions.isEmpty()) {
                    markNow(qr);
                    return;
                }
                boolean shown = FoodSlotSheet.showBeforeMark(getParentFragmentManager(), eventId,
                        sessions, ticket.name, ticket.regNo, ticket.email, ticket.phone,
                        done -> Api.verifyQrAttendance(qr, eventId, (res, markError) -> {
                            // Painted behind the sheet, so it is already there when it closes.
                            if (binding != null && res != null && res.ok()) {
                                renderSuccess(res.obj("attendance"));
                            }
                            done.done(res, markError);
                        }),
                        this::showAssignedColour,
                        marked -> {
                            if (binding == null) return;
                            if (!marked) showCancelled();
                            handler.postDelayed(this::hideAndResume, marked ? 1500 : 1200);
                        });
                if (!shown) markNow(qr);
            });
        });
    }

    /**
     * Marking someone present is only half the job when food sessions are running. The
     * camera stays paused for as long as the picker is up, otherwise the next ticket in the
     * queue would be read straight through the sheet.
     */
    private void offerFoodSlot(Res res, String name, String regNo, String email, String phone) {
        boolean shown = FoodSlotSheet.showIfNeeded(getParentFragmentManager(), eventId,
                res.data.optJSONObject("food"), name, regNo, email, phone,
                this::showAssignedColour,
                marked -> {
                    if (binding == null) return;
                    handler.postDelayed(this::hideAndResume, 1500);
                });
        if (shown) handler.removeCallbacksAndMessages(null);
    }

    private void showAssignedColour(String colorName, int colorInt) {
        if (binding == null) return;
        binding.resultMeta.setText(getString(R.string.food_slot_assigned_format, colorName));
        binding.resultMeta.setTextColor(colorInt);
        binding.resultMeta.setVisibility(View.VISIBLE);
    }

    private void showSuccess(JSONObject attendance) {
        renderSuccess(attendance);
        handler.postDelayed(this::hideAndResume, 3000);
    }

    /** The card on its own. The picker path holds the timer until the sheet is gone. */
    private void renderSuccess(JSONObject attendance) {
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
    }

    /** Backed out of the picker, so the ticket was never spent. */
    private void showCancelled() {
        Ui.feedback(requireContext(), false);
        int icon = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        int bg = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorSurfaceVariant);
        styleResultHeader(R.drawable.ic_info, icon, bg, getString(R.string.scan_cancelled));

        binding.resultName.setVisibility(View.GONE);
        binding.resultRegNo.setVisibility(View.GONE);
        binding.resultMeta.setVisibility(View.GONE);
        binding.resultMessage.setText(R.string.scan_cancelled_detail);
        binding.resultMessage.setVisibility(View.VISIBLE);

        Ui.pop(binding.resultCard);
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
