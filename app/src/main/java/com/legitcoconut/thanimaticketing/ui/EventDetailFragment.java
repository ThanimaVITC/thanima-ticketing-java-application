package com.legitcoconut.thanimaticketing.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentEventDetailBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemActionBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemStatChipBinding;
import com.legitcoconut.thanimaticketing.model.Event;
import com.legitcoconut.thanimaticketing.card.CardReaders;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

/**
 * The morph target for the event card. Shows the counters and the door duty tiles for
 * one event. Every action screen returns here, which is why onResume re-fetches.
 */
public class EventDetailFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    private FragmentEventDetailBinding binding;
    private String eventId;
    private String eventTitle;
    private boolean hasData;
    private int actionIndex;

    /** Chips are inflated once and then updated in place, so a refresh does not flash. */
    private final java.util.List<ItemStatChipBinding> chipViews = new java.util.ArrayList<>();
    private String statsSignature;
    private boolean actionsBuilt;

    public static EventDetailFragment newInstance(String eventId, String eventTitle) {
        EventDetailFragment fragment = new EventDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID, "");
        eventTitle = args.getString(ARG_EVENT_TITLE, "");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentEventDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ViewCompat.setTransitionName(binding.getRoot(), "event_" + eventId);
        Ui.startTransitionAfterLayout(this, binding.getRoot());

        Ui.toolbar(this, binding.toolbar, eventTitle);
        binding.retryButton.setOnClickListener(v -> loadDetails());

        binding.swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorPrimary));
        binding.swipeRefresh.setOnRefreshListener(this::loadDetails);

        chipViews.clear();
        statsSignature = null;
        actionsBuilt = false;

        Api.EventDetails cached = Api.peek("event:" + eventId);
        if (cached != null) {
            hasData = true;
            render(cached);
        } else {
            binding.loadingState.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The only place that fetches. Coming back from an action screen recreates this view,
     * so onViewCreated and onResume both run: loading from just one of them keeps it to a
     * single request and a single visible update.
     */
    @Override
    public void onResume() {
        super.onResume();
        loadDetails();
    }

    private void loadDetails() {
        Api.getEventDetails(eventId, (details, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                if (!hasData) {
                    binding.loadingState.setVisibility(View.GONE);
                    showError(error);
                }
                return;
            }
            hasData = true;
            binding.loadingState.setVisibility(View.GONE);
            binding.errorState.setVisibility(View.GONE);
            render(details);
        });
    }

    private void showError(String message) {
        binding.errorText.setText(message);
        binding.errorState.setVisibility(View.VISIBLE);
    }

    private void render(Api.EventDetails details) {
        binding.errorState.setVisibility(View.GONE);
        Event event = details.event;

        binding.title.setText(event.title);
        binding.toolbar.setTitle(event.title);
        binding.dateText.setText(Ui.formatDate(event.date));
        if (event.description == null || event.description.isEmpty()) {
            binding.description.setVisibility(View.GONE);
        } else {
            binding.description.setVisibility(View.VISIBLE);
            binding.description.setText(event.description);
        }

        buildStats(details, event);
        buildActions(event);
    }

    // ------------------------------------------------------------------ stats

    private void buildStats(Api.EventDetails d, Event event) {
        // Registrations is deliberately just the denominator here, never its own number.
        binding.heroProgress.setProgressCompat(d.attendanceRate, true);
        Ui.countTo(binding.heroRateText, d.attendanceRate, "%");
        Ui.countTo(binding.heroFraction, d.totalAttendance, " / " + d.totalRegistrations);
        int stillToArrive = Math.max(0, d.totalRegistrations - d.totalAttendance);
        Ui.countTo(binding.heroCaption, stillToArrive, " " + getString(R.string.still_to_arrive));

        buildChips(d, event);
    }

    private void buildChips(Api.EventDetails d, Event event) {
        int[] labels = {R.string.stat_food, R.string.in_pool, R.string.unpaid};
        int[] values = {d.foodScanCount, d.userPoolCount, d.unpaidCount};
        boolean[] shown = {event.foodSessionsEnabled, event.userPoolEnabled, event.unpaidEnabled};

        StringBuilder sig = new StringBuilder();
        for (boolean b : shown) sig.append(b ? '1' : '0');

        boolean anyShown = event.foodSessionsEnabled || event.userPoolEnabled || event.unpaidEnabled;
        binding.chipRow.setVisibility(anyShown ? View.VISIBLE : View.GONE);

        // Which chips exist depends only on the event flags, so the row is inflated once.
        // A refresh then just updates the numbers in place.
        if (!sig.toString().equals(statsSignature)) {
            statsSignature = sig.toString();
            chipViews.clear();
            LinearLayout row = binding.chipRow;
            row.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (int i = 0; i < shown.length; i++) {
                if (!shown[i]) continue;
                ItemStatChipBinding cb = ItemStatChipBinding.inflate(inflater, row, true);
                cb.tvLabel.setText(getString(labels[i]));
                chipViews.add(cb);
            }
        }

        int tile = 0;
        for (int i = 0; i < shown.length; i++) {
            if (!shown[i]) continue;
            Ui.countTo(chipViews.get(tile++).tvValue, values[i]);
        }
    }

    // ------------------------------------------------------------------ actions

    private void buildActions(Event event) {
        // The tiles depend only on the event flags. Rebuilding them on every refresh would
        // replay the stagger animation and make a returning screen look like a reload.
        if (actionsBuilt) return;
        actionsBuilt = true;

        LinearLayout container = binding.actionsContainer;
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        actionIndex = 0;

        addAction(inflater, container, R.drawable.ic_tile_scan, getString(R.string.scan_qr),
                getString(R.string.scan_qr_sub), true,
                () -> Nav.push(requireActivity(), ScanFragment.newInstance(eventId, eventTitle)));

        addAction(inflater, container, R.drawable.ic_tile_verify, getString(R.string.verify_ticket),
                getString(R.string.verify_ticket_sub), true,
                () -> Nav.push(requireActivity(), VerifyTicketFragment.newInstance(eventId, eventTitle)));

        addAction(inflater, container, R.drawable.ic_tile_manual, getString(R.string.manual_attendance),
                getString(R.string.manual_attendance_sub), true,
                () -> Nav.push(requireActivity(), ManualAttendanceFragment.newInstance(eventId, eventTitle)));

        addAction(inflater, container, R.drawable.ic_tile_attendees, getString(R.string.attendees),
                getString(R.string.attendees_sub), true,
                () -> Nav.push(requireActivity(), AttendeesFragment.newInstance(eventId, eventTitle)));

        addAction(inflater, container, R.drawable.ic_tile_assign, getString(R.string.assign_tickets),
                getString(R.string.assign_tickets_sub), true,
                () -> Nav.push(requireActivity(), AssignTicketFragment.newInstance(eventId, eventTitle)));

        if (event.foodSessionsEnabled) {
            addAction(inflater, container, R.drawable.ic_tile_food, getString(R.string.food_sessions),
                    getString(R.string.food_sessions_sub), true,
                    () -> Nav.push(requireActivity(), FoodSessionsFragment.newInstance(eventId, eventTitle)));
        }

        if (event.userPoolEnabled) {
            // A phone with no NFC can still work the pool if a Bluetooth reader is set up.
            boolean nfcOk = CardReaders.anyConfigured(requireContext());
            addAction(inflater, container, R.drawable.ic_tile_pool, getString(R.string.user_pool),
                    nfcOk ? getString(R.string.user_pool_sub) : getString(R.string.nfc_unavailable_tile), nfcOk,
                    () -> Nav.push(requireActivity(), PoolFragment.newInstance(eventId, eventTitle)));

            addAction(inflater, container, R.drawable.ic_tile_history, getString(R.string.pool_history),
                    getString(R.string.pool_history_sub), true,
                    () -> Nav.push(requireActivity(), PoolHistoryFragment.newInstance(eventId, eventTitle)));
        }

        if (event.unpaidEnabled) {
            addAction(inflater, container, R.drawable.ic_tile_unpaid, getString(R.string.unpaid_list),
                    getString(R.string.unpaid_list_sub), true,
                    () -> Nav.push(requireActivity(), UnpaidFragment.newInstance(eventId, eventTitle)));
        }
    }

    private void addAction(LayoutInflater inflater, LinearLayout container, int iconRes, String title,
                            String subtitle, boolean enabled, Runnable onClick) {
        ItemActionBinding ab = ItemActionBinding.inflate(inflater, container, true);
        ab.ivIcon.setImageResource(iconRes);
        ab.tvTitle.setText(title);
        ab.tvSubtitle.setText(subtitle);

        View tile = ab.getRoot();
        tile.setClickable(enabled);
        tile.setFocusable(enabled);
        tile.setOnClickListener(enabled ? v -> onClick.run() : null);

        float targetAlpha = enabled ? 1f : 0.45f;
        tile.setAlpha(0f);
        tile.setTranslationY(Ui.dp(requireContext(), 16));
        tile.animate().alpha(targetAlpha).translationY(0f)
                .setStartDelay(actionIndex * 40L)
                .setDuration(260)
                .setInterpolator(new FastOutSlowInInterpolator())
                .start();
        actionIndex++;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
