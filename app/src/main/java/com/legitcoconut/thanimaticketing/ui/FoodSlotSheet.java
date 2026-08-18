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

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.SheetFoodSlotBinding;
import com.legitcoconut.thanimaticketing.model.FoodSession;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.net.Cb;
import com.legitcoconut.thanimaticketing.net.Res;
import com.legitcoconut.thanimaticketing.util.Ui;

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
 * Two ways in. {@link #showBeforeMark} is the one a fresh scan or tap takes: nothing has
 * reached the server yet, so backing out marks nobody, and the colour and the mark are spent
 * together on the tap. {@link #showIfNeeded} is the after-the-fact one, for somebody already
 * marked present who still owes a colour — there the mark cannot be taken back, so the sheet
 * refuses to close until a colour lands.
 *
 * Either way, once a mark is on the server the only escape is a failed call, where a close
 * button appears so a dead network cannot wedge the queue — the dashboard can assign those
 * stragglers afterwards.
 */
public class FoodSlotSheet extends BottomSheetDialogFragment {

    private static final String TAG = "food-slot";
    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EMAIL = "email";
    private static final String ARG_NAME = "name";
    private static final String ARG_REG_NO = "regNo";
    private static final String ARG_PHONE = "phone";

    /** Told the colour that was assigned, so the caller can show it on its result card. */
    public interface OnAssigned {
        void assigned(String colorName, int colorInt);
    }

    /**
     * Marks attendance, run once a colour has been chosen. The caller owns the call so it can
     * render its own result; the sheet only needs the outcome to know whether to go on.
     */
    public interface Mark {
        void run(Cb<Res> done);
    }

    /** Told whether anybody ended up marked, so a cancelled scan can say so and move on. */
    public interface OnClosed {
        void closed(boolean marked);
    }

    /**
     * Picker for somebody not yet marked present: no colour, no mark. Returns false when
     * there is nothing to ask, and the caller should then mark them the plain way.
     */
    public static boolean showBeforeMark(FragmentManager fm, String eventId,
                                         List<FoodSession> sessions, String name, String regNo,
                                         String email, String phone, Mark mark,
                                         OnAssigned onAssigned, OnClosed onClosed) {
        if (sessions == null || sessions.isEmpty()) return false;
        if (email == null || email.isEmpty()) return false;
        return show(fm, eventId, sessions, name, regNo, email, phone, mark, onAssigned, onClosed);
    }

    /**
     * Reads the food block an attendance response carries and shows the picker when the
     * attendee still needs a colour. Returns false when there is nothing to ask.
     */
    public static boolean showIfNeeded(FragmentManager fm, String eventId, JSONObject food,
                                       String name, String regNo, String email, String phone,
                                       OnAssigned onAssigned, OnClosed onClosed) {
        if (food == null || !food.optBoolean("enabled", false)) return false;
        if (!food.isNull("assignment")) return false;
        if (email == null || email.isEmpty()) return false;

        List<FoodSession> sessions;
        try {
            sessions = Api.foodSessions(food.optJSONArray("sessions"));
        } catch (JSONException e) {
            return false;
        }
        if (sessions.isEmpty()) return false;
        return show(fm, eventId, sessions, name, regNo, email, phone, null, onAssigned, onClosed);
    }

    private static boolean show(FragmentManager fm, String eventId, List<FoodSession> sessions,
                                String name, String regNo, String email, @Nullable String phone,
                                @Nullable Mark mark, OnAssigned onAssigned, OnClosed onClosed) {
        FoodSlotSheet sheet = new FoodSlotSheet();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EMAIL, email);
        args.putString(ARG_NAME, name);
        args.putString(ARG_REG_NO, regNo);
        args.putString(ARG_PHONE, phone);
        sheet.setArguments(args);
        sheet.sessions.addAll(sessions);
        sheet.mark = mark;
        sheet.onAssigned = onAssigned;
        sheet.onClosed = onClosed;
        sheet.show(fm, TAG);
        return true;
    }

    private SheetFoodSlotBinding binding;
    @Nullable
    private Mark mark;
    private OnAssigned onAssigned;
    private OnClosed onClosed;
    private String eventId;
    private String email;
    private final List<FoodSession> sessions = new ArrayList<>();
    private boolean assigning;
    /** Chosen but not yet spent. Nothing leaves this screen until the button is pressed. */
    @Nullable
    private FoodSession selected;
    /** True once the mark is on the server, which is the point there is no going back from. */
    private boolean marked;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Escapable only while nobody is marked yet.
        setCancelable(mark != null);
        Bundle args = requireArguments();
        eventId = args.getString(ARG_EVENT_ID);
        email = args.getString(ARG_EMAIL);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setCanceledOnTouchOutside(false);
        // A card plus circles plus a button never fits the collapsed peek, so skip it.
        dialog.setOnShowListener(d -> {
            View sheet = ((BottomSheetDialog) d)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
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

        Bundle args = requireArguments();
        Ui.fillIdCard(binding.idCard, args.getString(ARG_NAME, ""),
                args.getString(ARG_REG_NO, ""), args.getString(ARG_PHONE));

        binding.confirmButton.setText(mark != null
                ? R.string.food_mark_attendance : R.string.food_assign_slot);
        binding.confirmButton.setOnClickListener(v -> {
            if (selected != null) assign(selected);
        });
        binding.closeButton.setOnClickListener(v -> dismiss());
        renderSlots();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        // The caller holds its scanner paused while this is up, so it needs telling either
        // way — assigned, closed after a failure, or backed out before anything was marked.
        if (onClosed != null) onClosed.closed(mark == null || marked);
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
            return column;
        }

        // Chosen, not spent: the button is what commits it.
        boolean chosen = selected != null && selected.id.equals(session.id);
        if (chosen) {
            circle.setStroke(Ui.dp(requireContext(), 4),
                    MaterialColors.getColor(column, com.google.android.material.R.attr.colorPrimary));
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            column.setAlpha(selected == null ? 1f : 0.45f);
        }
        column.setOnClickListener(v -> {
            if (assigning) return;
            selected = session;
            renderSlots();
            binding.confirmButton.setEnabled(true);
        });
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
        binding.confirmButton.setEnabled(false);
        binding.slotGrid.setAlpha(0.5f);

        if (mark != null && !marked) {
            markThenAssign(session);
            return;
        }
        assignSlot(session);
    }

    /**
     * Picking the colour is what commits the mark, so the two go together. A mark that fails
     * leaves nobody marked and the sheet open to try again.
     */
    private void markThenAssign(FoodSession session) {
        mark.run((res, err) -> {
            if (binding == null) return;
            // alreadyMarked means somebody else got there first, which still leaves the
            // colour owed, so it counts as through.
            boolean ok = err == null && res != null && (res.ok() || res.flag("alreadyMarked"));
            if (!ok) {
                assigning = false;
                binding.slotGrid.setAlpha(1f);
                binding.confirmButton.setEnabled(true);
                showError(err != null ? err
                        : res == null ? getString(R.string.att_mark_failed)
                        : res.error(getString(R.string.att_mark_failed)));
                return;
            }
            marked = true;
            setCancelable(false);
            assignSlot(session);
        });
    }

    private void assignSlot(FoodSession session) {
        Api.assignFoodSlot(eventId, email, session.id, (res, err) -> {
            if (binding == null) return;
            assigning = false;
            binding.slotGrid.setAlpha(1f);
            binding.confirmButton.setEnabled(selected != null);

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
                // The colour that just filled up cannot stay chosen.
                if (selected != null && selected.id.equals(updated.id)) selected = null;
                break;
            }
        }
        renderSlots();
        binding.confirmButton.setEnabled(selected != null);
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
