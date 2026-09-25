package com.oftreceiver.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oftreceiver.data.AppDatabase
import com.oftreceiver.data.TransferRecord
import com.oftreceiver.protocol.OftProtocol
import com.oftreceiver.protocol.TransferSession
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * ViewModel that survives rotation. Holds the transfer session state
 * and processes incoming QR frame payloads.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

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
        val statusMessage: String = "Ready to scan"
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
    private var transferStartTime: Long = 0L

    // ── Torch state ────────────────────────────────────────────────────

    private val _torchEnabled = MutableLiveData(false)
    val torchEnabled: LiveData<Boolean> = _torchEnabled

    fun toggleTorch() {
        _torchEnabled.value = !(_torchEnabled.value ?: false)
    }

    // ── QR processing ──────────────────────────────────────────────────

    @Synchronized
    fun onQrPayload(raw: String) {
        val currentState = _state.value ?: return

        if (currentState.status in listOf(Status.VERIFIED, Status.SAVING, Status.SAVED)) return

        val result = OftProtocol.parseFrame(raw)

        when (result) {
            is OftProtocol.ParseResult.Ignored -> {
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
            return
        }

        if (existing != null && existing.manifest.sessionId != manifest.sessionId) {
            session = TransferSession(manifest)
            transferStartTime = System.currentTimeMillis()
            postState(TransferState(
                status = Status.RECEIVING,
                filename = manifest.filename,
                sessionId = manifest.sessionId,
                totalChunks = manifest.totalChunks,
                totalBytes = manifest.fileSize,
                statusMessage = "Receiving ${manifest.filename}",
                lastRejection = "Previous session discarded"
            ))
            return
        }

        session = TransferSession(manifest)
        transferStartTime = System.currentTimeMillis()
        postState(TransferState(
            status = Status.RECEIVING,
            filename = manifest.filename,
            sessionId = manifest.sessionId,
            totalChunks = manifest.totalChunks,
            totalBytes = manifest.fileSize,
            statusMessage = "Receiving ${manifest.filename}"
        ))
    }

    private fun handleDataFrame(frame: OftProtocol.ParseResult.DataFrame, currentState: TransferState) {
        val s = session ?: run {
            postState(currentState.copy(
                lastRejection = "No manifest received yet"
            ))
            return
        }

        if (frame.sessionId != s.manifest.sessionId) {
            postState(currentState.copy(
                lastRejection = "Session mismatch (ignored)"
            ))
            return
        }

        if (frame.chunkIndex < 0 || frame.chunkIndex >= s.manifest.totalChunks) {
            postState(currentState.copy(
                lastRejection = "Chunk ${frame.chunkIndex} out of range"
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
            statusMessage = if (accepted) "Receiving ${currentState.filename}"
                           else currentState.statusMessage
        )
        postState(newState)

        if (s.receivedCount == s.manifest.totalChunks) {
            postState(newState.copy(status = Status.VERIFYING, statusMessage = "Verifying SHA-256..."))
            val error = s.tryFinalize()
            if (error != null) {
                postState(newState.copy(
                    status = Status.ERROR,
                    statusMessage = "Verification failed",
                    lastRejection = error
                ))
            } else {
                postState(newState.copy(
                    status = Status.VERIFIED,
                    sha256 = s.verifiedSha256,
                    progress = 1f,
                    receivedChunks = s.receivedCount,
                    bytesReceived = s.bytesReceived,
                    statusMessage = "File verified — ready to save"
                ))
            }
        }
    }

    // ── Save to SAF ────────────────────────────────────────────────────

    fun getSuggestedFilename(): String = session?.manifest?.filename ?: "received-file.bin"

    fun saveToStream(outputStream: OutputStream): Boolean {
        val s = session ?: return false
        if (!s.isComplete) return false

        postState(_state.value!!.copy(status = Status.SAVING, statusMessage = "Saving..."))

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
                statusMessage = "Save failed. Tap to retry."
            ))
            false
        }
    }

    fun onSaveComplete(uri: Uri) {
        val current = _state.value ?: return
        val s = session ?: return
        val durationMs = System.currentTimeMillis() - transferStartTime

        // Record in history database
        viewModelScope.launch {
            try {
                val db = AppDatabase.getInstance(getApplication())
                db.transferDao().insert(
                    TransferRecord(
                        filename = s.manifest.filename,
                        fileSize = s.manifest.fileSize,
                        sha256 = s.verifiedSha256 ?: "",
                        savedUri = uri.toString(),
                        timestamp = System.currentTimeMillis(),
                        sessionId = s.manifest.sessionId,
                        totalChunks = s.manifest.totalChunks,
                        durationMs = durationMs
                    )
                )
            } catch (_: Exception) {
                // History save is best-effort
            }
        }

        postState(current.copy(
            status = Status.SAVED,
            savedUri = uri,
            statusMessage = "File saved"
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
