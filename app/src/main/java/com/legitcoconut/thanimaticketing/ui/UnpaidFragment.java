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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentUnpaidBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemUnpaidBinding;
import com.legitcoconut.thanimaticketing.databinding.SheetUnpaidAddBinding;
import com.legitcoconut.thanimaticketing.model.UnpaidEntry;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The unpaid list for one event. Cold starts from the on disk cache while a fresh request
 * runs, then offers two ways to add someone: type them in, or photograph their ID card.
 */
public class UnpaidFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static UnpaidFragment newInstance(String eventId, String eventTitle) {
        UnpaidFragment f = new UnpaidFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentUnpaidBinding binding;
    private String eventId;
    private String eventTitle;

    private final List<UnpaidEntry> all = new ArrayList<>();
    private final List<UnpaidEntry> shown = new ArrayList<>();
    private Adapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentUnpaidBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        eventId = requireArguments().getString(ARG_EVENT_ID);
        eventTitle = requireArguments().getString(ARG_EVENT_TITLE);

        Ui.toolbar(this, binding.toolbar, getString(R.string.unpaid_list));

        adapter = new Adapter();
        binding.rvUnpaid.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvUnpaid.setAdapter(adapter);

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });

        binding.swipeRefresh.setOnRefreshListener(() -> load(true));
        binding.fabAdd.setOnClickListener(v -> openAddSheet());
        binding.btnScan.setOnClickListener(v ->
                Nav.push(requireActivity(), UnpaidScanFragment.newInstance(eventId, eventTitle)));

        // Cold start: the disk cache renders instantly, the network call then settles the truth.
        List<UnpaidEntry> cached = Api.unpaidFromDisk(eventId);
        if (cached != null) setEntries(cached);
    }

    private void load(boolean userTriggered) {
        Api.getUnpaid(eventId, (entries, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                if (userTriggered) Ui.error(binding.getRoot(), error);
                return;
            }
            setEntries(entries);
        });
    }

    private void setEntries(List<UnpaidEntry> entries) {
        all.clear();
        all.addAll(entries);
        Ui.countTo(binding.tvCount, all.size(), " " + getString(R.string.unpaid_count_suffix));
        filter(binding.etSearch.getText() == null ? "" : binding.etSearch.getText().toString());
    }

    private void filter(String query) {
        shown.clear();
        String q = query.trim();
        if (q.isEmpty()) {
            shown.addAll(all);
        } else {
            for (UnpaidEntry e : all) if (e.matches(q)) shown.add(e);
        }
        adapter.notifyDataSetChanged();
        Ui.animateList(binding.rvUnpaid);
        boolean empty = shown.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvUnpaid.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ manual add sheet

    private void openAddSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        SheetUnpaidAddBinding sheet = SheetUnpaidAddBinding.inflate(getLayoutInflater());
        dialog.setContentView(sheet.getRoot());

        sheet.etName.addTextChangedListener(clearErrorWatcher(sheet.tilName));
        sheet.etRegNo.addTextChangedListener(clearErrorWatcher(sheet.tilRegNo));
        sheet.btnCancel.setOnClickListener(v -> dialog.dismiss());
        sheet.btnSave.setOnClickListener(v -> submitAdd(dialog, sheet));

        dialog.show();
    }

    private void submitAdd(BottomSheetDialog dialog, SheetUnpaidAddBinding sheet) {
        String rawName = sheet.etName.getText() == null ? "" : sheet.etName.getText().toString();
        String rawRegNo = sheet.etRegNo.getText() == null ? "" : sheet.etRegNo.getText().toString();

        String nameErr = IdCardParser.nameError(rawName);
        String regNoErr = IdCardParser.regNoError(rawRegNo);
        sheet.tilName.setError(nameErr);
        sheet.tilRegNo.setError(regNoErr);
        if (nameErr != null || regNoErr != null) return;

        String name = IdCardParser.normalizeName(rawName);
        String regNo = IdCardParser.normalizeRegNo(rawRegNo);

        sheet.btnSave.setEnabled(false);
        sheet.progress.setVisibility(View.VISIBLE);
        Api.addUnpaid(eventId, name, regNo, "manual", (res, err) -> {
            if (binding == null) return;
            sheet.btnSave.setEnabled(true);
            sheet.progress.setVisibility(View.GONE);

            if (err != null) {
                Ui.error(sheet.getRoot(), err);
                return;
            }
            if (res.code == 201 || res.flag("ok")) {
                dialog.dismiss();
                Ui.feedback(requireContext(), true);
                Ui.snack(binding.getRoot(), getString(R.string.unpaid_added_fmt, name));
                load(false);
            } else if (res.code == 409 && res.flag("alreadyListed")) {
                String already = res.obj("entry").optString("name", name);
                dialog.dismiss();
                Ui.snack(binding.getRoot(), getString(R.string.unpaid_already_listed_fmt, already));
                load(false);
            } else {
                Ui.error(sheet.getRoot(), res.error(getString(R.string.unpaid_add_error_fallback)));
            }
        });
    }

    private TextWatcher clearErrorWatcher(TextInputLayout til) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                til.setError(null);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning here recreates the view, so onViewCreated and onResume both run.
        // Fetching from onResume alone keeps it to one request and one visible update.
        load(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ------------------------------------------------------------------ list

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemUnpaidBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(shown.get(position));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            private final ItemUnpaidBinding b;

            VH(ItemUnpaidBinding b) {
                super(b.getRoot());
                this.b = b;
            }

            void bind(UnpaidEntry entry) {
                b.tvName.setText(entry.name);
                b.tvRegNo.setText(entry.regNo);
                b.tvSource.setText("ocr".equals(entry.source)
                        ? R.string.source_scanned : R.string.source_manual);
                b.tvTime.setText(Ui.formatDateTime(entry.createdAt));
            }
        }
    }
}
