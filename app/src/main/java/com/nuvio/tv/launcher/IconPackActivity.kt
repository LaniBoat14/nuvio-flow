package com.nuvio.tv.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Lets launchers list the app as an icon pack. Opened directly, it starts the app as its home screen entry would. */
class IconPackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }
}
