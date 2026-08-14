package com.legitcoconut.thanimaticketing.ui;

import android.animation.Animator;
import android.animation.AnimatorInflater;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentPoolBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemPoolEntryBinding;
import com.legitcoconut.thanimaticketing.databinding.SheetPoolRemoveBinding;
import com.legitcoconut.thanimaticketing.model.PoolEntry;
import com.legitcoconut.thanimaticketing.card.CardReader;
import com.legitcoconut.thanimaticketing.card.CardReaders;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is holding a card right now. Stats, a live-ticking list of active stays, and the
 * two actions (Add / Remove) that move cards in and out of the pool.
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
    private CardReader nfcReader;
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
        nfcReader = CardReaders.create(requireActivity());

        Ui.toolbar(this, binding.toolbar, getString(R.string.user_pool));

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        String reason = nfcReader.unavailableReason();
        boolean nfcOk = reason == null;
        binding.cardNfcWarning.setVisibility(nfcOk ? View.GONE : View.VISIBLE);
        if (!nfcOk) binding.tvNfcReason.setText(reason);
        binding.fabAdd.setEnabled(nfcOk);
        binding.btnRemove.setEnabled(nfcOk);

        binding.fabAdd.setOnClickListener(v ->
                Nav.push(requireActivity(), PoolAddFragment.newInstance(eventId, eventTitle)));
        binding.btnRemove.setOnClickListener(v -> showRemoveSheet());
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
        if (nfcReader != null) nfcReader.stop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        tickHandler.removeCallbacks(ticker);
        if (nfcReader != null) nfcReader.stop();
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

    // ------------------------------------------------------------------ remove bottom sheet

    private void showRemoveSheet() {
        if (nfcReader == null) return;
        SheetPoolRemoveBinding sb = SheetPoolRemoveBinding.inflate(LayoutInflater.from(requireContext()));
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sb.getRoot());

        RemoveSession session = new RemoveSession(sb, dialog);
        dialog.setOnDismissListener(d -> {
            nfcReader.stop();
            session.stopPulse();
        });
        session.showScanning();
        dialog.show();
    }

    /** One run through the four remove stages. A fresh instance per sheet, so state never leaks. */
    private final class RemoveSession {
        private final SheetPoolRemoveBinding sb;
        private final BottomSheetDialog dialog;
        private final List<Animator> pulseAnimators = new ArrayList<>();
        private String entryId;
        private String entryName;

        RemoveSession(SheetPoolRemoveBinding sb, BottomSheetDialog dialog) {
            this.sb = sb;
            this.dialog = dialog;
            sb.btnScanningCancel.setOnClickListener(v -> dialog.dismiss());
            sb.btnConfirmCancel.setOnClickListener(v -> dialog.dismiss());
            sb.btnConfirmRemove.setOnClickListener(v -> remove());
            sb.btnRetry.setOnClickListener(v -> showScanning());
        }

        void showScanning() {
            stage(sb.stageScanning);

            sb.tvScanTitle.setText(CardReaders.tapPrompt());
            sb.ivNfc.setImageResource(CardReaders.tapIcon());
            sb.tvScanStatus.setVisibility(CardReaders.usingBluetooth() ? View.VISIBLE : View.GONE);
            nfcReader.setOnState((status, ready) -> {
                if (binding == null || !dialog.isShowing()) return;
                sb.tvScanStatus.setText(status);
            });

            startPulse();
            nfcReader.stop();
            nfcReader.start(this::lookup);
        }

        private void lookup(String uid) {
            Api.lookupPoolByNfc(eventId, uid, (res, err) -> {
                if (binding == null || !dialog.isShowing()) return;
                if (res == null) {
                    showFailed(err != null ? err : getString(R.string.pool_not_found));
                    return;
                }
                if (res.code == 200 && res.flag("found")) {
                    showConfirming(res.obj("entry"));
                } else {
                    showFailed(res.error(getString(R.string.pool_not_found)));
                }
            });
        }

        private void showConfirming(JSONObject entry) {
            nfcReader.stop();
            stopPulse();
            entryId = entry.optString("_id", "");
            entryName = entry.optString("name", "");
            sb.tvConfirmName.setText(entryName);
            sb.tvConfirmRegNo.setText(entry.optString("regNo", ""));
            sb.tvConfirmEntered.setText(getString(R.string.pool_entered_label,
                    Ui.formatDateTime(entry.isNull("enteredAt") ? null : entry.optString("enteredAt", null))));
            sb.tvConfirmHeld.setText(getString(R.string.pool_held_label,
                    Ui.formatDuration(entry.optLong("durationMs", 0L))));
            stage(sb.stageConfirming);
        }

        private void remove() {
            stage(sb.stageRemoving);
            Api.removeFromUserPool(eventId, entryId, (res, err) -> {
                if (binding == null || !dialog.isShowing()) return;
                if (res == null) {
                    showFailed(err != null ? err : getString(R.string.pool_remove_failed));
                    return;
                }
                if (res.ok() && res.flag("ok")) {
                    Ui.feedback(requireContext(), true);
                    dialog.dismiss();
                    Ui.snack(binding.getRoot(), getString(R.string.pool_returned, entryName,
                            Ui.formatDuration(res.data.optLong("durationMs", 0L))));
                    load();
                } else if (res.code == 409 && res.flag("alreadyRemoved")) {
                    showFailed(res.error(getString(R.string.pool_already_removed)));
                    load();
                } else {
                    showFailed(res.error(getString(R.string.pool_remove_failed)));
                }
            });
        }

        private void showFailed(String message) {
            nfcReader.stop();
            stopPulse();
            sb.tvFailedMessage.setText(message);
            stage(sb.stageFailed);
        }

        private void stage(View visible) {
            TransitionManager.beginDelayedTransition(sb.getRoot(), new Fade());
            View[] all = {sb.stageScanning, sb.stageConfirming, sb.stageRemoving, sb.stageFailed};
            for (View v : all) v.setVisibility(v == visible ? View.VISIBLE : View.GONE);
        }

        private void startPulse() {
            stopPulse();
            Animator r1 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pool_ring_pulse);
            r1.setTarget(sb.ring1);
            r1.start();
            pulseAnimators.add(r1);

            Animator r2 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pool_ring_pulse);
            r2.setTarget(sb.ring2);
            r2.setStartDelay(800);
            r2.start();
            pulseAnimators.add(r2);

            Animator icon = AnimatorInflater.loadAnimator(requireContext(), R.animator.pulse);
            icon.setTarget(sb.ivNfc);
            icon.start();
            pulseAnimators.add(icon);
        }

        void stopPulse() {
            for (Animator a : pulseAnimators) a.cancel();
            pulseAnimators.clear();
            sb.ring1.setAlpha(0f);
            sb.ring2.setAlpha(0f);
            sb.ring1.setScaleX(1f);
            sb.ring1.setScaleY(1f);
            sb.ring2.setScaleX(1f);
            sb.ring2.setScaleY(1f);
            sb.ivNfc.setScaleX(1f);
            sb.ivNfc.setScaleY(1f);
        }
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
                binding.tvUid.setText(e.nfcId);
                binding.tvTimer.setText(e.timeInPool());
            }
        }
    }
}
