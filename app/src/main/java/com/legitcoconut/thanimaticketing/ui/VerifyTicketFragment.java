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
import androidx.core.view.ViewCompat;
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
            Ui.fadeOut(binding.resultCard);
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

        int primary = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorPrimary);
        int neutralBg = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorSurfaceVariant);
        styleResultHeader(R.drawable.ic_verified, primary, neutralBg, getString(R.string.ticket_valid));

        binding.resultName.setText(ticket.name);
        binding.resultRegNo.setText(ticket.regNo);
        binding.resultEmail.setText(ticket.email);
        if (TextUtils.isEmpty(ticket.phone)) {
            binding.resultPhoneRow.setVisibility(View.GONE);
        } else {
            binding.resultPhone.setText(ticket.phone);
            binding.resultPhoneRow.setVisibility(View.VISIBLE);
        }
        binding.resultEvent.setText(ticket.eventTitle);

        if (ticket.hasAttended) {
            int c = ContextCompat.getColor(requireContext(), R.color.scan_success);
            binding.statusIcon.setImageResource(R.drawable.ic_check_circle);
            ImageViewCompat.setImageTintList(binding.statusIcon, ColorStateList.valueOf(c));
            binding.statusText.setText(R.string.already_marked);
            binding.statusText.setTextColor(c);
            binding.statusMeta.setText(Ui.formatDateTime(ticket.attendedAt));
            binding.statusMeta.setVisibility(View.VISIBLE);
        } else {
            int c = MaterialColors.getColor(binding.getRoot(),
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            binding.statusIcon.setImageResource(R.drawable.ic_info);
            ImageViewCompat.setImageTintList(binding.statusIcon, ColorStateList.valueOf(c));
            binding.statusText.setText(R.string.scan_not_marked);
            binding.statusText.setTextColor(c);
            binding.statusMeta.setVisibility(View.GONE);
        }

        binding.ticketDetails.setVisibility(View.VISIBLE);
        binding.resultMessage.setVisibility(View.GONE);

        Ui.pop(binding.resultCard);
    }

    private void showFailure(String error) {
        binding.scanner.setHint(null);
        Ui.feedback(requireContext(), false);
        binding.scanner.flash(false);

        int icon = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorOnErrorContainer);
        int bg = MaterialColors.getColor(binding.getRoot(),
                com.google.android.material.R.attr.colorErrorContainer);
        styleResultHeader(R.drawable.ic_error, icon, bg, getString(R.string.scan_verification_failed));

        binding.ticketDetails.setVisibility(View.GONE);
        binding.resultMessage.setText(error);
        binding.resultMessage.setVisibility(View.VISIBLE);

        Ui.pop(binding.resultCard);
    }

    private void styleResultHeader(@DrawableRes int drawable, int iconColor, int bgColor, String title) {
        binding.resultIcon.setImageResource(drawable);
        ImageViewCompat.setImageTintList(binding.resultIcon, ColorStateList.valueOf(iconColor));
        ViewCompat.setBackgroundTintList(binding.resultIcon, ColorStateList.valueOf(bgColor));
        binding.resultTitle.setText(title);
        binding.resultTitle.setTextColor(iconColor);
    }

    @Override
    public void onDestroyView() {
        if (binding != null) binding.scanner.stop();
        binding = null;
        super.onDestroyView();
    }
}
