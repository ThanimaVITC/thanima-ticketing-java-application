package com.legitcoconut.thanimaticketing.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.legitcoconut.thanimaticketing.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera, once. Every scanning screen drops this in rather than wiring CameraX itself.
 *
 * Reads QR codes only, keeps the newest frame, and refuses to report the same payload twice
 * inside the cooldown so a code lingering in frame does not fire repeatedly.
 */
public class ScannerView extends FrameLayout {

    public interface OnQr {
        void onQr(String value);
    }

    private static final long SAME_CODE_COOLDOWN_MS = 2500L;

    private PreviewView preview;
    private ScanOverlayView overlay;
    private android.widget.TextView hint;
    private Runnable onCameraReady;
    private ExecutorService analysisExecutor;
    private BarcodeScanner reader;
    private ProcessCameraProvider provider;
    private Camera camera;

    private OnQr listener;
    private boolean scanning;
    private String lastValue;
    private long lastValueAt;

    public ScannerView(Context context) {
        this(context, null);
    }

    public ScannerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_scanner, this);
        preview = findViewById(R.id.scanner_preview);
        overlay = findViewById(R.id.scanner_overlay);
        hint = findViewById(R.id.scanner_hint);
        overlay.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> positionHint());
    }

    /**
     * Sits the hint below the aiming frame rather than at a guessed fraction of the screen,
     * so it never lands inside the window on a short or a tall phone.
     */
    private void positionHint() {
        android.graphics.RectF frame = overlay.windowRect();
        if (frame.isEmpty()) return;
        LayoutParams lp = (LayoutParams) hint.getLayoutParams();
        int margin = Math.round(frame.bottom + 28 * getResources().getDisplayMetrics().density);
        if (lp.topMargin == margin) return;
        lp.topMargin = margin;
        hint.setLayoutParams(lp);
    }

    /** The line under the frame. Pass null to hide it. */
    public void setHint(CharSequence text) {
        if (text == null || text.length() == 0) {
            hint.setVisibility(GONE);
            return;
        }
        hint.setText(text);
        hint.setVisibility(VISIBLE);
        positionHint();
    }

    /** Fires once the camera is bound, which is the first moment hasTorch() is meaningful. */
    public void setOnCameraReady(Runnable callback) {
        this.onCameraReady = callback;
    }

    /** Camera permission must already be granted. */
    public void start(LifecycleOwner owner, OnQr onQr) {
        this.listener = onQr;
        if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor();
        if (reader == null) {
            reader = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build());
        }
        scanning = true;

        var future = ProcessCameraProvider.getInstance(getContext());
        future.addListener(() -> {
            try {
                provider = future.get();
                bind(owner);
            } catch (Exception ignored) {
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private void bind(LifecycleOwner owner) {
        Preview p = new Preview.Builder().build();
        p.setSurfaceProvider(preview.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyse);

        provider.unbindAll();
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis);
        if (onCameraReady != null) post(onCameraReady);
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyse(ImageProxy image) {
        if (image.getImage() == null) {
            image.close();
            return;
        }
        if (!scanning) {
            image.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(
                image.getImage(), image.getImageInfo().getRotationDegrees());
        reader.process(input)
                .addOnSuccessListener(codes -> {
                    for (Barcode code : codes) {
                        String value = code.getRawValue();
                        if (value == null || value.isEmpty()) continue;
                        post(() -> deliver(value));
                        break;
                    }
                })
                .addOnCompleteListener(t -> image.close());
    }

    private void deliver(String value) {
        if (!scanning || listener == null) return;
        long now = System.currentTimeMillis();
        if (value.equals(lastValue) && now - lastValueAt < SAME_CODE_COOLDOWN_MS) return;
        lastValue = value;
        lastValueAt = now;
        overlay.hit();
        listener.onQr(value);
    }

    /** Keeps the preview alive but stops reporting codes, for while a result is on screen. */
    public void pauseScanning() {
        scanning = false;
    }

    public void resumeScanning() {
        scanning = true;
        lastValue = null;
    }

    public void stop() {
        scanning = false;
        if (provider != null) provider.unbindAll();
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
    }

    public boolean hasTorch() {
        return camera != null && camera.getCameraInfo().hasFlashUnit();
    }

    public void setTorch(boolean on) {
        if (camera != null && hasTorch()) camera.getCameraControl().enableTorch(on);
    }

    public boolean isTorchOn() {
        return camera != null && camera.getCameraInfo().getTorchState().getValue() != null
                && camera.getCameraInfo().getTorchState().getValue() == 1;
    }

    /** Green or red pulse around the frame, matching the outcome the screen just got. */
    public void flash(boolean success) {
        overlay.flash(success);
    }
}
