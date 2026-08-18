package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentVerifyTicketBinding;
import com.legitcoconut.thanimaticketing.model.Ticket;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Ui;

/**
 * Read only ticket lookup, it never marks attendance. Unlike ScanFragment the result stays on
 * screen until the volunteer taps "Scan again", since there is more to read here.
 */
public class VerifyTicketFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    public static VerifyTicketFragment newInstance(String eventId, String eventTitle) {
        VerifyTicketFragment fragment = new VerifyTicketFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        fragment.setArguments(args);
        return fragment;
    }

    private FragmentVerifyTicketBinding binding;
    private String eventId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentVerifyTicketBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        String eventTitle = args.getString(ARG_EVENT_TITLE);

        Ui.toolbar(this, binding.toolbar, getString(R.string.verify_ticket));
        if (!TextUtils.isEmpty(eventTitle)) binding.toolbar.setSubtitle(eventTitle);

        binding.grantAccess.setOnClickListener(v -> requestCameraAndStart());
        binding.torch.setOnClickListener(v -> toggleTorch());
        binding.scanAgain.setOnClickListener(v -> {
            if (binding == null) return;
            Ui.fadeOut(binding.resultPanel);
            binding.scanner.resumeScanning();
            binding.scanner.setHint(getString(R.string.point_at_qr));
        });

        if (((MainActivity) requireActivity()).hasCamera()) {
            showScanner();
        } else {
            binding.permissionPanel.setVisibility(View.VISIBLE);
        }
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

    private void requestCameraAndStart() {
        ((MainActivity) requireActivity()).requestCamera(granted -> {
            if (binding == null) return;
            if (granted) showScanner();
        });
    }

    private void showScanner() {
        binding.permissionPanel.setVisibility(View.GONE);
        binding.scanner.setOnCameraReady(() -> {
            if (binding == null) return;
            binding.torch.setVisibility(binding.scanner.hasTorch() ? View.VISIBLE : View.GONE);
        });
        binding.scanner.start(getViewLifecycleOwner(), this::onQr);
        binding.scanner.setHint(getString(R.string.point_at_qr));
    }

    private void toggleTorch() {
        if (binding == null) return;
        boolean on = !binding.scanner.isTorchOn();
        binding.scanner.setTorch(on);
        binding.torch.setActivated(on);
        binding.torch.setAlpha(on ? 1f : 0.6f);
    }

    private void onQr(String value) {
        if (binding == null) return;
        binding.scanner.pauseScanning();
        Api.verifyTicket(value, eventId, (ticket, error) -> {
            if (binding == null) return;
            if (ticket != null) {
                showTicket(ticket);
            } else {
                showFailure(error);
            }
        });
    }

    private void showTicket(Ticket ticket) {
        binding.scanner.setHint(null);
        Ui.feedback(requireContext(), true);
        binding.scanner.flash(true);

        Ui.fillIdCard(binding.idCard, ticket.name, ticket.regNo, ticket.phone);
        binding.idCard.getRoot().setVisibility(View.VISIBLE);

        if (ticket.hasAttended) {
            setStatus(R.drawable.ic_check_circle,
                    ContextCompat.getColor(requireContext(), R.color.scan_success),
                    getString(R.string.already_marked), Ui.formatDateTime(ticket.attendedAt));
        } else {
            setStatus(R.drawable.ic_info, MaterialColors.getColor(binding.getRoot(),
                            com.google.android.material.R.attr.colorOnSurfaceVariant),
                    getString(R.string.scan_not_marked), null);
        }

        binding.resultEmail.setText(ticket.email);
        binding.resultEvent.setText(ticket.eventTitle);
        binding.ticketDetails.setVisibility(View.VISIBLE);

        Ui.pop(binding.resultPanel);
    }

    private void showFailure(String error) {
        binding.scanner.setHint(null);
        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);

        // No card without a ticket to build it from, so the failure stands on its own.
        binding.idCard.getRoot().setVisibility(View.GONE);
        binding.ticketDetails.setVisibility(View.GONE);
        setStatus(R.drawable.ic_error,
                MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorError),
                TextUtils.isEmpty(error) ? getString(R.string.scan_verification_failed) : error, null);

        Ui.pop(binding.resultPanel);
    }

    private void setStatus(@DrawableRes int icon, int color, String text, @Nullable String meta) {
        binding.statusIcon.setImageResource(icon);
        ImageViewCompat.setImageTintList(binding.statusIcon, ColorStateList.valueOf(color));
        binding.statusText.setText(text);
        binding.statusText.setTextColor(color);
        binding.statusMeta.setVisibility(meta == null ? View.GONE : View.VISIBLE);
        binding.statusMeta.setText(meta);
    }

    @Override
    public void onDestroyView() {
        if (binding != null) binding.scanner.stop();
        binding = null;
        super.onDestroyView();
    }
}
