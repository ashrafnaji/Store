package com.ashrafnaji.store

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import com.ashrafnaji.store.catalog.CatalogFetcher
import com.ashrafnaji.store.catalog.CatalogItem
import com.ashrafnaji.store.catalog.AppIconLoader
import com.ashrafnaji.store.databinding.ActivityMainBinding
import com.ashrafnaji.store.databinding.DialogNetaPasswordBinding
import com.ashrafnaji.store.databinding.ItemAppCardBinding
import com.ashrafnaji.store.neta.DeviceVin
import com.ashrafnaji.store.neta.NetaPasswordApplier
import com.ashrafnaji.store.neta.NetaUnlockPassword
import com.ashrafnaji.store.update.PackageUninstaller
import com.ashrafnaji.store.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadCatalog() }

    // onResume fires immediately after onCreate on first launch, which would otherwise race
    // the initial loadCatalog() call and render every card twice. Only the most recent call's
    // async result is allowed to actually render.
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener { loadCatalog() }
        binding.contactButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0782561111")))
        }
        binding.languageButton.setOnClickListener { toggleLanguage() }
        binding.netaPasswordButton.setOnClickListener { showNetaPasswordDialog() }

        requestNotificationPermissionIfNeeded()

        // Below Android 10, downloading into the public Downloads folder needs this
        // dangerous permission granted at runtime; on 10+ it's a no-op.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            loadCatalog()
        }
    }

    override fun onDestroy() {
        UpdateManager.detachListeners()
        super.onDestroy()
    }

    private var isFirstResume = true

    override fun onResume() {
        super.onResume()
        NetaPasswordApplier.applyForToday(this)
        // Installs are confirmed in a system dialog outside our process (and, for self-updates,
        // this Activity gets killed and relaunched by the system). Re-check installed versions
        // whenever the user comes back so cards don't stay stuck on "Installing...". Skip the
        // very first onResume, which fires right after onCreate already triggered a load.
        if (isFirstResume) {
            isFirstResume = false
        } else {
            loadCatalog()
        }
    }

    /**
     * Switches the app's own display language regardless of the device's system locale.
     * AppCompatDelegate persists the choice (via its own prefs on API < 33, via the OS's
     * per-app language setting on 33+) and recreates the activity so every string resource
     * re-resolves from the matching values-<lang>/ folder immediately.
     */
    private fun toggleLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()
        val currentLanguage = if (!current.isEmpty) current[0]?.language else resources.configuration.locales[0].language
        val next = if (currentLanguage == "ar") "en" else "ar"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    /**
     * NETA U units sometimes prompt for a password before accepting an APK install. It's
     * derived from the unit's VIN + today's date (see [NetaUnlockPassword]) -- read the VIN
     * from this device directly and show the matching password, falling back to manual VIN
     * entry if the property can't be read from inside the app.
     */
    private fun showNetaPasswordDialog() {
        val dialogBinding = DialogNetaPasswordBinding.inflate(LayoutInflater.from(this))
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Date()
        dialogBinding.dateText.text = getString(R.string.neta_password_for_date, dateFormat.format(today))

        fun refreshPassword(vin: String) {
            val password = NetaUnlockPassword.compute(vin, today)
            dialogBinding.passwordText.text = password ?: ""
            dialogBinding.passwordText.hint =
                if (password == null && vin.isNotEmpty()) getString(R.string.neta_password_vin_invalid) else ""
            dialogBinding.applyStatusText.text = when (
                if (password != null) NetaPasswordApplier.applyForToday(this, vin) else null
            ) {
                NetaPasswordApplier.Result.APPLIED -> getString(R.string.neta_password_applied)
                NetaPasswordApplier.Result.NO_PERMISSION -> getString(R.string.neta_password_need_permission)
                else -> ""
            }
        }

        val deviceVin = DeviceVin.read()
        dialogBinding.vinInput.setText(deviceVin ?: "")
        dialogBinding.vinSourceText.setText(
            if (deviceVin != null) R.string.neta_password_vin_from_device else R.string.neta_password_vin_not_read
        )
        refreshPassword(deviceVin ?: "")

        dialogBinding.vinInput.doAfterTextChanged { text ->
            refreshPassword(text?.toString().orEmpty())
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.neta_password_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.neta_password_copy) { _, _ ->
                val password = dialogBinding.passwordText.text?.toString().orEmpty()
                if (password.isNotEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.neta_password_title), password))
                    Toast.makeText(this, R.string.neta_password_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadCatalog() {
        val generation = ++loadGeneration
        binding.appListContainer.removeAllViews()
        CatalogFetcher.fetch { items ->
            if (generation != loadGeneration) return@fetch // a newer load superseded this one

            if (items == null) {
                // Fall back to just showing this app so the store isn't empty on network error.
                bindCard(CatalogItem(
                    id = "store", name = getString(R.string.app_name),
                    packageName = BuildConfig.APPLICATION_ID, type = "self",
                    version = null, downloadUrl = null, downloadUrls = emptyMap(),
                    description = null, iconUrl = null
                ))
                return@fetch
            }
            items.forEach { item ->
                // Hide catalog entries that aren't installed and have no asset matching this
                // device's CPU -- e.g. an x86-only upload shouldn't show as installable on arm64.
                val installed = item.type != "self" && installedVersion(item.packageName) != null
                if (item.type == "self" || installed || item.resolveDownloadUrl() != null) {
                    bindCard(item)
                }
            }
        }
    }

    private fun installedVersion(packageName: String): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun bindCard(item: CatalogItem) {
        val card = ItemAppCardBinding.inflate(LayoutInflater.from(this), binding.appListContainer, false)
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
        }
        binding.appListContainer.addView(card.root, layoutParams)

        card.appNameText.text = if (item.type == "self") getString(R.string.app_name) else item.name
        card.appDeveloperText.text = getString(R.string.developer_by, getString(R.string.developer_name))
        bindAppIcon(item, card)

        if (item.type == "self") {
            card.appDescriptionText.text = getString(R.string.store_description)
            card.versionText.text = getString(R.string.installed_version, BuildConfig.VERSION_NAME)
            card.actionButton.setOnClickListener { checkSelfUpdate(card) }
            checkSelfUpdate(card)
        } else {
            // Static catalog entries have no follow-up fetch, so show the final state immediately.
            val defaultStatusColor = card.statusText.currentTextColor
            card.appDescriptionText.text = item.description ?: getString(R.string.no_description)
            val installed = installedVersion(item.packageName)
            card.versionText.text = if (installed != null) {
                getString(R.string.installed_version, installed)
            } else {
                getString(R.string.not_installed)
            }

            val hasUpdate = installed != null && item.version != null && UpdateManager.isNewer(item.version, installed)
            when {
                installed == null -> {
                    card.statusText.text = ""
                    card.actionButton.setText(R.string.install)
                }
                hasUpdate -> {
                    card.statusText.text = getString(R.string.update_available, item.version)
                    card.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
                    card.actionButton.setText(R.string.update)
                }
                else -> {
                    card.statusText.setText(R.string.up_to_date)
                    card.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                    card.actionButton.setText(R.string.installed)
                }
            }

            card.uninstallButton.visibility = if (installed != null) android.view.View.VISIBLE else android.view.View.GONE
            card.uninstallButton.setOnClickListener {
                PackageUninstaller.request(this, item.packageName, item.name)
            }

            val launchIntent = if (installed != null) packageManager.getLaunchIntentForPackage(item.packageName) else null
            card.openButton.visibility = if (launchIntent != null) android.view.View.VISIBLE else android.view.View.GONE
            card.openButton.setOnClickListener { startActivity(launchIntent) }

            card.actionButton.setOnClickListener {
                val url = item.resolveDownloadUrl()
                if (url == null) {
                    card.statusText.setText(R.string.no_apk_for_cpu)
                    card.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                    return@setOnClickListener
                }
                card.actionButton.isEnabled = false
                card.statusText.setTextColor(defaultStatusColor)
                UpdateManager.installFromUrl(this, url, item.packageName, item.name, installListener(card, R.string.failed_message))
            }

            // The card is rebuilt on refresh/resume; pick a still-running download back up.
            if (UpdateManager.attach(item.packageName, installListener(card, R.string.failed_message))) {
                card.actionButton.isEnabled = false
                card.statusText.setTextColor(defaultStatusColor)
            }
        }
    }

    private fun installListener(card: ItemAppCardBinding, errorFormat: Int) = object : UpdateManager.Listener {
        override fun onStatus(message: String) {
            card.statusText.text = message
        }

        override fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
            showDownloadProgress(card, downloadedBytes, totalBytes)
        }

        override fun onUpToDate() {
            hideDownloadProgress(card)
            card.statusText.setText(R.string.up_to_date)
            card.statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_success))
            card.actionButton.setText(R.string.installed)
            card.actionButton.isEnabled = true
        }

        override fun onError(message: String) {
            hideDownloadProgress(card)
            card.statusText.text = getString(errorFormat, message)
            card.statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
            card.actionButton.setText(R.string.retry)
            card.actionButton.isEnabled = true
        }
    }

    private fun bindAppIcon(item: CatalogItem, card: ItemAppCardBinding) {
        card.appIcon.setImageResource(R.mipmap.ic_launcher)
        if (item.type == "self") return

        try {
            card.appIcon.setImageDrawable(packageManager.getApplicationIcon(item.packageName))
            return
        } catch (_: PackageManager.NameNotFoundException) {
            // Uninstalled apps use the icon extracted and published by the admin panel.
        }

        item.iconUrl?.let { url ->
            AppIconLoader.load(url) { bitmap ->
                if (bitmap != null) card.appIcon.setImageBitmap(bitmap)
            }
        }
    }

    private fun checkSelfUpdate(card: ItemAppCardBinding) {
        card.actionButton.isEnabled = false
        card.statusText.setText(R.string.checking_updates)
        UpdateManager.checkAndUpdate(this, installListener(card, R.string.could_not_check_updates))
    }

    private fun showDownloadProgress(card: ItemAppCardBinding, downloadedBytes: Long, totalBytes: Long) {
        card.downloadProgressContainer.visibility = View.VISIBLE
        if (totalBytes > 0L) {
            val percent = ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
            card.downloadProgressBar.isIndeterminate = false
            card.downloadProgressBar.progress = percent
            card.downloadProgressText.text = getString(R.string.download_progress_percent, percent)
        } else {
            card.downloadProgressBar.isIndeterminate = true
            card.downloadProgressText.setText(R.string.download_progress_unknown)
        }
    }

    private fun hideDownloadProgress(card: ItemAppCardBinding) {
        card.downloadProgressContainer.visibility = View.GONE
        card.downloadProgressBar.isIndeterminate = false
        card.downloadProgressBar.progress = 0
    }
}
