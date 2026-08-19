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

        binding.versionText.text = "Version ${BuildConfig.VERSION_NAME}"
        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }

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
        binding.statusText.text = "Checking for updates..."
        UpdateManager.checkAndUpdate(this, object : UpdateManager.Listener {
            override fun onStatus(message: String) {
                binding.statusText.text = message
            }

            override fun onUpToDate() {
                binding.statusText.text = "You're on the latest version"
            }

            override fun onError(message: String) {
                binding.statusText.text = "Update check failed: $message"
            }
        })
    }
}
