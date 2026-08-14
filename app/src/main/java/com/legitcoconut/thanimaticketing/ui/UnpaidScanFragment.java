package com.legitcoconut.thanimaticketing.ui;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.legitcoconut.thanimaticketing.BuildConfig;
import com.legitcoconut.thanimaticketing.MainActivity;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentUnpaidScanBinding;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Nav;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photographs an unpaid person's ID card, reads it with on device OCR, lets the volunteer
 * confirm or fix what was read, then saves it with source "ocr". One explicit step at a time.
 */
public class UnpaidScanFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";
    private static final int MAX_SIDE = 1600;

    public static UnpaidScanFragment newInstance(String eventId, String eventTitle) {
        UnpaidScanFragment f = new UnpaidScanFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private enum Step { ALIGNING, READING, CONFIRMING, SAVING, DONE }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FragmentUnpaidScanBinding binding;
    private String eventId;

    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera camera;
    private TextRecognizer recognizer;

    private Bitmap capturedBitmap;
    private boolean torchOn;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentUnpaidScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (BuildConfig.DEBUG) {
            IdCardParser.selfCheck();
        }

        eventId = requireArguments().getString(ARG_EVENT_ID);
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        binding.btnBack.setOnClickListener(v -> Nav.back(requireActivity()));
        binding.btnTorch.setOnClickListener(v -> toggleTorch());
        binding.btnCapture.setOnClickListener(v -> capture());
        binding.btnGrant.setOnClickListener(v -> requestPermission());

        binding.etConfirmName.addTextChangedListener(clearErrorWatcher(binding.tilConfirmName));
        binding.etConfirmRegNo.addTextChangedListener(clearErrorWatcher(binding.tilConfirmRegNo));
        binding.btnRetake.setOnClickListener(v -> retake());
        binding.btnSaveScan.setOnClickListener(v -> saveEntry());

        binding.btnScanAnother.setOnClickListener(v -> retake());
        binding.btnFinishDone.setOnClickListener(v -> Nav.back(requireActivity()));

        showStep(Step.ALIGNING);
        checkPermissionAndStart();
    }

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    // ------------------------------------------------------------------ permission + camera

    private void checkPermissionAndStart() {
        if (activity().hasCamera()) {
            showPermission(false);
            bindCamera();
        } else {
            showPermission(true);
        }
    }

    private void requestPermission() {
        activity().requestCamera(granted -> {
            if (binding == null) return;
            if (granted) {
                showPermission(false);
                bindCamera();
            }
        });
    }

    private void showPermission(boolean show) {
        binding.groupPermission.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.cameraContent.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void bindCamera() {
        var future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            if (binding == null) return;
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                binding.btnTorch.setVisibility(
                        camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
                torchOn = false;
                setTorchIcon(false);
            } catch (Exception ignored) {
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void unbindCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        camera = null;
        imageCapture = null;
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        setTorchIcon(torchOn);
    }

    private void setTorchIcon(boolean on) {
        int color = MaterialColors.getColor(binding.getRoot(), on
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorSurface);
        ImageViewCompat.setImageTintList(binding.ivTorchIcon, ColorStateList.valueOf(color));
    }

    // ------------------------------------------------------------------ capture

    private void capture() {
        if (imageCapture == null) return;
        binding.slotFrame.capture();
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                int rotation = image.getImageInfo().getRotationDegrees();
                byte[] bytes = toBytes(image);
                image.close();
                mainHandler.post(() -> onCaptured(bytes, rotation));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                mainHandler.post(() -> {
                    if (binding == null) return;
                    Ui.error(binding.getRoot(), getString(R.string.up_capture_failed));
                });
            }
        });
    }

    private static byte[] toBytes(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private void onCaptured(byte[] jpegBytes, int rotationDegrees) {
        if (binding == null) return;
        showStep(Step.READING);
        unbindCamera();

        Bitmap raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        Bitmap upright = rotate(raw, rotationDegrees);

        RectF viewFrame = binding.slotFrame.frameRect();
        Bitmap cropped = cropToFrame(upright, viewFrame,
                binding.previewView.getWidth(), binding.previewView.getHeight());
        Bitmap scaled = downscale(cropped, MAX_SIDE);

        capturedBitmap = scaled;
        runOcr(scaled);
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    /**
     * Maps the on screen guide rectangle into image pixels, assuming the PreviewView's default
     * fill center scale type, then crops to it. This is what keeps OCR reliable: the recognizer
     * only ever sees the card, not the desk around it.
     */
    private static Bitmap cropToFrame(Bitmap src, RectF viewFrame, int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) return src;
        int imgW = src.getWidth();
        int imgH = src.getHeight();
        float scale = Math.max((float) viewW / imgW, (float) viewH / imgH);
        float offsetX = (viewW - imgW * scale) / 2f;
        float offsetY = (viewH - imgH * scale) / 2f;

        int left = clamp(Math.round((viewFrame.left - offsetX) / scale), 0, imgW);
        int top = clamp(Math.round((viewFrame.top - offsetY) / scale), 0, imgH);
        int right = clamp(Math.round((viewFrame.right - offsetX) / scale), 0, imgW);
        int bottom = clamp(Math.round((viewFrame.bottom - offsetY) / scale), 0, imgH);
        if (right <= left || bottom <= top) return src;

        Bitmap out = Bitmap.createBitmap(src, left, top, right - left, bottom - top);
        if (out != src) src.recycle();
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Bitmap downscale(Bitmap src, int maxSide) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= maxSide) return src;
        float scale = maxSide / (float) longest;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.round(src.getWidth() * scale), Math.round(src.getHeight() * scale), true);
        if (out != src) src.recycle();
        return out;
    }

    // ------------------------------------------------------------------ OCR

    private void runOcr(Bitmap bitmap) {
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> {
                    if (binding == null) return;
                    showConfirm(IdCardParser.parse(toReadingOrder(text)));
                })
                .addOnFailureListener(e -> {
                    if (binding == null) return;
                    showConfirm(new IdCardParser.Result(null, null));
                });
    }

    /** Top to bottom, then left to right, joined with newlines for IdCardParser. */
    private static String toReadingOrder(Text text) {
        List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
        blocks.sort(Comparator
                .comparingInt((Text.TextBlock b) -> b.getBoundingBox() != null ? b.getBoundingBox().top : 0)
                .thenComparingInt(b -> b.getBoundingBox() != null ? b.getBoundingBox().left : 0));
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : blocks) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(block.getText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ confirm

    private void showConfirm(IdCardParser.Result result) {
        binding.ivCaptured.setImageBitmap(capturedBitmap);
        binding.etConfirmName.setText(result.name);
        binding.etConfirmRegNo.setText(result.regNo);
        binding.tvOcrHint.setVisibility(
                result.name == null || result.regNo == null ? View.VISIBLE : View.GONE);
        showStep(Step.CONFIRMING);
    }

    private void retake() {
        binding.ivCaptured.setImageDrawable(null);
        if (capturedBitmap != null) {
            capturedBitmap.recycle();
            capturedBitmap = null;
        }
        binding.etConfirmName.setText("");
        binding.etConfirmRegNo.setText("");
        binding.tilConfirmName.setError(null);
        binding.tilConfirmRegNo.setError(null);
        showStep(Step.ALIGNING);
        checkPermissionAndStart();
    }

    private void saveEntry() {
        String rawName = text(binding.etConfirmName);
        String rawRegNo = text(binding.etConfirmRegNo);

        String nameErr = IdCardParser.nameError(rawName);
        String regNoErr = IdCardParser.regNoError(rawRegNo);
        binding.tilConfirmName.setError(nameErr);
        binding.tilConfirmRegNo.setError(regNoErr);
        if (nameErr != null || regNoErr != null) return;

        String name = IdCardParser.normalizeName(rawName);
        String regNo = IdCardParser.normalizeRegNo(rawRegNo);

        showStep(Step.SAVING);
        Api.addUnpaid(eventId, name, regNo, "ocr", (res, err) -> {
            if (binding == null) return;
            if (err != null) {
                showStep(Step.CONFIRMING);
                Ui.error(binding.getRoot(), err);
                return;
            }
            if (res.code == 201 || res.flag("ok")) {
                Ui.feedback(requireContext(), true);
                finishWith(name, regNo, null);
            } else if (res.code == 409 && res.flag("alreadyListed")) {
                String already = res.obj("entry").optString("name", name);
                finishWith(already, regNo, getString(R.string.unpaid_already_listed_fmt, already));
            } else {
                showStep(Step.CONFIRMING);
                Ui.error(binding.getRoot(), res.error(getString(R.string.unpaid_add_error_fallback)));
            }
        });
    }

    private void finishWith(String name, String regNo, @Nullable String note) {
        binding.tvDoneName.setText(name);
        binding.tvDoneRegNo.setText(regNo);
        showStep(Step.DONE);
        if (note != null) Ui.snack(binding.getRoot(), note);
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    private TextWatcher clearErrorWatcher(TextInputLayout til) {
        return new TextWatcher() {
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
        };
    }

    // ------------------------------------------------------------------ step animation

    private void showStep(Step next) {
        switch (next) {
            case ALIGNING:
                showGroup(binding.groupAligning);
                hideLoading();
                break;
            case READING:
                showLoading(getString(R.string.reading_card));
                break;
            case CONFIRMING:
                showGroup(binding.groupConfirming);
                hideLoading();
                break;
            case SAVING:
                showLoading(getString(R.string.up_saving));
                break;
            case DONE:
                showGroup(binding.groupDone);
                hideLoading();
                break;
        }
    }

    private void showGroup(View target) {
        View[] groups = {binding.groupAligning, binding.groupConfirming, binding.groupDone};
        for (View g : groups) {
            if (g == target) {
                if (g.getVisibility() != View.VISIBLE) {
                    g.setAlpha(0f);
                    g.setVisibility(View.VISIBLE);
                    g.animate().alpha(1f).setDuration(260).start();
                }
            } else if (g.getVisibility() == View.VISIBLE) {
                Ui.fadeOut(g);
            }
        }
    }

    private void showLoading(String message) {
        binding.tvLoadingMessage.setText(message);
        if (binding.loadingOverlay.getVisibility() != View.VISIBLE) {
            binding.loadingOverlay.setAlpha(0f);
            binding.loadingOverlay.setVisibility(View.VISIBLE);
            binding.loadingOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideLoading() {
        if (binding.loadingOverlay.getVisibility() == View.VISIBLE) {
            Ui.fadeOut(binding.loadingOverlay);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        unbindCamera();
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
        if (capturedBitmap != null) {
            capturedBitmap.recycle();
            capturedBitmap = null;
        }
        binding = null;
    }
}
