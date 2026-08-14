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

import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentPoolHistoryBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemPoolHistoryBinding;
import com.legitcoconut.thanimaticketing.model.PoolEntry;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Every visit, finished or not, newest first. */
public class PoolHistoryFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static PoolHistoryFragment newInstance(String eventId, String eventTitle) {
        PoolHistoryFragment f = new PoolHistoryFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentPoolHistoryBinding binding;
    private String eventId;
    private final HistoryAdapter adapter = new HistoryAdapter();

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
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentPoolHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.toolbar(this, binding.toolbar, getString(R.string.pool_history));

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);
        binding.swipeRefresh.setOnRefreshListener(this::load);

        Api.PoolResult cached = Api.peek("pool:" + eventId + ":all");
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
        Api.getUserPool(eventId, "all", (result, error) -> {
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
        adapter.submit(result.entries);
        Ui.animateList(binding.recyclerView);
        binding.emptyState.setVisibility(result.entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<PoolEntry> items = new ArrayList<>();

        void submit(List<PoolEntry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void tick(RecyclerView rv) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
                if (vh instanceof VH) {
                    VH holder = (VH) vh;
                    if (holder.entry != null && holder.entry.isInPool()) {
                        holder.binding.tvDuration.setText(holder.entry.timeInPool());
                    }
                }
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPoolHistoryBinding b = ItemPoolHistoryBinding.inflate(
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
            final ItemPoolHistoryBinding binding;
            PoolEntry entry;

            VH(ItemPoolHistoryBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            void bind(PoolEntry e) {
                entry = e;
                boolean inPool = e.isInPool();
                binding.tvName.setText(e.name);
                binding.tvRegNo.setText(e.regNo);
                binding.tvInTime.setText(binding.getRoot().getContext()
                        .getString(R.string.pool_in_at, Ui.formatTime(e.enteredAt)));
                binding.tvOutTime.setVisibility(inPool ? View.GONE : View.VISIBLE);
                binding.rowCurrent.setVisibility(inPool ? View.VISIBLE : View.GONE);
                if (!inPool) {
                    binding.tvOutTime.setText(binding.getRoot().getContext()
                            .getString(R.string.pool_out_at, Ui.formatTime(e.exitedAt)));
                }
                binding.tvDuration.setText(e.timeInPool());
            }
        }
    }
}
