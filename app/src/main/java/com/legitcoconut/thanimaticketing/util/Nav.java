package com.legitcoconut.thanimaticketing.util;

import android.graphics.Color;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.transition.MaterialElevationScale;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.transition.MaterialSharedAxis;
import com.google.android.material.transition.MaterialContainerTransform;

import com.legitcoconut.thanimaticketing.R;

/**
 * Navigation is three moves, each with the motion that matches it.
 *   root   fade through, used when the whole session changes
 *   push   shared axis Z, forward feels like going deeper
 *   morph  container transform, the tapped card grows into the page
 */
public final class Nav {

    private static final long DURATION = 350L;

    private Nav() {
    }

    public static void root(FragmentActivity activity, Fragment fragment) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fragment.setEnterTransition(new MaterialFadeThrough().setDuration(DURATION));
        fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.nav_host, fragment)
                .commit();
    }

    public static void push(FragmentActivity activity, Fragment fragment) {
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.nav_host);
        if (current != null) {
            current.setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true).setDuration(DURATION));
            current.setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false).setDuration(DURATION));
        }
        fragment.setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true).setDuration(DURATION));
        fragment.setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false).setDuration(DURATION));

        fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.nav_host, fragment)
                .addToBackStack(null)
                .commit();
    }

    /**
     * The tapped view morphs into the new page. The incoming fragment must give its root
     * the same transition name and call {@link Ui#startTransitionAfterLayout}.
     */
    public static void morph(FragmentActivity activity, Fragment fragment, View from, String name) {
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.nav_host);

        int surface = MaterialColors.getColor(from, com.google.android.material.R.attr.colorSurface);
        fragment.setSharedElementEnterTransition(containerTransform(surface));
        fragment.setSharedElementReturnTransition(containerTransform(surface));

        if (current != null) {
            current.setExitTransition(new MaterialElevationScale(false).setDuration(DURATION));
            current.setReenterTransition(new MaterialElevationScale(true).setDuration(DURATION));
        }

        FragmentTransaction tx = fm.beginTransaction()
                .setReorderingAllowed(true)
                .addSharedElement(from, name)
                .replace(R.id.nav_host, fragment)
                .addToBackStack(null);
        tx.commit();
    }

    private static MaterialContainerTransform containerTransform(int surface) {
        MaterialContainerTransform t = new MaterialContainerTransform();
        t.setDrawingViewId(R.id.nav_host);
        t.setDuration(DURATION);
        t.setScrimColor(Color.TRANSPARENT);
        t.setAllContainerColors(surface);
        return t;
    }

    public static void back(FragmentActivity activity) {
        activity.getOnBackPressedDispatcher().onBackPressed();
    }
}
