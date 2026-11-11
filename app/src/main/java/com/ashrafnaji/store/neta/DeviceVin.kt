package com.ashrafnaji.store.neta

/**
 * Reads the vehicle VIN exposed by NETA U units as the system property
 * `ofilm.product.vin.serial` (the same property `password.ps1` reads over ADB with
 * `getprop`). Running inside the unit itself, the Store app can read it directly -- no PC
 * or ADB connection needed.
 */
object DeviceVin {

    private const val VIN_PROPERTY = "ofilm.product.vin.serial"

    /** Returns the unit's VIN, or null if the property is empty/unreadable from this app. */
    fun read(): String? {
        val raw = readSystemProperty(VIN_PROPERTY) ?: return null
        val vin = NetaUnlockPassword.normalizeVin(raw)
        return vin.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads a system property via the hidden `android.os.SystemProperties.get(String)`.
     * Normal apps can call this through reflection (no permission needed for most
     * `getprop`-visible properties); falls back to shelling out to `getprop` itself, which
     * is how the PC-side script reads it and works even if the reflective path is ever
     * blocked by a platform update.
     */
    private fun readSystemProperty(name: String): String? {
        reflectiveGetProp(name)?.let { if (it.isNotBlank()) return it }
        shellGetProp(name)?.let { if (it.isNotBlank()) return it }
        return null
    }

    private fun reflectiveGetProp(name: String): String? = try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        get.invoke(null, name) as? String
    } catch (_: Exception) {
        null
    }

    private fun shellGetProp(name: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output
    } catch (_: Exception) {
        null
    }
}
