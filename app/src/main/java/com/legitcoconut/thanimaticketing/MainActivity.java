package com.legitcoconut.thanimaticketing;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.DynamicColors;

import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.ui.EventsFragment;
import com.legitcoconut.thanimaticketing.ui.LoginFragment;
import com.legitcoconut.thanimaticketing.util.Nav;

import java.util.function.Consumer;

/**
 * The one Activity. Every screen is a Fragment inside nav_host, which is what lets the
 * event card morph into the event page.
 */
public class MainActivity extends AppCompatActivity {

    private Consumer<Boolean> pendingResult;

    // Registered as a field so it exists before onStart, as the launcher contract requires.
    private final ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Consumer<Boolean> c = pendingResult;
                pendingResult = null;
                if (c == null) return;
                boolean all = true;
                for (Boolean granted : result.values()) all &= Boolean.TRUE.equals(granted);
                c.accept(all);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Swapping off the splash theme replaces the whole theme, which throws away the
        // dynamic colour overlay the Application installed in onActivityPreCreated. Put it
        // back straight afterwards or every surface falls back to the baseline palette.
        setTheme(R.style.Theme_App);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) return;

        View splash = findViewById(R.id.splash);
        if (!Api.hasToken()) {
            splash.setVisibility(View.GONE);
            Nav.root(this, new LoginFragment());
            return;
        }
        // A stored token still needs the server to agree. Any failure means logged out.
        Api.getMe((user, error) -> {
            splash.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> splash.setVisibility(View.GONE)).start();
            if (user == null) {
                Api.logout();
                Nav.root(this, new LoginFragment());
            } else {
                Nav.root(this, new EventsFragment());
            }
        });
    }

    public boolean hasCamera() {
        return has(Manifest.permission.CAMERA);
    }

    public void requestCamera(Consumer<Boolean> result) {
        request(result, Manifest.permission.CAMERA);
    }

    public boolean has(String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Asks once, then reports whether every permission came back granted. */
    public void request(Consumer<Boolean> result, String... wanted) {
        if (wanted.length == 0 || has(wanted)) {
            result.accept(true);
            return;
        }
        pendingResult = result;
        permissions.launch(wanted);
    }
}
