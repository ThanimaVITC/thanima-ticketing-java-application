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
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

import java.util.List;

/** Scan tickets against one food session: admit, catch duplicates, stop at capacity. */
public class FoodScanFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_SESSION_ID = "sessionId";
    private static final String ARG_SESSION_NAME = "sessionName";

    public static FoodScanFragment newInstance(String eventId, String sessionId, String sessionName) {
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_SESSION_ID, sessionId);
        args.putString(ARG_SESSION_NAME, sessionName);
        FoodScanFragment f = new FoodScanFragment();
        f.setArguments(args);
        return f;
    }

    private FragmentFoodScanBinding binding;
    private String eventId;
    private String sessionId;
    private String sessionName;

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
        sessionName = args.getString(ARG_SESSION_NAME);
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
        Ui.toolbar(this, binding.toolbar, sessionName);
        binding.grantAccess.setOnClickListener(v -> requestCameraPermission());
        binding.torchButton.setOnClickListener(v -> toggleTorch());
        binding.fullPanelClose.setOnClickListener(v -> Nav.back(requireActivity()));

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
     * fresh copy, so the seats left figure is right before anyone scans anything instead of
     * waiting on the first successful scan.
     */
    private void loadSessionStats() {
        List<FoodSession> cached = Api.peek("food:" + eventId);
        FoodSession cachedSession = findSession(cached);
        if (cachedSession != null) {
            applyStats(cachedSession.admitted, cachedSession.remainingToMax,
                    cachedSession.nearLimit, cachedSession.full);
        }

        Api.getFoodSessions(eventId, (sessions, error) -> {
            if (binding == null || error != null) return;
            FoodSession fresh = findSession(sessions);
            if (fresh == null) {
                handleSessionMissing();
                return;
            }
            applyStats(fresh.admitted, fresh.remainingToMax, fresh.nearLimit, fresh.full);
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
        if (binding.fullPanel.getVisibility() == View.VISIBLE) return;
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
            onAdmitted(res);
        } else if (res.code == 409 && res.flag("alreadyScanned")) {
            onAlready(res);
        } else if (res.code == 409 && res.flag("full")) {
            onFull(res);
        } else if (res.flag("wrongEvent")) {
            onSimpleError(getString(R.string.food_wrong_event));
        } else if (res.flag("sessionUnavailable")) {
            onSimpleError(getString(R.string.food_session_closed));
        } else {
            onSimpleError(res.error(getString(R.string.food_scan_failed_fallback)));
        }
    }

    private void onAdmitted(Res res) {
        JSONObject attendee = res.obj("attendee");
        JSONObject stats = res.obj("stats");

        paintCard(getColor(R.color.scan_success_container), getColor(R.color.on_scan_success_container),
                getColor(R.color.scan_success), R.drawable.ic_check_circle);
        fillCard(getString(R.string.admitted), attendee.optString("name", ""),
                attendee.optString("regNo", ""), null,
                stats.optBoolean("nearLimit") ? getString(R.string.near_limit) : null);
        applyStats(stats.optInt("admitted"), stats.optInt("remainingToMax"),
                stats.optBoolean("nearLimit"), stats.optBoolean("full"));

        Ui.feedback(requireContext(), true);
        binding.scanner.flash(true);
        scheduleResume(2500);
    }

    private void onAlready(Res res) {
        JSONObject attendee = res.obj("attendee");
        String when = Ui.formatDateTime(res.data.optString("scannedAt"));
        String where = res.data.optString("scannedSessionName");

        paintCard(getColor(R.color.scan_warn_container), getColor(R.color.on_scan_warn_container),
                getColor(R.color.scan_warn), R.drawable.ic_info);
        fillCard(getString(R.string.already_ate), attendee.optString("name", ""),
                attendee.optString("regNo", ""), getString(R.string.food_already_detail_format, when, where),
                getString(R.string.food_one_scan_per_event));

        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);
        scheduleResume(3000);
    }

    private void onFull(Res res) {
        JSONObject stats = res.obj("stats");
        applyStats(stats.optInt("admitted"), stats.optInt("remainingToMax"),
                stats.optBoolean("nearLimit"), stats.optBoolean("full"));

        if (resumeRunnable != null) handler.removeCallbacks(resumeRunnable);
        binding.scanner.stop();
        binding.scanner.setVisibility(View.GONE);
        binding.torchButton.setVisibility(View.GONE);
        binding.resultCard.setVisibility(View.GONE);

        binding.fullPanelCount.setText(getString(R.string.food_final_admitted_format, stats.optInt("admitted")));
        Ui.pop(binding.fullPanel);
        Ui.feedback(requireContext(), false);
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

    /** Seats left against the hard cap leads; admitted is secondary; the bar mirrors both. */
    private void applyStats(int admitted, int remainingToMax, boolean nearLimit, boolean full) {
        if (binding == null) return;
        Ui.countTo(binding.statSeatsLeftValue, remainingToMax);
        Ui.countTo(binding.statAdmittedValue, admitted);

        int capacity = admitted + remainingToMax;
        int percent = capacity > 0 ? Math.min(100, admitted * 100 / capacity) : 0;
        binding.statProgress.setProgressCompat(percent, true);

        int color;
        if (full) {
            color = MaterialColors.getColor(binding.statProgress, com.google.android.material.R.attr.colorError);
        } else if (nearLimit) {
            color = getColor(R.color.scan_warn);
        } else {
            color = MaterialColors.getColor(binding.statProgress, com.google.android.material.R.attr.colorPrimary);
        }
        binding.statProgress.setIndicatorColor(color);
        binding.statSeatsLeftValue.setTextColor(color);
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
