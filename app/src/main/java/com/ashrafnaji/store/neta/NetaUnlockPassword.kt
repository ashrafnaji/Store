package com.ashrafnaji.store.neta

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Computes the NETA U daily ADB-install password from the vehicle's VIN.
 *
 * Some NETA U units prompt for a password before an APK install is accepted (the same
 * value exposed to ADB via the `dev_adb_auth_passwd` global setting). It is derived from
 * the 17-character VIN plus today's date: AES-128/ECB/PKCS7 encrypt `VIN + yyyyMMdd` with a
 * fixed key, base64-encode the ciphertext, and keep only its last 9 characters.
 *
 * This mirrors `Brands/Neta_AllModels/NETA_U/document/password.ps1`, which was previously
 * the only way to compute it (over ADB, from a PC). Keeping both in sync matters if the
 * algorithm ever changes.
 */
object NetaUnlockPassword {

    // Same fixed key used by the PC-side script. Not a secret worth protecting further: it's
    // embedded in the (closed-source) unit firmware itself, which is what this reproduces.
    private const val AES_KEY = "ASDFGHJKLASDFGHI"
    private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)

    private val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    /** True if [vin] (after trimming/uppercasing) is a plausible 17-character VIN. */
    fun isPlausibleVin(vin: String): Boolean = VIN_PATTERN.matches(normalizeVin(vin))

    fun normalizeVin(vin: String): String = vin.trim().uppercase(Locale.US)

    /**
     * Returns today's (or [date]'s) install password for [vin], or null if [vin] isn't a
     * plausible 17-character VIN.
     */
    fun compute(vin: String, date: Date = Date()): String? {
        val normalizedVin = normalizeVin(vin)
        if (!isPlausibleVin(normalizedVin)) return null

        val plainText = normalizedVin + DATE_FORMAT.format(date)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoded = android.util.Base64.encodeToString(cipherBytes, android.util.Base64.NO_WRAP)
        return encoded.takeLast(9)
    }
}
