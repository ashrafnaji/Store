package com.ashrafnaji.store.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

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
            Toast.makeText(context, "Uninstalling $it", Toast.LENGTH_SHORT).show()
        }
        try {
            context.startActivity(uninstallIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No system uninstaller found", Toast.LENGTH_SHORT).show()
        }
    }
}
