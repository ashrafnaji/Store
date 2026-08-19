package com.ashrafnaji.store

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ashrafnaji.store.databinding.ActivityMainBinding
import com.ashrafnaji.store.update.UpdateManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appNameText.text = getString(R.string.app_name)
        binding.appDeveloperText.text = "by ${BuildConfig.GITHUB_OWNER}"
        binding.appDescriptionText.text = "Loading..."
        binding.versionText.text = "Installed version ${BuildConfig.VERSION_NAME}"
        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }

        UpdateManager.fetchRepoDescription { description ->
            binding.appDescriptionText.text = description ?: "No description available."
        }

        requestNotificationPermissionIfNeeded()
        checkForUpdate()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkForUpdate() {
        binding.checkUpdateButton.isEnabled = false
        binding.statusText.text = "Checking for updates..."
        UpdateManager.checkAndUpdate(this, object : UpdateManager.Listener {
            override fun onStatus(message: String) {
                binding.statusText.text = message
            }

            override fun onUpToDate() {
                binding.statusText.text = "Up to date"
                binding.checkUpdateButton.text = "Installed"
                binding.checkUpdateButton.isEnabled = true
            }

            override fun onError(message: String) {
                binding.statusText.text = "Couldn't check for updates: $message"
                binding.checkUpdateButton.text = "Retry"
                binding.checkUpdateButton.isEnabled = true
            }
        })
    }
}
