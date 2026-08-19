package com.ashrafnaji.store.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Receives the result of a [PackageInstaller] session commit started by [UpdateManager]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { context.startActivity(it) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Update installed", Toast.LENGTH_SHORT).show()
            }

            else -> {
                // Most commonly STATUS_FAILURE_CONFLICT: the new APK's signing key doesn't
                // match the installed app's. Fall back to uninstall-then-reinstall.
                val apkUriString = intent.getStringExtra(UpdateManager.EXTRA_APK_URI)
                UpdateManager.handleInstallFailure(context, apkUriString)
            }
        }
    }
}
