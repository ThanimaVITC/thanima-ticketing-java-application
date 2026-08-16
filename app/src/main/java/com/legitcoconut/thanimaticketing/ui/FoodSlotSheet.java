package com.legitcoconut.thanimaticketing.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.SheetFoodSlotBinding;
import com.legitcoconut.thanimaticketing.model.FoodSession;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The colour picker that follows every attendance mark. Assigning a colour is what takes a
 * seat, so this is the screen that spends capacity — the food counter later only checks it.
 *
 * Opened from both marking paths, the QR scanner and the manual list, because an attendee
 * marked either way needs a colour just the same.
 *
 * Not dismissible: picking is mandatory. The one exception is after a failed call, where a
 * close button appears so a dead network cannot wedge the queue — the dashboard can assign
 * those stragglers afterwards.
 */
public class FoodSlotSheet extends BottomSheetDialogFragment {

    private static final String TAG = "food-slot";
    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EMAIL = "email";
    private static final String ARG_NAME = "name";
    private static final String ARG_REG_NO = "regNo";
    private static final String ARG_SESSIONS = "sessions";

    /** Told the colour that was assigned, so the caller can show it on its result card. */
    public interface OnAssigned {
        void assigned(String colorName, int colorInt);
    }

    /**
     * Reads the food block an attendance response carries and shows the picker when the
     * attendee still needs a colour. Returns false when there is nothing to ask.
     */
    public static boolean showIfNeeded(FragmentManager fm, String eventId, JSONObject food,
                                       String name, String regNo, String email,
                                       OnAssigned onAssigned, Runnable onClosed) {
        if (food == null || !food.optBoolean("enabled", false)) return false;
        if (!food.isNull("assignment")) return false;
        if (email == null || email.isEmpty()) return false;

        JSONArray sessions = food.optJSONArray("sessions");
        if (sessions == null || sessions.length() == 0) return false;

        FoodSlotSheet sheet = new FoodSlotSheet();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EMAIL, email);
        args.putString(ARG_NAME, name);
        args.putString(ARG_REG_NO, regNo);
        args.putString(ARG_SESSIONS, sessions.toString());
        sheet.setArguments(args);
        sheet.onAssigned = onAssigned;
        sheet.onClosed = onClosed;
        sheet.show(fm, TAG);
        return true;
    }

    private SheetFoodSlotBinding binding;
    private OnAssigned onAssigned;
    private Runnable onClosed;
    private String eventId;
    private String email;
    private final List<FoodSession> sessions = new ArrayList<>();
    private boolean assigning;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        email = args.getString(ARG_EMAIL);
        parseSessions(args.getString(ARG_SESSIONS));
    }

    private void parseSessions(String raw) {
        sessions.clear();
        try {
            sessions.addAll(Api.foodSessions(new JSONArray(raw == null ? "[]" : raw)));
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetFoodSlotBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String name = requireArguments().getString(ARG_NAME, "");
        String regNo = requireArguments().getString(ARG_REG_NO, "");
        binding.attendee.setText(regNo.isEmpty() ? name : getString(R.string.food_attendee_format, name, regNo));

        binding.closeButton.setOnClickListener(v -> dismiss());
        renderSlots();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        // The caller holds its scanner paused while this is up, so it needs telling either
        // way — assigned or closed after a failure.
        if (onClosed != null) onClosed.run();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ---------------------------------------------------------------- circles

    private void renderSlots() {
        if (binding == null) return;
        binding.slotGrid.removeAllViews();
        binding.emptyState.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);

        for (FoodSession s : sessions) {
            binding.slotGrid.addView(buildSlot(s));
        }
    }

    /** A colour circle with the seats still free inside it and the colour's name below. */
    private View buildSlot(FoodSession session) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(requireContext(), 10);
        column.setPadding(pad, pad, pad, pad);

        int size = Ui.dp(requireContext(), 72);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(session.colorInt);
        if (session.nearLimit && !session.full) {
            circle.setStroke(Ui.dp(requireContext(), 3),
                    requireContext().getColor(R.color.scan_warn));
        }

        TextView bubble = new TextView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        bubble.setLayoutParams(lp);
        bubble.setBackground(circle);
        bubble.setGravity(Gravity.CENTER);
        bubble.setText(String.valueOf(session.remainingToMax));
        bubble.setTextSize(22f);
        bubble.setTextColor(readableOn(session.colorInt));

        TextView label = new TextView(requireContext());
        label.setText(session.colorName);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(13f);
        label.setPadding(0, Ui.dp(requireContext(), 6), 0, 0);

        column.addView(bubble);
        column.addView(label);

        if (session.full) {
            column.setAlpha(0.3f);
            column.setEnabled(false);
            label.setText(getString(R.string.food_slot_full_format, session.colorName));
        } else {
            column.setOnClickListener(v -> assign(session));
        }
        return column;
    }

    /** Dark text on a light circle, light on a dark one, so the count stays readable. */
    private static int readableOn(int background) {
        double luminance = (0.299 * Color.red(background)
                + 0.587 * Color.green(background)
                + 0.114 * Color.blue(background)) / 255d;
        return luminance > 0.6 ? 0xDD000000 : 0xFFFFFFFF;
    }

    // ---------------------------------------------------------------- assigning

    private void assign(FoodSession session) {
        if (assigning || binding == null) return;
        assigning = true;
        binding.errorText.setVisibility(View.GONE);
        binding.slotGrid.setAlpha(0.5f);

        Api.assignFoodSlot(eventId, email, session.id, (res, err) -> {
            if (binding == null) return;
            assigning = false;
            binding.slotGrid.setAlpha(1f);

            if (err != null) {
                showError(err);
                return;
            }

            if (res.ok() || res.flag("ok")) {
                Ui.feedback(requireContext(), true);
                if (onAssigned != null) onAssigned.assigned(session.colorName, session.colorInt);
                dismiss();
                return;
            }

            // Someone else took the last seat, or this attendee was assigned elsewhere in
            // the meantime. Redraw from the truth the server just sent back.
            if (res.flag("full")) {
                JSONObject fresh = res.obj("session");
                replaceSession(fresh);
                showError(getString(R.string.food_slot_taken_format, session.colorName));
                return;
            }

            if (res.flag("alreadyAssigned")) {
                JSONObject held = res.data.optJSONObject("assignment");
                String colorName = held == null ? "" : held.optString("colorName", "");
                if (onAssigned != null && !colorName.isEmpty()) {
                    onAssigned.assigned(colorName, session.colorInt);
                }
                dismiss();
                return;
            }

            showError(res.error(getString(R.string.food_assign_failed)));
        });
    }

    private void replaceSession(JSONObject fresh) {
        if (fresh == null || fresh.length() == 0) return;
        FoodSession updated = new FoodSession(fresh);
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(updated.id)) {
                sessions.set(i, updated);
                break;
            }
        }
        renderSlots();
    }

    private void showError(String message) {
        if (binding == null) return;
        binding.errorText.setText(message);
        binding.errorText.setVisibility(View.VISIBLE);
        // Only once something has gone wrong does an escape appear, so a dead network
        // cannot hold up the door.
        binding.closeButton.setVisibility(View.VISIBLE);
        Ui.feedback(requireContext(), false);
    }
}
