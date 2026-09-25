package com.oftreceiver

import android.app.Application
import com.google.android.material.color.DynamicColors

class OftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic colors from the user's wallpaper (Pixel 8)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
