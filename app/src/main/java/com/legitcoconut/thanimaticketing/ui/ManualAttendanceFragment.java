package com.legitcoconut.thanimaticketing.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentManualAttendanceBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemRegistrationBinding;
import com.legitcoconut.thanimaticketing.model.Registration;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search a name, tap it, mark them present. Only the people still to arrive are listed, so
 * the queue shrinks as the door works through it and nobody can be marked twice.
 */
public class ManualAttendanceFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static ManualAttendanceFragment newInstance(String eventId, String eventTitle) {
        ManualAttendanceFragment f = new ManualAttendanceFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentManualAttendanceBinding binding;
    private String eventId;

    private final List<Registration> all = new ArrayList<>();
    private final List<Registration> visible = new ArrayList<>();
    private final Set<String> marking = new HashSet<>();
    private final Adapter adapter = new Adapter();
    private String query = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentManualAttendanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        eventId = requireArguments().getString(ARG_EVENT_ID);

        Ui.toolbar(this, binding.toolbar, getString(R.string.manual_attendance));

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                recompute();
            }
        });

        binding.swipeRefresh.setOnRefreshListener(this::load);

        Api.EventDetails cached = Api.peek("event:" + eventId);
        if (cached != null) render(cached);
        load();
    }

    private void load() {
        Api.getEventDetails(eventId, (details, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                return;
            }
            render(details);
        });
    }

    private void render(Api.EventDetails details) {
        all.clear();
        all.addAll(details.registrations);
        recompute();
    }

    /** Only the people still to arrive, alphabetical. Runs on load and every keystroke. */
    private void recompute() {
        visible.clear();
        for (Registration r : all) {
            if (r.attended || !r.matches(query)) continue;
            visible.add(r);
        }
        Collections.sort(visible, (a, b) -> a.name.compareToIgnoreCase(b.name));

        adapter.notifyDataSetChanged();
        Ui.animateList(binding.recyclerView);
        updateEmptyState();
        updateSummary();
    }

    private void updateEmptyState() {
        boolean empty = visible.isEmpty();
        binding.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.tvEmpty.setText(all.isEmpty() ? R.string.nobody_here
                : query.isEmpty() ? R.string.att_all_present : R.string.no_match);
    }

    private void updateSummary() {
        int presentCount = 0;
        for (Registration r : all) if (r.attended) presentCount++;
        Ui.countTo(binding.tvSummary, presentCount, getString(R.string.att_summary_suffix, all.size()));
    }

    private void confirmMark(Registration r) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mark_present)
                .setMessage(getString(R.string.att_mark_present_message, r.name, r.regNo))
                .setPositiveButton(R.string.mark_present, (d, w) -> mark(r))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void mark(Registration r) {
        marking.add(r.id);
        int startIdx = visible.indexOf(r);
        if (startIdx >= 0) adapter.notifyItemChanged(startIdx);

        Api.markAttendance(eventId, r.email, (res, error) -> {
            if (binding == null) return;
            marking.remove(r.id);
            int idx = visible.indexOf(r);

            if (error != null) {
                if (idx >= 0) adapter.notifyItemChanged(idx);
                Ui.feedback(requireContext(), false);
                Ui.error(binding.getRoot(), error);
                return;
            }

            if (!res.ok()) {
                // A duplicate is the server telling us they are already present. They may
                // still be owed a food colour, so the picker is offered all the same.
                if (res.flag("alreadyMarked")) {
                    r.attended = true;
                    removeRow(idx);
                    updateSummary();
                    offerFoodSlot(res, r);
                } else if (idx >= 0) {
                    adapter.notifyItemChanged(idx);
                }
                Ui.feedback(requireContext(), false);
                Ui.error(binding.getRoot(), res.error(getString(R.string.att_mark_failed)));
                return;
            }

            r.attended = true;
            r.markedAt = res.obj("attendance").optString("markedAt", null);
            Ui.feedback(requireContext(), true);
            Ui.snack(binding.getRoot(), getString(R.string.att_marked_present_fmt, r.name));
            updateSummary();
            removeRow(idx);
            offerFoodSlot(res, r);
        });
    }

    /** The door assigns a colour whichever way someone is marked present, tap or scan. */
    private void offerFoodSlot(Res res, Registration r) {
        FoodSlotSheet.showIfNeeded(getParentFragmentManager(), eventId,
                res.data.optJSONObject("food"), r.name, r.regNo, r.email,
                (colorName, colorInt) -> {
                    if (binding == null) return;
                    Ui.snack(binding.getRoot(), getString(R.string.food_slot_assigned_format, colorName));
                },
                null);
    }

    /** Present people are not on this screen, so marking someone slides them out of the list. */
    private void removeRow(int idx) {
        if (idx < 0) return;
        visible.remove(idx);
        adapter.notifyItemRemoved(idx);
        updateEmptyState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ItemRegistrationBinding b;

            VH(ItemRegistrationBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemRegistrationBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Registration r = visible.get(position);
            ItemRegistrationBinding b = holder.b;
            String letter = r.name.isEmpty() ? "?" : r.name.substring(0, 1).toUpperCase(Locale.getDefault());
            b.tvAvatar.setText(letter);
            b.tvName.setText(r.name);
            b.tvMeta.setText(r.regNo + "  •  " + r.email);

            boolean busy = marking.contains(r.id);
            b.getRoot().setClickable(!busy);
            b.getRoot().setFocusable(!busy);
            b.getRoot().setOnClickListener(busy ? null : v -> confirmMark(r));
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }
}
