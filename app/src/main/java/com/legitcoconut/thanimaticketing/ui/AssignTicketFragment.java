package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentAssignTicketBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemAssignRegistrationBinding;
import com.legitcoconut.thanimaticketing.model.Registration;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Binds a scanned blank ticket QR to a registration. One fragment, two panes swapped with a
 * cross fade: the searchable list (PICK_PERSON) and the scanner (everything else). CHECKING,
 * ASSIGNING and DONE are sub states rendered inside the scanner pane so the camera never
 * has to tear down between a scan and its result.
 */
public class AssignTicketFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    private enum Step { PICK_PERSON, SCAN_TICKET, CHECKING, ASSIGNING, DONE }

    public static AssignTicketFragment newInstance(String eventId, String eventTitle) {
        AssignTicketFragment f = new AssignTicketFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentAssignTicketBinding binding;
    private String eventId;

    private Step step = Step.PICK_PERSON;
    private List<Registration> all = new ArrayList<>();
    private final List<Registration> filtered = new ArrayList<>();
    private final Set<String> justBoundIds = new HashSet<>();
    private final RegAdapter adapter = new RegAdapter();

    private Registration selected;
    private String lastQrPayload;
    private boolean scannerStarted;
    private boolean listAnimated;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentAssignTicketBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        String eventTitle = args.getString(ARG_EVENT_TITLE);

        Ui.toolbar(this, binding.toolbar, getString(R.string.assign_tickets_title));
        if (eventTitle != null && !eventTitle.isEmpty()) binding.toolbar.setSubtitle(eventTitle);

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
                applyFilter();
            }
        });

        binding.swipeRefresh.setOnRefreshListener(this::fetch);
        binding.btnGrantPermission.setOnClickListener(v -> requestCameraAndEnter());
        binding.btnTorch.setOnClickListener(v -> toggleTorch());
        binding.btnPickSomeoneElse.setOnClickListener(v -> go(Step.PICK_PERSON));
        binding.btnAssignElsewhere.setOnClickListener(v -> {
            binding.etSearch.setText("");
            go(Step.PICK_PERSON);
        });
        binding.btnAssignAnother.setOnClickListener(v -> go(Step.SCAN_TICKET));

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (step == Step.PICK_PERSON) {
                            setEnabled(false);
                            Nav.back(requireActivity());
                        } else {
                            go(Step.PICK_PERSON);
                        }
                    }
                });

        loadAll();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    // ------------------------------------------------------------------ list loading

    private void loadAll() {
        List<Registration> cached = Api.peek("regs:" + eventId);
        if (cached != null) {
            all = cached;
            applyFilter();
        }
        binding.swipeRefresh.setRefreshing(cached == null);
        fetch();
    }

    private void fetch() {
        Api.getRegistrations(eventId, (list, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                return;
            }
            all = list;
            applyFilter();
        });
    }

    private void applyFilter() {
        String q = text(binding.etSearch);
        filtered.clear();
        if (q.isEmpty()) {
            filtered.addAll(all);
        } else {
            for (Registration r : all) if (r.matches(q)) filtered.add(r);
        }
        adapter.notifyDataSetChanged();
        if (!listAnimated && !filtered.isEmpty()) {
            listAnimated = true;
            Ui.animateList(binding.recyclerView);
        }

        boolean noneAtAll = all.isEmpty();
        boolean noMatch = !noneAtAll && filtered.isEmpty();
        binding.emptyAll.setVisibility(noneAtAll ? View.VISIBLE : View.GONE);
        binding.emptyFiltered.setVisibility(noMatch ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility((noneAtAll || noMatch) ? View.GONE : View.VISIBLE);
        updateCounts();
    }

    private void updateCounts() {
        int with = 0;
        for (Registration r : all) if (isBound(r)) with++;
        Ui.countTo(binding.tvCounts, with, getString(R.string.counts_suffix, all.size() - with));
    }

    private boolean isBound(Registration r) {
        return r.hasTicket() || justBoundIds.contains(r.id);
    }

    // ------------------------------------------------------------------ row taps

    private void onRowClicked(Registration r) {
        if (isBound(r)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.reassign_confirm_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.continue_label, (d, w) -> selectAndScan(r))
                    .show();
        } else {
            selectAndScan(r);
        }
    }

    private void selectAndScan(Registration r) {
        selected = r;
        go(Step.SCAN_TICKET);
    }

    // ------------------------------------------------------------------ step machine

    private void go(Step next) {
        if (binding == null) return;
        boolean wasList = step == Step.PICK_PERSON;
        boolean toList = next == Step.PICK_PERSON;
        step = next;

        if (wasList != toList) {
            crossfade(toList ? binding.paneList : binding.paneScan,
                    toList ? binding.paneScan : binding.paneList);
        }

        if (toList) {
            binding.scanner.stop();
            scannerStarted = false;
            if (binding.doneCard.getVisibility() == View.VISIBLE) Ui.fadeOut(binding.doneCard);
        } else if (next == Step.SCAN_TICKET) {
            enterScanTicket();
        } else if (next == Step.DONE) {
            showDone();
        }

        applyStepVisibility();
    }

    private void crossfade(View show, View hide) {
        show.setVisibility(View.VISIBLE);
        show.setAlpha(0f);
        show.animate().alpha(1f).setDuration(260).start();
        hide.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    if (binding == null) return;
                    hide.setVisibility(View.GONE);
                }).start();
    }

    private void applyStepVisibility() {
        if (binding == null) return;
        boolean hasCamera = ((MainActivity) requireActivity()).hasCamera();
        boolean showPermission = step != Step.PICK_PERSON && !hasCamera;
        binding.permissionPanel.setVisibility(showPermission ? View.VISIBLE : View.GONE);

        boolean showChrome = step != Step.PICK_PERSON && step != Step.DONE && hasCamera;
        binding.headerStrip.setVisibility(showChrome ? View.VISIBLE : View.GONE);
        binding.hintGroup.setVisibility(showChrome ? View.VISIBLE : View.GONE);
        binding.btnPickSomeoneElse.setVisibility(showChrome ? View.VISIBLE : View.GONE);

        boolean showProgress = step == Step.CHECKING || step == Step.ASSIGNING;
        binding.progressOverlay.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            binding.tvProgressLabel.setText(
                    step == Step.CHECKING ? R.string.checking_ticket : R.string.assigning_ticket);
        }
    }

    private void enterScanTicket() {
        if (selected == null || binding == null) return;
        binding.tvScanningFor.setText(getString(R.string.scanning_for, selected.name));
        binding.tvScanningRegNo.setText(selected.regNo);
        if (binding.doneCard.getVisibility() == View.VISIBLE) Ui.fadeOut(binding.doneCard);

        if (!((MainActivity) requireActivity()).hasCamera()) return;
        if (!scannerStarted) {
            scannerStarted = true;
            binding.scanner.start(getViewLifecycleOwner(), this::onQr);
            // ponytail: ScannerView has no "camera ready" callback, so a short delay is the
            // simplest way to know whether this device has a torch.
            binding.getRoot().postDelayed(() -> {
                if (binding == null) return;
                binding.btnTorch.setVisibility(binding.scanner.hasTorch() ? View.VISIBLE : View.GONE);
            }, 600);
        } else {
            binding.scanner.resumeScanning();
        }
    }

    private void requestCameraAndEnter() {
        ((MainActivity) requireActivity()).requestCamera(granted -> {
            if (binding == null) return;
            if (granted) enterScanTicket();
            applyStepVisibility();
        });
    }

    private void toggleTorch() {
        if (binding == null) return;
        boolean on = !binding.scanner.isTorchOn();
        binding.scanner.setTorch(on);
        binding.btnTorch.setActivated(on);
        binding.btnTorch.setAlpha(on ? 1f : 0.6f);
    }

    // ------------------------------------------------------------------ scan -> check -> assign

    private void onQr(String qr) {
        if (binding == null) return;
        binding.scanner.pauseScanning();
        go(Step.CHECKING);

        // check-qr does not exist on the server: it answers with an HTML 404, so this
        // reports "free" for every ticket today and the duplicate guard fails open.
        // Mirrors the documented behaviour, no workaround here.
        Api.checkQrPayloadExists(eventId, qr, (holder, err) -> {
            if (binding == null) return;
            if (holder != null) {
                showAlreadyAssignedDialog(holder, qr);
            } else {
                doAssign(qr);
            }
        });
    }

    private void showAlreadyAssignedDialog(String holder, String qr) {
        if (binding == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.ticket_already_assigned, holder))
                .setNegativeButton(R.string.cancel, (d, w) -> go(Step.SCAN_TICKET))
                .setPositiveButton(R.string.assign_anyway, (d, w) -> doAssign(qr))
                .setOnCancelListener(d -> go(Step.SCAN_TICKET))
                .show();
    }

    private void doAssign(String qr) {
        if (binding == null || selected == null) return;
        go(Step.ASSIGNING);
        Registration target = selected;
        Api.assignQrPayload(eventId, target.id, qr, (result, err2) -> {
            if (binding == null) return;
            if (err2 != null) {
                Ui.feedback(requireContext(), false);
                binding.scanner.flash(false);
                Ui.error(binding.getRoot(), err2);
                go(Step.SCAN_TICKET);
                return;
            }
            Ui.feedback(requireContext(), true);
            binding.scanner.flash(true);
            justBoundIds.add(target.id);
            lastQrPayload = qr;
            adapter.notifyDataSetChanged();
            updateCounts();
            go(Step.DONE);
        });
    }

    private void showDone() {
        if (binding == null || selected == null) return;
        binding.tvDoneName.setText(selected.name);
        binding.tvDoneRegNo.setText(selected.regNo);
        binding.tvDonePayload.setText(truncate(lastQrPayload));
        binding.btnAssignAnother.setText(getString(R.string.assign_another_to, selected.name));
        Ui.pop(binding.doneCard);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 24 ? s : s.substring(0, 24) + "...";
    }

    private static String text(com.google.android.material.textfield.TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        if (binding != null) binding.scanner.stop();
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------------ adapter

    private class RegAdapter extends RecyclerView.Adapter<RegAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ItemAssignRegistrationBinding b;

            VH(ItemAssignRegistrationBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemAssignRegistrationBinding b = ItemAssignRegistrationBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Registration r = filtered.get(position);
            ItemAssignRegistrationBinding b = holder.b;

            b.tvInitial.setText(r.name.isEmpty()
                    ? "?" : String.valueOf(Character.toUpperCase(r.name.charAt(0))));
            b.tvName.setText(r.name);
            b.tvRegNo.setText(r.regNo);
            b.tvEmail.setText(r.email);

            boolean bound = isBound(r);
            if (bound) {
                b.ivState.setImageResource(R.drawable.ic_check_circle);
                b.ivState.setImageTintList(ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                                b.getRoot().getContext(), R.color.scan_success)));
                b.tvState.setText(R.string.ticket_bound);
            } else {
                b.ivState.setImageResource(R.drawable.ic_chevron_right);
                b.ivState.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(
                        b.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant)));
                b.tvState.setText(R.string.tap_to_assign);
            }

            b.getRoot().setOnClickListener(v -> onRowClicked(r));
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }
    }
}
