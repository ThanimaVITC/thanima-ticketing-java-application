package com.legitcoconut.thanimaticketing.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentProfileBinding;
import com.legitcoconut.thanimaticketing.model.User;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

/** Who is signed in, what server they talk to, and the door out. */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private boolean animated;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Ui.toolbar(this, binding.toolbar, getString(R.string.profile));

        bindUser();
        binding.tvServerValue.setText(Api.baseUrl());
        binding.btnLogout.setOnClickListener(v -> confirmLogout());

        if (!animated) {
            animated = true;
            animateEntry();
        }
    }

    private void bindUser() {
        User user = Api.cachedUser();
        if (user == null) {
            binding.tvName.setText(R.string.profile_fallback_name);
            binding.tvEmail.setVisibility(View.GONE);
            binding.chipRole.setVisibility(View.GONE);
            binding.tvRoleValue.setText("-");
            return;
        }
        binding.tvName.setText(user.name.isEmpty() ? getString(R.string.profile_fallback_name) : user.name);
        if (user.email.isEmpty()) {
            binding.tvEmail.setVisibility(View.GONE);
        } else {
            binding.tvEmail.setVisibility(View.VISIBLE);
            binding.tvEmail.setText(user.email);
        }
        binding.chipRole.setVisibility(View.VISIBLE);
        binding.chipRole.setText(user.roleLabel());
        binding.tvRoleValue.setText(user.roleLabel());
    }

    /**
     * Naming the account and colouring the action red makes this a deliberate choice rather
     * than a reflex tap, which matters on a shared door phone.
     */
    private void confirmLogout() {
        User user = Api.cachedUser();
        String who = user == null || user.name.isEmpty()
                ? getString(R.string.profile_fallback_name) : user.name;

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.log_out)
                .setMessage(getString(R.string.log_out_confirm, who))
                .setPositiveButton(R.string.log_out_confirm_action, (d, w) -> {
                    Api.logout();
                    Nav.root(requireActivity(), new LoginFragment());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorError));
    }

    /** Same stagger as the login screen: fade in and rise 16dp, 60ms apart. */
    private void animateEntry() {
        View[] views = {
                binding.cardAvatar, binding.rowServer, binding.rowRole, binding.btnLogout,
        };
        int dy = Ui.dp(requireContext(), 16);
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            v.setAlpha(0f);
            v.setTranslationY(dy);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 60L)
                    .setDuration(320)
                    .setInterpolator(new FastOutSlowInInterpolator())
                    .start();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
