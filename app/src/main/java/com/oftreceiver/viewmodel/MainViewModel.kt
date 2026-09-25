package com.oftreceiver.viewmodel

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.oftreceiver.protocol.OftProtocol
import com.oftreceiver.protocol.TransferSession
import java.io.OutputStream

/**
 * ViewModel that survives rotation. Holds the transfer session state
 * and processes incoming QR frame payloads.
 */
class MainViewModel : ViewModel() {

    // ── Observable state ───────────────────────────────────────────────

    data class TransferState(
        val status: Status = Status.WAITING_FOR_MANIFEST,
        val filename: String = "",
        val sessionId: String = "",
        val receivedChunks: Int = 0,
        val totalChunks: Int = 0,
        val bytesReceived: Long = 0,
        val totalBytes: Long = 0,
        val progress: Float = 0f,
        val lastRejection: String? = null,
        val sha256: String? = null,
        val savedUri: Uri? = null,
        val statusMessage: String = "Point camera at the QR code sequence"
    )

    enum class Status {
        WAITING_FOR_MANIFEST,
        RECEIVING,
        VERIFYING,
        VERIFIED,
        SAVING,
        SAVED,
        ERROR
    }

    private val _state = MutableLiveData(TransferState())
    val state: LiveData<TransferState> = _state

    private var session: TransferSession? = null

    // ── Torch state ────────────────────────────────────────────────────

    private val _torchEnabled = MutableLiveData(false)
    val torchEnabled: LiveData<Boolean> = _torchEnabled

    fun toggleTorch() {
        _torchEnabled.value = !(_torchEnabled.value ?: false)
    }

    // ── QR processing ──────────────────────────────────────────────────

    /**
     * Called from the barcode analyzer on the camera thread.
     * Must be synchronized to avoid concurrent modification.
     */
    @Synchronized
    fun onQrPayload(raw: String) {
        val currentState = _state.value ?: return

        // Don't process if we're already verified/saving/saved
        if (currentState.status in listOf(Status.VERIFIED, Status.SAVING, Status.SAVED)) return

        val result = OftProtocol.parseFrame(raw)

        when (result) {
            is OftProtocol.ParseResult.Ignored -> {
                // Non-OFT1 QR — show brief status
                postState(currentState.copy(
                    lastRejection = "Non-protocol QR code (ignored)"
                ))
            }

            is OftProtocol.ParseResult.Error -> {
                postState(currentState.copy(lastRejection = result.reason))
            }

            is OftProtocol.ParseResult.Manifest -> handleManifest(result, currentState)
            is OftProtocol.ParseResult.DataFrame -> handleDataFrame(result, currentState)
        }
    }

    private fun handleManifest(manifest: OftProtocol.ParseResult.Manifest, currentState: TransferState) {
        val existing = session
        if (existing != null && existing.manifest.sessionId == manifest.sessionId) {
            // Same session manifest repeated — ignore
            return
        }

        if (existing != null && existing.manifest.sessionId != manifest.sessionId) {
            // Different session — discard old and start new
            session = TransferSession(manifest)
            postState(TransferState(
                status = Status.RECEIVING,
                filename = manifest.filename,
                sessionId = manifest.sessionId,
                totalChunks = manifest.totalChunks,
                totalBytes = manifest.fileSize,
                statusMessage = "New session started (previous discarded): ${manifest.filename}",
                lastRejection = "Previous session ${existing.manifest.sessionId} discarded"
            ))
            return
        }

        // No existing session — start fresh
        session = TransferSession(manifest)
        postState(TransferState(
            status = Status.RECEIVING,
            filename = manifest.filename,
            sessionId = manifest.sessionId,
            totalChunks = manifest.totalChunks,
            totalBytes = manifest.fileSize,
            statusMessage = "Receiving: ${manifest.filename}"
        ))
    }

    private fun handleDataFrame(frame: OftProtocol.ParseResult.DataFrame, currentState: TransferState) {
        val s = session ?: run {
            // No manifest yet — ignore data frames
            postState(currentState.copy(
                lastRejection = "Data frame ignored — no manifest received yet"
            ))
            return
        }

        // Session ID must match
        if (frame.sessionId != s.manifest.sessionId) {
            postState(currentState.copy(
                lastRejection = "Data frame session mismatch (ignored)"
            ))
            return
        }

        // Index range check
        if (frame.chunkIndex < 0 || frame.chunkIndex >= s.manifest.totalChunks) {
            postState(currentState.copy(
                lastRejection = "Chunk index ${frame.chunkIndex} out of range"
            ))
            return
        }

        val accepted = s.acceptChunk(frame.chunkIndex, frame.payloadBytes)

        val newState = currentState.copy(
            status = Status.RECEIVING,
            receivedChunks = s.receivedCount,
            bytesReceived = s.bytesReceived,
            progress = s.progress,
            lastRejection = s.lastRejection,
            statusMessage = if (accepted) "Chunk ${frame.chunkIndex} received"
                           else currentState.statusMessage
        )
        postState(newState)

        // Check for completion
        if (s.receivedCount == s.manifest.totalChunks) {
            postState(newState.copy(status = Status.VERIFYING, statusMessage = "Verifying SHA-256..."))
            val error = s.tryFinalize()
            if (error != null) {
                postState(newState.copy(
                    status = Status.ERROR,
                    statusMessage = "Verification failed: $error",
                    lastRejection = error
                ))
            } else {
                postState(newState.copy(
                    status = Status.VERIFIED,
                    sha256 = s.verifiedSha256,
                    progress = 1f,
                    receivedChunks = s.receivedCount,
                    bytesReceived = s.bytesReceived,
                    statusMessage = "File verified! Ready to save."
                ))
            }
        }
    }

    // ── Save to SAF ────────────────────────────────────────────────────

    /** The sanitized filename for ACTION_CREATE_DOCUMENT. */
    fun getSuggestedFilename(): String = session?.manifest?.filename ?: "received-file.bin"

    /**
     * Write the verified file to the given OutputStream.
     * Call from a coroutine or background thread.
     */
    fun saveToStream(outputStream: OutputStream): Boolean {
        val s = session ?: return false
        if (!s.isComplete) return false

        postState(_state.value!!.copy(status = Status.SAVING, statusMessage = "Saving file..."))

        return try {
            outputStream.use { out ->
                for (chunk in s.chunks) {
                    out.write(chunk!!)
                }
                out.flush()
            }
            true
        } catch (e: Exception) {
            postState(_state.value!!.copy(
                status = Status.VERIFIED,
                statusMessage = "Save failed: ${e.message}. Tap Save to retry."
            ))
            false
        }
    }

    fun onSaveComplete(uri: Uri) {
        val current = _state.value ?: return
        postState(current.copy(
            status = Status.SAVED,
            savedUri = uri,
            statusMessage = "File saved successfully!"
        ))
    }

    // ── Reset ──────────────────────────────────────────────────────────

    fun reset() {
        session = null
        _state.postValue(TransferState())
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun postState(state: TransferState) {
        _state.postValue(state)
    }
}
