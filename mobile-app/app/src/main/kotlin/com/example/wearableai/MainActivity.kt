package com.example.wearableai

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.animation.LinearInterpolator
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
import androidx.compose.runtime.mutableStateOf
import com.example.wearableai.databinding.ActivityMainBinding
import com.example.wearableai.shared.NoteCategory
import com.example.wearableai.ui.FloorPlanScreen
import com.example.wearableai.ui.SessionsSheet
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val sessionsSheetVisible = mutableStateOf(false)
    private var thinkingAnimator: ValueAnimator? = null
    private var defaultStatusColor: Int = 0

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
        defaultStatusColor = binding.tvStatus.currentTextColor

        binding.btnInspection.setOnClickListener { viewModel.toggleInspection() }
        binding.btnLoadFloorPlan.setOnClickListener {
            floorPlanPicker.launch(arrayOf("image/png", "image/jpeg", "image/*"))
        }
        binding.floorPlanHeader.setOnClickListener { toggleFloorPlan() }
        binding.btnLoadDocs.setOnClickListener {
            docsPicker.launch(arrayOf("text/plain", "text/markdown", "text/*"))
        }
        binding.btnSummary.setOnClickListener { viewModel.speakSummary() }
        binding.btnCapture.setOnClickListener { viewModel.captureNow() }
        binding.btnExportPdf.setOnClickListener { viewModel.exportPdf() }
        binding.btnSessions.setOnClickListener {
            sessionsSheetVisible.value = true
            viewModel.refreshSessionList()
        }
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

        binding.sessionsSheetCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val visible by sessionsSheetVisible
                val currentId by viewModel.currentSessionId.collectAsState()
                val currentName by viewModel.currentSessionName.collectAsState()
                val sessions by viewModel.sessions.collectAsState()
                val running by viewModel.inspectionLabel.collectAsState()
                SessionsSheet(
                    visible = visible,
                    currentId = currentId,
                    currentName = currentName,
                    sessions = sessions,
                    isInspectionRunning = running == "Stop Inspection",
                    onDismiss = { sessionsSheetVisible.value = false },
                    onNewInspection = { viewModel.newInspection() },
                    onLoad = { viewModel.loadSession(it) },
                    onDelete = { viewModel.deleteSession(it) },
                    onRename = { id, name -> viewModel.renameSession(id, name) },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.status,
                        viewModel.deferredPhotos,
                    ) { msg, pending -> msg to pending.size }.collect { (msg, count) ->
                        binding.tvStatus.text = if (count > 0) "$msg  📷 $count pending" else msg
                        setThinkingPulse(msg.startsWith("Thinking"))
                    }
                }
                launch {
                    viewModel.notes.collect { list ->
                        binding.tvNotes.text = renderNotes(list)
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
                launch { viewModel.currentSessionName.collect { binding.tvSessionName.text = it } }
            }
        }

        requestAndroidPermissions()
    }

    override fun onDestroy() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        super.onDestroy()
    }

    private fun toggleFloorPlan() {
        val expanded = binding.floorPlanCompose.visibility == android.view.View.VISIBLE
        binding.floorPlanCompose.visibility =
            if (expanded) android.view.View.GONE else android.view.View.VISIBLE
        binding.ivFloorPlanChevron.setImageResource(
            if (expanded) R.drawable.ic_tabler_chevron_right else R.drawable.ic_tabler_chevron_down
        )
    }

    /** Pulse [binding.tvStatus] between two blues while the agent is thinking.
     *  One full fade cycle is 500ms (250ms each direction via REVERSE). */
    private fun setThinkingPulse(enabled: Boolean) {
        if (enabled) {
            if (thinkingAnimator?.isRunning == true) return
            thinkingAnimator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                Color.parseColor("#4287f5"),
                Color.parseColor("#075ade"),
            ).apply {
                duration = 500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { a -> binding.tvStatus.setTextColor(a.animatedValue as Int) }
                start()
            }
        } else {
            thinkingAnimator?.cancel()
            thinkingAnimator = null
            binding.tvStatus.setTextColor(defaultStatusColor)
        }
    }

    private fun openPdf(file: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Can't share PDF: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderNotes(list: List<com.example.wearableai.shared.Note>): String {
        if (list.isEmpty()) return ""
        val byCat = list.groupBy { it.category }
        val sb = StringBuilder()
        for (cat in NoteCategory.entries) {
            val items = byCat[cat] ?: continue
            sb.append("## ").append(cat.heading).append('\n')
            for (n in items) {
                sb.append("• ").append(n.markdown)
                if (n.photoPath != null) sb.append(" [📷]")
                sb.append('\n')
            }
            sb.append('\n')
        }
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
                    is RegistrationState.Unavailable ->
                        viewModel.onStatus("Meta AI app not installed or unavailable.")
                    else -> { /* wait */ }
                }
            }
        }
    }
}
