package com.example.wearableai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wearableai.databinding.ActivityMainBinding
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Step 1: Android system permissions
    private val androidPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startWearableRegistration()
        } else {
            Toast.makeText(this, "Permissions required for wearable access", Toast.LENGTH_LONG).show()
        }
    }

    // Step 3: Wearable microphone permission via Meta AI app
    private val wearableMicLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val granted = result.getOrNull() is PermissionStatus.Granted
        android.util.Log.d("MainActivity", "Wearable mic permission result: $result, granted=$granted")
        if (granted) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onWearablePermissionDenied()
            Toast.makeText(this, "Microphone permission required for glasses", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener { viewModel.connectGlasses() }
        binding.btnTranscribe.setOnClickListener { viewModel.toggleAgent() }
        binding.cbLocalInference.setOnCheckedChangeListener { _, checked ->
            viewModel.setForceLocal(checked)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.status.collect { binding.tvStatus.text = it } }
                launch {
                    viewModel.transcript.collect {
                        binding.tvTranscript.text = it
                        binding.transcriptScroll.post {
                            binding.transcriptScroll.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
                launch { viewModel.connectEnabled.collect { binding.btnConnect.isEnabled = it } }
                launch { viewModel.agentEnabled.collect { binding.btnTranscribe.isEnabled = it } }
                launch { viewModel.agentLabel.collect { binding.btnTranscribe.text = it } }
                launch { viewModel.forceLocal.collect { binding.cbLocalInference.isChecked = it } }
            }
        }

        requestAndroidPermissions()
    }

    private fun requestAndroidPermissions() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            startWearableRegistration()
        } else {
            androidPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Step 2: Register app with Meta AI, then request wearable mic permission
    private fun startWearableRegistration() {
        viewModel.onStatus("Registering with Meta AI app…")
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                android.util.Log.d("MainActivity", "RegistrationState: $state")
                when (state) {
                    is RegistrationState.Registered -> {
                        // Already registered — go straight to permission check
                        wearableMicLauncher.launch(Permission.MICROPHONE)
                        return@collect
                    }
                    is RegistrationState.Available -> {
                        // Not registered yet — start registration flow
                        Wearables.startRegistration(this@MainActivity)
                    }
                    is RegistrationState.Unavailable -> {
                        viewModel.onStatus("Meta AI app not installed or unavailable.")
                    }
                    else -> { /* Registering / Unregistering — wait */ }
                }
            }
        }
    }
}
