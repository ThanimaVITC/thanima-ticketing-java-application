package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentFoodScanBinding;
import com.legitcoconut.thanimaticketing.model.FoodSession;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

import java.util.List;

/**
 * The food counter for one colour. Seats were already spent when attendees were given their
 * colour at the door, so this screen reserves nothing — it checks whether the ticket in front
 * of it belongs to this sitting and records the serving.
 */
public class FoodScanFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_SESSION_ID = "sessionId";
    private static final String ARG_COLOR_NAME = "colorName";
    private static final String ARG_COLOR_INT = "colorInt";

    public static FoodScanFragment newInstance(String eventId, String sessionId,
                                               String colorName, int colorInt) {
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_SESSION_ID, sessionId);
        args.putString(ARG_COLOR_NAME, colorName);
        args.putInt(ARG_COLOR_INT, colorInt);
        FoodScanFragment f = new FoodScanFragment();
        f.setArguments(args);
        return f;
    }

    private FragmentFoodScanBinding binding;
    private String eventId;
    private String sessionId;
    private String colorName;
    private int colorInt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable resumeRunnable;
    private boolean torchOn;
    private boolean sessionClosed;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        sessionId = args.getString(ARG_SESSION_ID);
        colorName = args.getString(ARG_COLOR_NAME);
        colorInt = args.getInt(ARG_COLOR_INT);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFoodScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.toolbar(this, binding.toolbar, colorName);
        // The counter wears the colour it is serving, so a volunteer can see at a glance
        // which sitting this screen belongs to.
        binding.toolbar.setTitleTextColor(colorInt);
        binding.grantAccess.setOnClickListener(v -> requestCameraPermission());
        binding.torchButton.setOnClickListener(v -> toggleTorch());

        loadSessionStats();

        if (((MainActivity) requireActivity()).hasCamera()) {
            startScanning();
        } else {
            showPermissionPanel();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        super.onPause();
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding.scanner.stop();
        handler.removeCallbacksAndMessages(null);
        binding = null;
    }

    // ---------------------------------------------------------------- session stats

    /**
     * Seeds the header from whatever the sessions list last cached, then corrects it from a
     * fresh copy, so the served-against-assigned figures are right before anyone scans
     * anything instead of waiting on the first success.
     */
    private void loadSessionStats() {
        List<FoodSession> cached = Api.peek("food:" + eventId);
        FoodSession cachedSession = findSession(cached);
        if (cachedSession != null) applyStats(cachedSession.served, cachedSession.admitted);

        Api.getFoodSessions(eventId, (sessions, error) -> {
            if (binding == null || error != null) return;
            FoodSession fresh = findSession(sessions);
            if (fresh == null) {
                handleSessionMissing();
                return;
            }
            applyStats(fresh.served, fresh.admitted);
        });
    }

    private FoodSession findSession(List<FoodSession> sessions) {
        if (sessions == null) return null;
        for (FoodSession s : sessions) if (sessionId.equals(s.id)) return s;
        return null;
    }

    /** The session was hidden on the dashboard after this screen opened. Stop before it starts. */
    private void handleSessionMissing() {
        if (binding == null || sessionClosed) return;
        sessionClosed = true;
        binding.scanner.stop();
        binding.scanner.setVisibility(View.GONE);
        binding.torchButton.setVisibility(View.GONE);

        int container = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorErrorContainer);
        int onContainer = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorOnErrorContainer);
        int iconTint = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorError);
        paintCard(container, onContainer, iconTint, R.drawable.ic_error);
        fillCard(getString(R.string.food_session_closed), null, null, null, null);
        Ui.feedback(requireContext(), false);
    }

    // ---------------------------------------------------------------- camera setup

    private void showPermissionPanel() {
        binding.scanner.setVisibility(View.GONE);
        binding.permissionPanel.setVisibility(View.VISIBLE);
    }

    private void requestCameraPermission() {
        ((MainActivity) requireActivity()).requestCamera(granted -> {
            if (binding == null) return;
            if (granted) {
                binding.permissionPanel.setVisibility(View.GONE);
                binding.scanner.setVisibility(View.VISIBLE);
                startScanning();
            } else {
                Ui.snack(binding.getRoot(), getString(R.string.camera_permission_needed));
            }
        });
    }

    private void startScanning() {
        if (sessionClosed) return;
        binding.scanner.setOnCameraReady(() -> {
            if (binding == null) return;
            binding.torchButton.setVisibility(binding.scanner.hasTorch() ? View.VISIBLE : View.GONE);
        });
        binding.scanner.start(getViewLifecycleOwner(), this::onQr);
        binding.scanner.setHint(getString(R.string.point_at_qr));
    }

    private void toggleTorch() {
        if (binding == null) return;
        torchOn = !torchOn;
        binding.scanner.setTorch(torchOn);
        binding.torchButton.setIconTint(ColorStateList.valueOf(MaterialColors.getColor(
                binding.torchButton,
                torchOn ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurface)));
    }

    // ---------------------------------------------------------------- scanning

    private void onQr(String value) {
        if (binding == null) return;
        if (resumeRunnable != null) handler.removeCallbacks(resumeRunnable);
        binding.scanner.pauseScanning();
        Api.scanFoodSession(eventId, sessionId, value, (res, err) -> {
            if (binding == null) return;
            if (err != null) {
                onSimpleError(err);
                return;
            }
            handleResult(res);
        });
    }

    private void handleResult(Res res) {
        if (res.code == 200 && res.flag("ok")) {
            onServed(res);
        } else if (res.flag("wrongSession")) {
            onWrongSession(res);
        } else if (res.flag("noAssignment")) {
            onNoSlot(res);
        } else if (res.flag("alreadyServed")) {
            onAlreadyServed(res);
        } else if (res.flag("wrongEvent")) {
            onSimpleError(getString(R.string.food_wrong_event));
        } else if (res.flag("sessionUnavailable")) {
            onSimpleError(getString(R.string.food_session_closed));
        } else {
            onSimpleError(res.error(getString(R.string.food_scan_failed_fallback)));
        }
    }

    private void onServed(Res res) {
        JSONObject attendee = res.obj("attendee");

        paintCard(getColor(R.color.scan_success_container), getColor(R.color.on_scan_success_container),
                getColor(R.color.scan_success), R.drawable.ic_check_circle);
        fillCard(getString(R.string.food_served), attendee.optString("name", ""),
                attendee.optString("regNo", ""), null, null);
        applyStatsFrom(res.obj("session"));

        Ui.feedback(requireContext(), true);
        binding.scanner.flash(true);
        scheduleResume(2000);
    }

    /** The commonest rejection: right person, wrong sitting. Name the colour they do hold. */
    private void onWrongSession(Res res) {
        JSONObject attendee = res.obj("attendee");
        JSONObject assigned = res.data.optJSONObject("assigned");
        String theirColor = assigned == null ? null : assigned.optString("colorName", null);

        paintCard(getColor(R.color.scan_warn_container), getColor(R.color.on_scan_warn_container),
                getColor(R.color.scan_warn), R.drawable.ic_info);
        fillCard(getString(R.string.food_wrong_colour), attendee.optString("name", ""),
                attendee.optString("regNo", ""),
                theirColor == null || theirColor.isEmpty()
                        ? getString(R.string.food_other_session)
                        : getString(R.string.food_belongs_to_format, theirColor),
                getString(R.string.food_change_on_dashboard));

        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);
        scheduleResume(3000);
    }

    private void onNoSlot(Res res) {
        JSONObject attendee = res.obj("attendee");

        paintCard(getColor(R.color.scan_warn_container), getColor(R.color.on_scan_warn_container),
                getColor(R.color.scan_warn), R.drawable.ic_info);
        fillCard(getString(R.string.food_no_slot), attendee.optString("name", ""),
                attendee.optString("regNo", ""), getString(R.string.food_no_slot_detail),
                getString(R.string.food_change_on_dashboard));

        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);
        scheduleResume(3000);
    }

    private void onAlreadyServed(Res res) {
        JSONObject attendee = res.obj("attendee");
        String when = Ui.formatDateTime(res.data.optString("servedAt"));

        paintCard(getColor(R.color.scan_warn_container), getColor(R.color.on_scan_warn_container),
                getColor(R.color.scan_warn), R.drawable.ic_info);
        fillCard(getString(R.string.already_ate), attendee.optString("name", ""),
                attendee.optString("regNo", ""), when,
                getString(R.string.food_one_scan_per_event));

        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);
        scheduleResume(3000);
    }

    private void onSimpleError(String message) {
        if (binding == null) return;
        int container = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorErrorContainer);
        int onContainer = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorOnErrorContainer);
        int iconTint = MaterialColors.getColor(binding.resultCard,
                com.google.android.material.R.attr.colorError);

        paintCard(container, onContainer, iconTint, R.drawable.ic_error);
        fillCard(message, null, null, null, null);

        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);
        scheduleResume(2000);
    }

    // ---------------------------------------------------------------- result card

    private void paintCard(int containerColor, int onContainerColor, int iconTintColor, int iconRes) {
        binding.resultCard.setCardBackgroundColor(containerColor);
        binding.resultIcon.setImageResource(iconRes);
        binding.resultIcon.setImageTintList(ColorStateList.valueOf(iconTintColor));
        binding.resultTitle.setTextColor(onContainerColor);
        binding.resultName.setTextColor(onContainerColor);
        binding.resultRegNo.setTextColor(onContainerColor);
        binding.resultMeta.setTextColor(onContainerColor);
    }

    private void fillCard(String title, String name, String regNo, String meta, String note) {
        binding.resultTitle.setText(title);
        setLine(binding.resultName, name);
        setLine(binding.resultRegNo, regNo);
        setLine(binding.resultMeta, meta);
        setLine(binding.resultNote, note);
        Ui.pop(binding.resultCard);
    }

    private void setLine(TextView tv, String text) {
        if (text == null || text.isEmpty()) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(text);
            tv.setVisibility(View.VISIBLE);
        }
    }

    private void applyStatsFrom(JSONObject session) {
        if (session == null || session.length() == 0) return;
        FoodSession s = new FoodSession(session);
        applyStats(s.served, s.admitted);
    }

    /**
     * How far through the queue this counter is: people served against people holding this
     * colour. Capacity is not shown, because it was settled at the door and cannot move here.
     */
    private void applyStats(int served, int assigned) {
        if (binding == null) return;
        Ui.countTo(binding.statServedValue, served);
        Ui.countTo(binding.statAssignedValue, assigned);

        int percent = assigned > 0 ? Math.min(100, served * 100 / assigned) : 0;
        binding.statProgress.setProgressCompat(percent, true);
        binding.statProgress.setIndicatorColor(colorInt);
        binding.statServedValue.setTextColor(colorInt);
    }

    private void scheduleResume(long delayMs) {
        binding.scanner.setHint(null);
        if (resumeRunnable != null) handler.removeCallbacks(resumeRunnable);
        resumeRunnable = () -> {
            if (binding == null) return;
            Ui.fadeOut(binding.resultCard);
            binding.scanner.resumeScanning();
            binding.scanner.setHint(getString(R.string.point_at_qr));
        };
        handler.postDelayed(resumeRunnable, delayMs);
    }

    private int getColor(int colorRes) {
        return requireContext().getColor(colorRes);
    }
}
