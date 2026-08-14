package com.legitcoconut.thanimaticketing.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentLoginBinding;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

/** The one entry point when there is no valid session. Email and password, plus a
 * collapsible server URL override for pointing at a different backend. */
public class LoginFragment extends Fragment {

    private FragmentLoginBinding binding;
    private boolean animated;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.etServerUrl.setText(Api.baseUrl());
        clearErrorOnType(binding.etEmail, binding.tilEmail);
        clearErrorOnType(binding.etPassword, binding.tilPassword);

        binding.btnToggleServer.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(binding.serverSection, new AutoTransition());
            boolean expand = binding.tilServerUrl.getVisibility() != View.VISIBLE;
            binding.tilServerUrl.setVisibility(expand ? View.VISIBLE : View.GONE);
        });

        binding.btnSignIn.setOnClickListener(v -> attemptSignIn());

        if (!animated) {
            animated = true;
            animateEntry();
        }
    }

    private void clearErrorOnType(com.google.android.material.textfield.TextInputEditText et,
                                   com.google.android.material.textfield.TextInputLayout til) {
        et.addTextChangedListener(new TextWatcher() {
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
        });
    }

    private void attemptSignIn() {
        hideKeyboard();

        String email = text(binding.etEmail);
        String password = text(binding.etPassword);

        boolean valid = true;
        if (email.isEmpty()) {
            binding.tilEmail.setError(getString(R.string.err_email_required));
            valid = false;
        }
        if (password.isEmpty()) {
            binding.tilPassword.setError(getString(R.string.err_password_required));
            valid = false;
        }
        if (!valid) return;

        // The login request reads the stored URL, so any override must land first.
        String serverUrl = text(binding.etServerUrl);
        if (!serverUrl.isEmpty() && !serverUrl.equals(Api.baseUrl())) {
            Api.setServerUrl(serverUrl);
        }

        setLoading(true);
        Api.login(email, password, (user, error) -> {
            if (binding == null) return;
            if (error != null) {
                setLoading(false);
                Ui.error(binding.getRoot(), error);
                return;
            }
            Nav.root(requireActivity(), new EventsFragment());
        });
    }

    private void setLoading(boolean loading) {
        binding.btnSignIn.setEnabled(!loading);
        binding.btnSignIn.setText(loading ? R.string.signing_in : R.string.sign_in);
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null) focus = binding.getRoot();
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private static String text(com.google.android.material.textfield.TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    /** Logo, then each field, then the button. Fade in and rise 16dp, 60ms apart. */
    private void animateEntry() {
        View[] views = {
                binding.ivLogo, binding.tvAppName, binding.tvTagline,
                binding.tilEmail, binding.tilPassword, binding.serverSection, binding.btnContainer,
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
