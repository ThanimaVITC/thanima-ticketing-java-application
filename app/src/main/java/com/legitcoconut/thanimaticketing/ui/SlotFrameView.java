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
 * Everything drawn over the ID card camera preview: the dimmed surround, the rounded card
 * shaped window, and four breathing corner brackets. {@link #frameRect()} is what
 * UnpaidScanFragment crops the capture to before running OCR.
 */
public class SlotFrameView extends View {

    private static final float ASPECT = 1.6f; // width : height, a landscape ID card

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF frame = new RectF();
    private final Path scrimPath = new Path();
    private final Path framePath = new Path();

    private float corner;
    private float bracketLength;
    private float bracketScale = 1f;

    private ValueAnimator pulseAnimator;

    public SlotFrameView(Context context) {
        this(context, null);
    }

    public SlotFrameView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        int accentColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary,
                Color.WHITE);
        scrimPaint.setColor(ContextCompat.getColor(context, R.color.scrim_camera));
        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPaint.setColor(accentColor);
        corner = dp(18);
        bracketPaint.setStrokeWidth(dp(5));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cardW = w * 0.86f;
        float cardH = cardW / ASPECT;
        if (cardH > h * 0.6f) {
            cardH = h * 0.6f;
            cardW = cardH * ASPECT;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        frame.set(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f);
        bracketLength = Math.min(cardW, cardH) * 0.22f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startPulse();
    }

    /** The card shaped guide rectangle, in this view's own coordinates. */
    public RectF frameRect() {
        return new RectF(frame);
    }

    /** Quick squeeze of the brackets, for the instant the shutter fires. */
    public void capture() {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.92f, 1f);
        a.setDuration(260);
        a.setInterpolator(new FastOutSlowInInterpolator());
        a.addUpdateListener(x -> {
            bracketScale = (float) x.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    private void startPulse() {
        if (pulseAnimator != null) return;
        pulseAnimator = ValueAnimator.ofInt(140, 220);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.addUpdateListener(a -> {
            bracketPaint.setAlpha((int) a.getAnimatedValue());
            invalidate();
        });
        pulseAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.isEmpty()) return;

        scrimPath.reset();
        scrimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        framePath.reset();
        framePath.addRoundRect(frame, corner, corner, Path.Direction.CW);
        scrimPath.op(framePath, Path.Op.DIFFERENCE);
        canvas.drawPath(scrimPath, scrimPaint);

        canvas.save();
        canvas.scale(bracketScale, bracketScale, frame.centerX(), frame.centerY());
        drawBrackets(canvas);
        canvas.restore();
    }

    private void drawBrackets(Canvas canvas) {
        float r = corner;
        float len = bracketLength;

        canvas.drawLine(frame.left, frame.top + r, frame.left, frame.top + r + len, bracketPaint);
        canvas.drawLine(frame.left + r, frame.top, frame.left + r + len, frame.top, bracketPaint);
        arc(canvas, frame.left, frame.top, 180f);

        canvas.drawLine(frame.right, frame.top + r, frame.right, frame.top + r + len, bracketPaint);
        canvas.drawLine(frame.right - r, frame.top, frame.right - r - len, frame.top, bracketPaint);
        arc(canvas, frame.right - 2 * r, frame.top, 270f);

        canvas.drawLine(frame.left, frame.bottom - r, frame.left, frame.bottom - r - len, bracketPaint);
        canvas.drawLine(frame.left + r, frame.bottom, frame.left + r + len, frame.bottom, bracketPaint);
        arc(canvas, frame.left, frame.bottom - 2 * r, 90f);

        canvas.drawLine(frame.right, frame.bottom - r, frame.right, frame.bottom - r - len, bracketPaint);
        canvas.drawLine(frame.right - r, frame.bottom, frame.right - r - len, frame.bottom, bracketPaint);
        arc(canvas, frame.right - 2 * r, frame.bottom - 2 * r, 0f);
    }

    private void arc(Canvas canvas, float left, float top, float startAngle) {
        canvas.drawArc(left, top, left + 2 * corner, top + 2 * corner, startAngle, 90f, false, bracketPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        super.onDetachedFromWindow();
    }
}
