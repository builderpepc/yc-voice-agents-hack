package com.example.wearableai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import com.example.wearableai.databinding.ActivityMainBinding
import com.example.wearableai.shared.FORM_FIELD_DEFINITIONS
import com.example.wearableai.shared.FormField
import com.example.wearableai.shared.FormSection
import com.example.wearableai.shared.NoteCategory
import com.example.wearableai.ui.FloorPlanScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val androidPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startWearableRegistration()
        } else {
            Toast.makeText(this, "Permissions required for wearable access", Toast.LENGTH_LONG).show()
        }
    }

    private val wearableMicLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val granted = result.getOrNull() is PermissionStatus.Granted
        if (granted) wearableCameraLauncher.launch(Permission.CAMERA)
        else {
            viewModel.onWearablePermissionDenied()
            Toast.makeText(this, "Microphone permission required for glasses", Toast.LENGTH_LONG).show()
        }
    }

    private val wearableCameraLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val granted = result.getOrNull() is PermissionStatus.Granted
        if (granted) viewModel.onPermissionsGranted()
        else {
            // Mic is granted; proceed without camera but warn.
            viewModel.onPermissionsGranted()
            Toast.makeText(this, "Camera permission denied — photo capture disabled", Toast.LENGTH_LONG).show()
        }
    }

    private val floorPlanPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.loadFloorPlan(it) } }

    private val docsPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.ingestDocs(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInspection.setOnClickListener { viewModel.toggleInspection() }
        binding.btnLoadFloorPlan.setOnClickListener {
            floorPlanPicker.launch(arrayOf("image/png", "image/jpeg", "image/*"))
        }
        binding.btnLoadDocs.setOnClickListener {
            docsPicker.launch(arrayOf("text/plain", "text/markdown", "text/*"))
        }
        binding.btnSummary.setOnClickListener { viewModel.speakSummary() }
        binding.btnCapture.setOnClickListener { viewModel.captureNow() }
        binding.btnExportPdf.setOnClickListener { viewModel.exportPdf() }
        binding.cbLocalInference.setOnCheckedChangeListener { _, checked ->
            viewModel.setForceLocal(checked)
        }

        binding.floorPlanCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val path by viewModel.floorPlanPath.collectAsState()
                val pins by viewModel.pins.collectAsState()
                FloorPlanScreen(
                    floorPlanPath = path,
                    pins = pins,
                    onAddPinAtNorm = { x, y -> viewModel.addManualPin(x, y) },
                    onPinTap = { /* no-op for now; could show details sheet */ },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.status.collect { binding.tvStatus.text = it } }
                launch {
                    viewModel.formFields.collect { fieldMap ->
                        binding.tvNotes.text = renderForm(fieldMap)
                        binding.notesScroll.post {
                            binding.notesScroll.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
                launch { viewModel.inspectionEnabled.collect { binding.btnInspection.isEnabled = it } }
                launch { viewModel.inspectionLabel.collect { binding.btnInspection.text = it } }
                launch { viewModel.forceLocal.collect { binding.cbLocalInference.isChecked = it } }
                launch {
                    viewModel.docsIndexedChunks.collect { count ->
                        binding.btnLoadDocs.contentDescription =
                            if (count > 0) "Load documents ($count chunks indexed)" else "Load documents"
                    }
                }
                launch { viewModel.pdfExported.collect { openPdf(it) } }
            }
        }

        requestAndroidPermissions()
    }

    private fun openPdf(file: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Can't share PDF: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Try to open for viewing first
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Pre-Incident Plan Report")
            putExtra(Intent.EXTRA_TEXT, "Attached is the completed FM Global Pre-Incident Plan.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Show chooser with both view and share options
        val chooser = Intent.createChooser(shareIntent, "View or Share Inspection Report")
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(viewIntent))
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No PDF viewer or sharing apps available", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderForm(fieldMap: Map<String, FormField>): String {
        val filled = fieldMap.values.count { it.value.isNotBlank() }
        if (filled == 0) return ""
        val sb = StringBuilder()
        var currentSection: FormSection? = null
        for (def in FORM_FIELD_DEFINITIONS) {
            val field = fieldMap[def.id] ?: continue
            if (field.value.isBlank()) continue
            if (field.section != currentSection) {
                currentSection = field.section
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("## ").append(currentSection.heading).append('\n')
            }
            sb.append("• ").append(field.label).append(": ").append(field.value)
            if (field.photoPath != null) sb.append(" [📷]")
            sb.append('\n')
        }
        sb.append("\n($filled/${fieldMap.size} fields filled)")
        return sb.toString().trimEnd()
    }

    private fun requestAndroidPermissions() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startWearableRegistration()
        else androidPermissionLauncher.launch(needed.toTypedArray())
    }

    private fun startWearableRegistration() {
        viewModel.onStatus("Registering with Meta AI app…")
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                when (state) {
                    is RegistrationState.Registered -> {
                        wearableMicLauncher.launch(Permission.MICROPHONE)
                        return@collect
                    }
                    is RegistrationState.Available -> Wearables.startRegistration(this@MainActivity)
                    is RegistrationState.Unavailable -> {
                        // No Meta glasses — fall back to phone mic mode
                        viewModel.onStatus("Phone mic mode — no glasses detected.")
                        viewModel.onPermissionsGranted()
                    }
                    else -> { /* wait */ }
                }
            }
        }
    }
}
