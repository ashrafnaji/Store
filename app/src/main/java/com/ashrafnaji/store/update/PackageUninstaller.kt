package com.ashrafnaji.store.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ashrafnaji.store.R

object PackageUninstaller {

    fun request(context: Context, packageName: String, label: String? = null) {
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.fromParts("package", packageName, null)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        label?.let {
            Toast.makeText(
                context,
                context.getString(R.string.uninstalling_app, it),
                Toast.LENGTH_SHORT
            ).show()
        }
        try {
            context.startActivity(uninstallIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_system_uninstaller, Toast.LENGTH_SHORT).show()
        }
    }
}
