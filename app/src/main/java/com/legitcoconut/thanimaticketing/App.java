package com.legitcoconut.thanimaticketing;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.ImageLoader;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        Api.init(this);
        ImageLoader.init(this);
    }
}
