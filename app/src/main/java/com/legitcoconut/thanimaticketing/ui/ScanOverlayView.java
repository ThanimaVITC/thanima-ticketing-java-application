package com.legitcoconut.thanimaticketing.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.MaterialColors;
import com.legitcoconut.thanimaticketing.R;

/**
 * Everything drawn over the camera: the dimmed surround, the rounded window and four corner
 * brackets. The frame is still until a code lands, then it reacts.
 */
public class ScanOverlayView extends View {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF window = new RectF();
    private final Path scrimPath = new Path();
    private final Path windowPath = new Path();

    private float corner;
    private float bracketLength;
    private float bracketScale = 1f;
    private float flashAlpha;

    private int accentColor;
    private int flashColor;

    public ScanOverlayView(Context context) {
        this(context, null);
    }

    public ScanOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        accentColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary,
                Color.WHITE);
        flashColor = accentColor;

        scrimPaint.setColor(ContextCompat.getColor(context, R.color.scrim_camera));
        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPaint.setColor(accentColor);
        fillPaint.setStyle(Paint.Style.FILL);
        corner = dp(30);
        bracketPaint.setStrokeWidth(dp(5));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float side = Math.min(w, h) * 0.7f;
        float cx = w / 2f;
        float cy = h / 2f - dp(24);
        window.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        bracketLength = side * 0.17f;
    }

    /** The frame the volunteer aims at, in view coordinates. */
    public RectF windowRect() {
        return new RectF(window);
    }

    /** Quick squeeze of the brackets the instant a code is picked up. */
    public void hit() {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.93f, 1f);
        a.setDuration(280);
        a.setInterpolator(new FastOutSlowInInterpolator());
        a.addUpdateListener(x -> {
            bracketScale = (float) x.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    /** Colours the frame for the outcome, then fades back to the accent. */
    public void flash(boolean success) {
        flashColor = ContextCompat.getColor(getContext(),
                success ? R.color.scan_success : R.color.md_error);
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(900);
        a.setInterpolator(new FastOutSlowInInterpolator());
        a.addUpdateListener(x -> {
            flashAlpha = (float) x.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (window.isEmpty()) return;

        scrimPath.reset();
        scrimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        windowPath.reset();
        windowPath.addRoundRect(window, corner, corner, Path.Direction.CW);
        scrimPath.op(windowPath, Path.Op.DIFFERENCE);
        canvas.drawPath(scrimPath, scrimPaint);

        if (flashAlpha > 0f) {
            fillPaint.setColor(flashColor);
            fillPaint.setAlpha((int) (60 * flashAlpha));
            canvas.drawRoundRect(window, corner, corner, fillPaint);
        }

        int blended = flashAlpha > 0f
                ? MaterialColors.layer(accentColor, flashColor, flashAlpha)
                : accentColor;
        bracketPaint.setColor(blended);

        canvas.save();
        canvas.scale(bracketScale, bracketScale, window.centerX(), window.centerY());
        drawBrackets(canvas);
        canvas.restore();
    }

    private void drawBrackets(Canvas canvas) {
        float r = corner;
        float len = bracketLength;

        // Top left
        canvas.drawLine(window.left, window.top + r, window.left, window.top + r + len, bracketPaint);
        canvas.drawLine(window.left + r, window.top, window.left + r + len, window.top, bracketPaint);
        arc(canvas, window.left, window.top, 180f);

        // Top right
        canvas.drawLine(window.right, window.top + r, window.right, window.top + r + len, bracketPaint);
        canvas.drawLine(window.right - r, window.top, window.right - r - len, window.top, bracketPaint);
        arc(canvas, window.right - 2 * r, window.top, 270f);

        // Bottom left
        canvas.drawLine(window.left, window.bottom - r, window.left, window.bottom - r - len, bracketPaint);
        canvas.drawLine(window.left + r, window.bottom, window.left + r + len, window.bottom, bracketPaint);
        arc(canvas, window.left, window.bottom - 2 * r, 90f);

        // Bottom right
        canvas.drawLine(window.right, window.bottom - r, window.right, window.bottom - r - len, bracketPaint);
        canvas.drawLine(window.right - r, window.bottom, window.right - r - len, window.bottom, bracketPaint);
        arc(canvas, window.right - 2 * r, window.bottom - 2 * r, 0f);
    }

    private void arc(Canvas canvas, float left, float top, float startAngle) {
        canvas.drawArc(left, top, left + 2 * corner, top + 2 * corner, startAngle, 90f, false,
                bracketPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
