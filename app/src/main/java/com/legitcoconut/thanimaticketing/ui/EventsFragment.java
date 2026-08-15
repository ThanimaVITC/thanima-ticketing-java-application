package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentEventsBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemEventBinding;
import com.legitcoconut.thanimaticketing.model.Event;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.ImageLoader;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** The event picker. Root screen after login, and the morph source for EventDetailFragment. */
public class EventsFragment extends Fragment {

    private FragmentEventsBinding binding;
    private EventAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentEventsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.toolbar.setTitle(R.string.events);
        MenuItem profile = binding.toolbar.getMenu().add(Menu.NONE, Menu.NONE, Menu.NONE, R.string.profile);
        profile.setIcon(R.drawable.ic_rambo);
        profile.setIconTintList(null);  // The mascot brings its own colours.
        profile.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        profile.setOnMenuItemClickListener(item -> {
            Nav.push(requireActivity(), new ProfileFragment());
            return true;
        });

        adapter = new EventAdapter(this::openEvent);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        binding.swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorPrimary));
        binding.swipeRefresh.setOnRefreshListener(this::loadEvents);
        binding.retryButton.setOnClickListener(v -> loadEvents());

        List<Event> cached = Api.peek("events");
        if (cached != null) render(cached);
        loadEvents();
    }

    private void loadEvents() {
        Api.getEvents((events, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                if (adapter.getItemCount() == 0) showError(error);
                return;
            }
            render(events);
        });
    }

    private void render(List<Event> events) {
        binding.errorState.setVisibility(View.GONE);
        if (events.isEmpty()) {
            binding.recyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.VISIBLE);
            return;
        }
        binding.emptyState.setVisibility(View.GONE);
        binding.recyclerView.setVisibility(View.VISIBLE);
        adapter.submit(events);
        Ui.animateList(binding.recyclerView);
    }

    private void showError(String message) {
        binding.recyclerView.setVisibility(View.GONE);
        binding.emptyState.setVisibility(View.GONE);
        binding.errorState.setVisibility(View.VISIBLE);
        binding.errorText.setText(message);
    }

    private void openEvent(Event event, View cardView) {
        String name = "event_" + event.id;
        Nav.morph(requireActivity(), EventDetailFragment.newInstance(event.id, event.title), cardView, name);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ------------------------------------------------------------------ adapter

    private interface OnEventClick {
        void onClick(Event event, View cardView);
    }

    private static final class EventAdapter extends RecyclerView.Adapter<EventAdapter.VH> {

        private final List<Event> items = new ArrayList<>();
        private final OnEventClick onClick;

        EventAdapter(OnEventClick onClick) {
            this.onClick = onClick;
        }

        void submit(List<Event> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemEventBinding b = ItemEventBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
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
            final ItemEventBinding b;

            VH(ItemEventBinding b) {
                super(b.getRoot());
                this.b = b;
            }

            void bind(Event event, OnEventClick onClick) {
                b.title.setText(event.title);
                b.dateText.setText(Ui.formatDate(event.date));

                ImageLoader.load(b.logo, event.logoPath, R.drawable.thanima_mark);
                if (event.logoPath == null || event.logoPath.isEmpty()) {
                    b.logo.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(
                            b.logo, com.google.android.material.R.attr.colorPrimary)));
                } else {
                    b.logo.setImageTintList(null);
                }

                // Logo, name and date only. The description and the feature pills live on the
                // event page, which is one tap away.

                ViewCompat.setTransitionName(b.getRoot(), "event_" + event.id);
                b.getRoot().setOnClickListener(v -> onClick.onClick(event, b.getRoot()));
            }
        }
    }
}
