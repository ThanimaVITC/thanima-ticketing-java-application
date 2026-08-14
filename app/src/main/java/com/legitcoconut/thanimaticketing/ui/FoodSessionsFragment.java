package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentFoodSessionsBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemFoodSessionBinding;
import com.legitcoconut.thanimaticketing.model.FoodSession;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Every open food session for an event, with live capacity and a way into the scanner. */
public class FoodSessionsFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static FoodSessionsFragment newInstance(String eventId, String eventTitle) {
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        FoodSessionsFragment f = new FoodSessionsFragment();
        f.setArguments(args);
        return f;
    }

    private FragmentFoodSessionsBinding binding;
    private String eventId;
    private FoodAdapter adapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFoodSessionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.toolbar(this, binding.toolbar, getString(R.string.food_sessions));
        String eventTitle = requireArguments().getString(ARG_EVENT_TITLE);
        if (eventTitle != null && !eventTitle.isEmpty()) binding.toolbar.setSubtitle(eventTitle);

        adapter = new FoodAdapter(session ->
                Nav.push(requireActivity(), FoodScanFragment.newInstance(eventId, session.id, session.name)));
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        binding.swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.swipeRefresh, com.google.android.material.R.attr.colorPrimary));
        binding.swipeRefresh.setOnRefreshListener(this::refresh);

        List<FoodSession> cached = Api.peek("food:" + eventId);
        if (cached != null) render(cached);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning here recreates the view, so onViewCreated and onResume both run.
        // Fetching from onResume alone keeps it to one request and one visible update.
        refresh();
    }

    private void refresh() {
        if (binding == null) return;
        binding.swipeRefresh.setRefreshing(true);
        Api.getFoodSessions(eventId, (sessions, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                return;
            }
            render(sessions);
        });
    }

    private void render(List<FoodSession> sessions) {
        if (binding == null) return;
        adapter.setItems(sessions);
        Ui.animateList(binding.recyclerView);
        boolean empty = sessions.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ---------------------------------------------------------------- adapter

    private static final class FoodAdapter extends RecyclerView.Adapter<FoodAdapter.VH> {

        private final List<FoodSession> items = new ArrayList<>();
        private final Consumer<FoodSession> onClick;

        FoodAdapter(Consumer<FoodSession> onClick) {
            this.onClick = onClick;
        }

        void setItems(List<FoodSession> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemFoodSessionBinding b = ItemFoodSessionBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position), onClick);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final ItemFoodSessionBinding binding;

            VH(ItemFoodSessionBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            void bind(FoodSession s, Consumer<FoodSession> onClick) {
                binding.tvName.setText(s.name);
                binding.tvCapacity.setText(binding.getRoot().getContext()
                        .getString(R.string.food_capacity_format, s.admitted, s.maxLimit));
                binding.progress.setProgressCompat(s.percentOfMax(), true);

                int indicatorColor;
                if (s.full) {
                    indicatorColor = MaterialColors.getColor(binding.getRoot(),
                            com.google.android.material.R.attr.colorError);
                } else if (s.nearLimit) {
                    indicatorColor = binding.getRoot().getContext().getColor(R.color.scan_warn);
                } else {
                    indicatorColor = MaterialColors.getColor(binding.getRoot(),
                            com.google.android.material.R.attr.colorPrimary);
                }
                binding.progress.setIndicatorColor(indicatorColor);

                if (s.full) {
                    binding.chipStatus.setText(R.string.session_full);
                    tintChip(binding.chipStatus,
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorErrorContainer),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnErrorContainer));
                } else if (s.nearLimit) {
                    binding.chipStatus.setText(R.string.near_limit);
                    tintChip(binding.chipStatus,
                            binding.getRoot().getContext().getColor(R.color.scan_warn_container),
                            binding.getRoot().getContext().getColor(R.color.on_scan_warn_container));
                } else {
                    binding.chipStatus.setText(binding.getRoot().getContext()
                            .getString(R.string.food_remaining_format, s.remainingToMax));
                    tintChip(binding.chipStatus,
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorSurfaceVariant),
                            MaterialColors.getColor(binding.getRoot(),
                                    com.google.android.material.R.attr.colorOnSurfaceVariant));
                }

                if (s.full) {
                    binding.card.setClickable(false);
                    binding.card.setFocusable(false);
                    binding.card.setOnClickListener(null);
                    binding.card.setAlpha(0.6f);
                } else {
                    binding.card.setClickable(true);
                    binding.card.setFocusable(true);
                    binding.card.setAlpha(1f);
                    binding.card.setOnClickListener(v -> onClick.accept(s));
                }
            }

            private void tintChip(Chip chip, int background, int text) {
                chip.setChipBackgroundColor(ColorStateList.valueOf(background));
                chip.setTextColor(text);
            }
        }
    }
}
