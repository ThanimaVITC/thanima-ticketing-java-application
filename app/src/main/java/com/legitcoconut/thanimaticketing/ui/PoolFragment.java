package com.legitcoconut.thanimaticketing.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;

import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentPoolBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemPoolEntryBinding;
import com.legitcoconut.thanimaticketing.model.PoolEntry;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is in the pool right now. Stats, a live-ticking list of active stays, and the two
 * actions that open and close them. Both actions are the same ticket scanner in a
 * different mode.
 */
public class PoolFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static PoolFragment newInstance(String eventId, String eventTitle) {
        PoolFragment f = new PoolFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentPoolBinding binding;
    private String eventId;
    private String eventTitle;
    private final PoolEntryAdapter adapter = new PoolEntryAdapter();

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (binding == null) return;
            adapter.tick(binding.recyclerView);
            tickHandler.postDelayed(this, 1000);
        }
    };


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
        binding = FragmentPoolBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Ui.toolbar(this, binding.toolbar, getString(R.string.user_pool));

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        binding.fabAdd.setOnClickListener(v -> Nav.push(requireActivity(),
                PoolScanFragment.newInstance(eventId, eventTitle, PoolScanFragment.Mode.ADD)));
        binding.btnRemove.setOnClickListener(v -> Nav.push(requireActivity(),
                PoolScanFragment.newInstance(eventId, eventTitle, PoolScanFragment.Mode.REMOVE)));
        binding.swipeRefresh.setOnRefreshListener(this::load);

        Api.PoolResult cached = Api.peek("pool:" + eventId + ":active");
        if (cached != null) render(cached);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning here recreates the view, so onViewCreated and onResume both run.
        // Fetching from onResume alone keeps it to one request and one visible update.
        load();
        tickHandler.post(ticker);
    }

    @Override
    public void onPause() {
        super.onPause();
        tickHandler.removeCallbacks(ticker);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        tickHandler.removeCallbacks(ticker);
        binding = null;
    }

    private void load() {
        Api.getUserPool(eventId, "active", (result, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                return;
            }
            render(result);
        });
    }

    private void render(Api.PoolResult result) {
        if (binding == null) return;
        Ui.countTo(binding.tvCurrentCount, result.currentCount);
        Ui.countTo(binding.tvVisits, result.totalVisits);
        Ui.countTo(binding.tvUniqueUsers, result.uniqueUsers);
        adapter.submit(result.entries);
        Ui.animateList(binding.recyclerView);
        binding.emptyState.setVisibility(result.entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ list adapter

    private static final class PoolEntryAdapter extends RecyclerView.Adapter<PoolEntryAdapter.VH> {
        private final List<PoolEntry> items = new ArrayList<>();

        void submit(List<PoolEntry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        /** Walks only the currently attached rows and refreshes their timer text in place. */
        void tick(RecyclerView rv) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
                if (vh instanceof VH) {
                    VH holder = (VH) vh;
                    if (holder.entry != null && holder.entry.isInPool()) {
                        holder.binding.tvTimer.setText(holder.entry.timeInPool());
                    }
                }
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPoolEntryBinding b = ItemPoolEntryBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final ItemPoolEntryBinding binding;
            PoolEntry entry;

            VH(ItemPoolEntryBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            void bind(PoolEntry e) {
                entry = e;
                binding.tvName.setText(e.name);
                binding.tvRegNo.setText(e.regNo);
                binding.tvTimer.setText(e.timeInPool());
            }
        }
    }
}
