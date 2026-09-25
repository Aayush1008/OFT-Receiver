package com.oftreceiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.oftreceiver.R
import com.oftreceiver.databinding.FragmentScanBinding
import com.oftreceiver.viewmodel.MainViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

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
            binding.statusText.text = "Camera permission denied.\nGrant access in Settings."
        }
    }

    // ── SAF save document ──────────────────────────────────────────────

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            saveFileToUri(uri)
        } else {
            Toast.makeText(requireContext(), "Save cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply status bar insets to top bar so buttons don't hide behind it
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top + 8, v.paddingRight, v.paddingBottom)
            windowInsets
        }

        setupObservers()
        setupButtons()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor?.shutdown()
        _binding = null
    }

    // ── Camera setup ───────────────────────────────────────────────────

    private fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
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
                val camera = provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, analysis)

                hasTorch = camera.cameraInfo.hasFlashUnit()
                activity?.runOnUiThread {
                    binding.btnTorch.visibility = if (hasTorch) View.VISIBLE else View.GONE
                }

                viewModel.torchEnabled.observe(viewLifecycleOwner) { enabled ->
                    camera.cameraControl.enableTorch(enabled)
                    binding.btnTorch.setIconResource(
                        if (enabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                    )
                }

            } catch (e: Exception) {
                binding.statusText.text = "Camera failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── UI wiring ──────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.statusText.text = state.statusMessage

            // Session info chips
            if (state.sessionId.isNotEmpty()) {
                binding.sessionInfoRow.visibility = View.VISIBLE
                binding.fileNameChip.text = state.filename
                binding.fileSizeChip.text = formatBytes(state.totalBytes)
            } else {
                binding.sessionInfoRow.visibility = View.GONE
            }

            // Progress
            if (state.totalChunks > 0) {
                binding.progressGroup.visibility = View.VISIBLE
                binding.progressBar.max = state.totalChunks
                binding.progressBar.progress = state.receivedChunks
                binding.progressText.text = "${state.receivedChunks}/${state.totalChunks} chunks"
                val pct = if (state.totalChunks > 0) (state.receivedChunks * 100 / state.totalChunks) else 0
                binding.progressPercent.text = "$pct%"
            } else {
                binding.progressGroup.visibility = View.GONE
            }

            // Rejection
            if (state.lastRejection != null) {
                binding.rejectionText.visibility = View.VISIBLE
                binding.rejectionText.text = state.lastRejection
            } else {
                binding.rejectionText.visibility = View.GONE
            }

            // SHA-256
            if (state.sha256 != null) {
                binding.shaText.visibility = View.VISIBLE
                binding.shaText.text = "SHA-256 verified\n${state.sha256}"
            } else {
                binding.shaText.visibility = View.GONE
            }

            // Save button
            binding.btnSave.visibility = when (state.status) {
                MainViewModel.Status.VERIFIED -> View.VISIBLE
                else -> View.GONE
            }

            // Scan overlay color hint
            val overlayAlpha = when (state.status) {
                MainViewModel.Status.VERIFIED, MainViewModel.Status.SAVED -> 0.2f
                MainViewModel.Status.RECEIVING -> 0.8f
                else -> 0.6f
            }
            binding.scanOverlay.animate().alpha(overlayAlpha).setDuration(300).start()

            // Hide scan hint when receiving
            binding.scanHint.visibility = when (state.status) {
                MainViewModel.Status.WAITING_FOR_MANIFEST -> View.VISIBLE
                else -> View.GONE
            }

            // Saved confirmation
            if (state.status == MainViewModel.Status.SAVED) {
                binding.savedConfirmation.visibility = View.VISIBLE
                binding.savedConfirmation.text = "File saved successfully"
            } else {
                binding.savedConfirmation.visibility = View.GONE
            }
        }
    }

    private fun setupButtons() {
        binding.btnReset.setOnClickListener {
            viewModel.reset()
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
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
            val ctx = context ?: return@Thread
            val outputStream = ctx.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                activity?.runOnUiThread {
                    Toast.makeText(ctx, "Could not open output stream", Toast.LENGTH_LONG).show()
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
