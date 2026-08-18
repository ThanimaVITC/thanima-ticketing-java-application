package com.legitcoconut.thanimaticketing.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.ViewIdCardBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Small shared helpers. Formatting, feedback and the two animation idioms every screen uses. */
public final class Ui {

    private Ui() {
    }

    // ---------------------------------------------------------------- formatting

    private static final SimpleDateFormat ISO_Z =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    private static final SimpleDateFormat ISO_Z_NO_MS =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    static {
        ISO_Z.setTimeZone(TimeZone.getTimeZone("UTC"));
        ISO_Z_NO_MS.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** Returns 0 when the string is null or unparseable. */
    public static long parseIso(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        synchronized (ISO_Z) {
            try {
                return ISO_Z.parse(iso).getTime();
            } catch (Exception ignored) {
            }
            try {
                return ISO_Z_NO_MS.parse(iso).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    /** Mirrors the server formatDuration: 1h 05m, 5m 12s, 42s. */
    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        if (m > 0) return String.format(Locale.US, "%dm %02ds", m, s);
        return s + "s";
    }

    public static String formatDate(@Nullable String iso) {
        long t = parseIso(iso);
        if (t == 0L) return "Date not set";
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(t));
    }

    public static String formatDateTime(@Nullable String iso) {
        long t = parseIso(iso);
        if (t == 0L) return "";
        return new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(new Date(t));
    }

    public static String formatTime(@Nullable String iso) {
        long t = parseIso(iso);
        if (t == 0L) return "";
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t));
    }

    // ---------------------------------------------------------------- feedback

    public static void snack(View anchor, String message) {
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show();
    }

    public static void error(View anchor, String message) {
        Snackbar sb = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG);
        sb.setBackgroundTint(MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorErrorContainer));
        sb.setTextColor(MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnErrorContainer));
        sb.show();
    }

    /** Short tone plus haptic. Cheaper than shipping wav assets and audible over a crowd. */
    public static void feedback(Context context, boolean success) {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(success ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tone::release, 400);
        } catch (Exception ignored) {
        }
        vibrate(context, success);
    }

    public static void vibrate(Context context, boolean success) {
        Vibrator v = ContextCompat.getSystemService(context, Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        try {
            if (success) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 60, 80, 60}, -1));
            }
        } catch (SecurityException ignored) {
        }
    }

    // ---------------------------------------------------------------- animation

    /**
     * Puts a number into a stat TextView.
     *
     * Deliberately not a count up from zero: rolling digits make a screen that is merely
     * refreshing look like it is loading from scratch, which is exactly the wrong signal on
     * a door. The figure appears at its real value, and only a value that actually changed
     * gets a short cross fade so staff notice the update.
     */
    public static void countTo(TextView view, int target) {
        countTo(view, target, "");
    }

    public static void countTo(TextView view, int target, String suffix) {
        String next = target + suffix;
        String current = view.getText() == null ? "" : view.getText().toString();
        if (next.equals(current)) return;

        view.setText(next);
        if (current.isEmpty()) return;

        view.animate().cancel();
        view.setAlpha(0.35f);
        view.animate().alpha(1f).setDuration(220)
                .setInterpolator(new FastOutSlowInInterpolator()).start();
    }

    /** Entry animation for a list, free of per row code. */
    /**
     * Entry animation for a list, played ONCE.
     *
     * Replaying it on every data change is what made a background refresh look like a full
     * reload: the rows the user was already reading would slide in from nothing again. After
     * the first run the rows just update in place.
     */
    public static void animateList(RecyclerView list) {
        if (list.getTag(R.id.tag_list_animated) != null) return;
        list.setTag(R.id.tag_list_animated, Boolean.TRUE);
        list.setLayoutAnimation(
                AnimationUtils.loadLayoutAnimation(list.getContext(), R.anim.layout_slide_up));
        list.scheduleLayoutAnimation();
    }

    /**
     * Lets a list animate again on the next {@link #animateList}. For a deliberate content
     * switch, such as changing tab, where the new set really should animate in.
     */
    public static void resetListAnimation(RecyclerView list) {
        list.setTag(R.id.tag_list_animated, null);
    }

    /** Fills the shared ID card layout. Phone is optional, so its line drops out when empty. */
    public static void fillIdCard(ViewIdCardBinding card, String name, String regNo,
                                 @Nullable String phone) {
        card.tvIdName.setText(name);
        card.tvIdRegNo.setText(regNo);
        card.tvIdPhone.setVisibility(phone == null || phone.isEmpty() ? View.GONE : View.VISIBLE);
        card.tvIdPhone.setText(phone);
    }

    /** Springy pop for a result card appearing. */
    public static void pop(View view) {
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setScaleX(0.88f);
        view.setScaleY(0.88f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                .start();
    }

    public static void fadeOut(View view) {
        view.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    /**
     * Holds the shared element transition until the view has actually been laid out.
     * Without this the container transform starts against a zero sized target and jumps.
     */
    public static void startTransitionAfterLayout(Fragment fragment, View view) {
        fragment.postponeEnterTransition();
        view.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        view.getViewTreeObserver().removeOnPreDrawListener(this);
                        fragment.startPostponedEnterTransition();
                        return true;
                    }
                });
    }

    /** Every sub screen gets the same top bar behaviour: title plus a working back arrow. */
    public static void toolbar(Fragment fragment, com.google.android.material.appbar.MaterialToolbar bar,
                               CharSequence title) {
        bar.setTitle(title);
        bar.setNavigationIcon(R.drawable.ic_arrow_back);
        bar.setNavigationContentDescription(R.string.back);
        bar.setNavigationOnClickListener(v -> Nav.back(fragment.requireActivity()));
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
