package com.ashrafnaji.store

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ashrafnaji.store.catalog.CatalogFetcher
import com.ashrafnaji.store.catalog.CatalogItem
import com.ashrafnaji.store.databinding.ActivityMainBinding
import com.ashrafnaji.store.databinding.ItemAppCardBinding
import com.ashrafnaji.store.update.UpdateManager

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

    private var isFirstResume = true

    override fun onResume() {
        super.onResume()
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
                    version = null, downloadUrl = null, downloadUrls = emptyMap(), description = null
                ))
                return@fetch
            }
            items.forEach { bindCard(it) }
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
        binding.appListContainer.addView(card.root)

        card.appNameText.text = item.name
        card.appDeveloperText.text = "by ${BuildConfig.GITHUB_OWNER}"
        card.appDescriptionText.text = item.description ?: "Loading..."

        if (item.type == "self") {
            card.versionText.text = "Installed version ${BuildConfig.VERSION_NAME}"
            UpdateManager.fetchRepoDescription { description ->
                card.appDescriptionText.text = description ?: "No description available."
            }
            card.actionButton.setOnClickListener { checkSelfUpdate(card) }
            checkSelfUpdate(card)
        } else {
            val installed = installedVersion(item.packageName)
            card.versionText.text = if (installed != null) "Installed version $installed" else "Not installed"

            val hasUpdate = installed != null && item.version != null && UpdateManager.isNewer(item.version, installed)
            when {
                installed == null -> {
                    card.statusText.text = ""
                    card.actionButton.text = "Install"
                }
                hasUpdate -> {
                    card.statusText.text = "Update available: ${item.version}"
                    card.actionButton.text = "Update"
                }
                else -> {
                    card.statusText.text = "Up to date"
                    card.actionButton.text = "Installed"
                }
            }

            card.actionButton.setOnClickListener {
                val url = item.resolveDownloadUrl()
                if (url == null) {
                    card.statusText.text = "No APK available for this device's CPU"
                    return@setOnClickListener
                }
                card.actionButton.isEnabled = false
                UpdateManager.installFromUrl(this, url, item.packageName, item.name, object : UpdateManager.Listener {
                    override fun onStatus(message: String) {
                        card.statusText.text = message
                    }

                    override fun onUpToDate() {
                        card.statusText.text = "Up to date"
                        card.actionButton.text = "Installed"
                        card.actionButton.isEnabled = true
                    }

                    override fun onError(message: String) {
                        card.statusText.text = "Failed: $message"
                        card.actionButton.text = "Retry"
                        card.actionButton.isEnabled = true
                    }
                })
            }
        }
    }

    private fun checkSelfUpdate(card: ItemAppCardBinding) {
        card.actionButton.isEnabled = false
        card.statusText.text = "Checking for updates..."
        UpdateManager.checkAndUpdate(this, object : UpdateManager.Listener {
            override fun onStatus(message: String) {
                card.statusText.text = message
            }

            override fun onUpToDate() {
                card.statusText.text = "Up to date"
                card.actionButton.text = "Installed"
                card.actionButton.isEnabled = true
            }

            override fun onError(message: String) {
                card.statusText.text = "Couldn't check for updates: $message"
                card.actionButton.text = "Retry"
                card.actionButton.isEnabled = true
            }
        })
    }
}
