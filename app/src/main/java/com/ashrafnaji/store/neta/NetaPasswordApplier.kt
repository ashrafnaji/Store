package com.ashrafnaji.store.neta

import android.content.Context
import android.provider.Settings
import java.util.Date

object NetaPasswordApplier {
    private const val SETTING_KEY = "dev_adb_auth_passwd"

    enum class Result { APPLIED, NO_VIN, NO_PERMISSION, FAILED }

    // Writing Settings.Global needs WRITE_SECURE_SETTINGS, which only `pm grant` over ADB (or
    // a privileged install) can give this app. Without it we report NO_PERMISSION and the UI
    // falls back to showing the password to type by hand.
    fun applyForToday(context: Context, vin: String? = DeviceVin.read()): Result {
        val password = vin?.let { NetaUnlockPassword.compute(it, Date()) } ?: return Result.NO_VIN
        return try {
            val ok = Settings.Global.putString(context.contentResolver, SETTING_KEY, password)
            if (ok) Result.APPLIED else Result.FAILED
        } catch (_: SecurityException) {
            Result.NO_PERMISSION
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
