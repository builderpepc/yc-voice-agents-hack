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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.onPermissionsGranted()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener { viewModel.connectMic() }
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

        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
