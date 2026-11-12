package com.ashrafnaji.store.neta

object DeviceVin {
    // NETA U exposes the VIN under ofilm.*, NETA V (Hozon "hazens") under vendor.hazens.*.
    private val VIN_PROPERTIES = listOf("vendor.hazens.vehicle.info.vin", "ofilm.product.vin.serial")

    fun read(): String? {
        for (property in VIN_PROPERTIES) {
            val raw = readSystemProperty(property) ?: continue
            val vin = NetaUnlockPassword.normalizeVin(raw)
            if (NetaUnlockPassword.isPlausibleVin(vin)) return vin
        }
        return null
    }

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
