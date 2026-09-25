package com.oftreceiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.oftreceiver.R
import com.oftreceiver.databinding.ActivityMainBinding
import com.oftreceiver.viewmodel.MainViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var hasTorch = false

    // ── Permission request ─────────────────────────────────────────────

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.statusText.text = "Camera permission denied.\nPlease grant camera access in Settings to scan QR codes."
            binding.btnReset.visibility = View.VISIBLE
        }
    }

    // ── SAF save document ──────────────────────────────────────────────

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            saveFileToUri(uri)
        } else {
            // User cancelled — remain in VERIFIED state so they can retry
            Toast.makeText(this, "Save cancelled — file is still in memory", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }

    // ── Camera setup ───────────────────────────────────────────────────

    private fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor!!, QrAnalyzer { raw ->
                        viewModel.onQrPayload(raw)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, cameraSelector, preview, analysis)

                // Check torch availability
                hasTorch = camera.cameraInfo.hasFlashUnit()
                runOnUiThread {
                    binding.btnTorch.visibility = if (hasTorch) View.VISIBLE else View.GONE
                }

                // Observe torch state
                viewModel.torchEnabled.observe(this) { enabled ->
                    camera.cameraControl.enableTorch(enabled)
                    binding.btnTorch.text = if (enabled) "🔦 Torch OFF" else "🔦 Torch ON"
                }

            } catch (e: Exception) {
                binding.statusText.text = "Camera initialization failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── UI wiring ──────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.state.observe(this) { state ->
            // Status text
            binding.statusText.text = state.statusMessage

            // Session info
            if (state.sessionId.isNotEmpty()) {
                binding.sessionInfo.visibility = View.VISIBLE
                binding.sessionInfo.text = "File: ${state.filename}\nSession: ${state.sessionId}"
            } else {
                binding.sessionInfo.visibility = View.GONE
            }

            // Progress
            if (state.totalChunks > 0) {
                binding.progressGroup.visibility = View.VISIBLE
                binding.progressBar.max = state.totalChunks
                binding.progressBar.progress = state.receivedChunks
                binding.progressText.text = "${state.receivedChunks} / ${state.totalChunks} chunks  •  " +
                        "${formatBytes(state.bytesReceived)} / ${formatBytes(state.totalBytes)}"
            } else {
                binding.progressGroup.visibility = View.GONE
            }

            // Last rejection
            if (state.lastRejection != null) {
                binding.rejectionText.visibility = View.VISIBLE
                binding.rejectionText.text = "⚠ ${state.lastRejection}"
            } else {
                binding.rejectionText.visibility = View.GONE
            }

            // SHA-256 display
            if (state.sha256 != null) {
                binding.shaText.visibility = View.VISIBLE
                binding.shaText.text = "SHA-256:\n${state.sha256}"
            } else {
                binding.shaText.visibility = View.GONE
            }

            // Save button
            binding.btnSave.visibility = when (state.status) {
                MainViewModel.Status.VERIFIED -> View.VISIBLE
                else -> View.GONE
            }

            // Scan overlay tint
            binding.scanOverlay.alpha = when (state.status) {
                MainViewModel.Status.VERIFIED, MainViewModel.Status.SAVED -> 0.1f
                else -> 0.3f
            }

            // Saved confirmation
            if (state.status == MainViewModel.Status.SAVED) {
                binding.savedConfirmation.visibility = View.VISIBLE
                binding.savedConfirmation.text = "✓ File saved successfully"
            } else {
                binding.savedConfirmation.visibility = View.GONE
            }
        }
    }

    private fun setupButtons() {
        binding.btnReset.setOnClickListener {
            viewModel.reset()
            // Re-check camera permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnTorch.setOnClickListener {
            viewModel.toggleTorch()
        }

        binding.btnSave.setOnClickListener {
            saveDocumentLauncher.launch(viewModel.getSuggestedFilename())
        }
    }

    // ── File saving ────────────────────────────────────────────────────

    private fun saveFileToUri(uri: Uri) {
        Thread {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open output stream", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            val success = viewModel.saveToStream(outputStream)
            if (success) {
                viewModel.onSaveComplete(uri)
            }
        }.start()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
