package com.oftreceiver.protocol

import java.security.MessageDigest

/**
 * Manages the state of a single file transfer session.
 * Stores chunks, tracks progress, validates completion.
 */
class TransferSession(val manifest: OftProtocol.ParseResult.Manifest) {

    /** Chunk storage — null slots are not yet received. */
    val chunks: Array<ByteArray?> = arrayOfNulls(manifest.totalChunks)

    /** Number of chunks successfully received. */
    var receivedCount: Int = 0
        private set

    /** Total bytes received so far. */
    var bytesReceived: Long = 0L
        private set

    /** Last rejection reason, if any. */
    var lastRejection: String? = null
        private set

    /** Whether the transfer is complete and verified. */
    var isComplete: Boolean = false
        private set

    /** The verified SHA-256 hex string (set on successful completion). */
    var verifiedSha256: String? = null
        private set

    /**
     * Attempt to store a data frame's payload.
     * @return true if the chunk was new and accepted, false otherwise.
     */
    fun acceptChunk(index: Int, payload: ByteArray): Boolean {
        // Range check
        if (index < 0 || index >= manifest.totalChunks) {
            lastRejection = "Chunk index $index out of range [0, ${manifest.totalChunks})"
            return false
        }

        val existing = chunks[index]
        if (existing != null) {
            // Duplicate: if byte-identical, silently ignore
            if (existing.contentEquals(payload)) {
                return false
            }
            // Conflicting duplicate: reject and keep first
            lastRejection = "Conflicting duplicate for chunk $index — keeping original"
            return false
        }

        // Store
        chunks[index] = payload.copyOf()
        receivedCount++
        bytesReceived += payload.size
        return true
    }

    /**
     * Check whether all chunks have been received and validate the transfer.
     * @return null on success, or an error message on failure.
     */
    fun tryFinalize(): String? {
        // All slots filled?
        if (receivedCount < manifest.totalChunks) {
            return "Not all chunks received: $receivedCount / ${manifest.totalChunks}"
        }

        // Uniform chunk length check: all except last must have the same length
        if (manifest.totalChunks > 1) {
            val standardSize = chunks[0]!!.size
            for (i in 0 until manifest.totalChunks - 1) {
                if (chunks[i]!!.size != standardSize) {
                    return "Chunk $i has size ${chunks[i]!!.size}, expected $standardSize"
                }
            }
        }

        // Total byte count check
        var totalBytes = 0L
        for (chunk in chunks) {
            totalBytes += chunk!!.size
        }
        if (totalBytes != manifest.fileSize) {
            return "Total bytes $totalBytes != expected ${manifest.fileSize}"
        }

        // SHA-256 incremental verification
        val digest = MessageDigest.getInstance("SHA-256")
        for (chunk in chunks) {
            digest.update(chunk!!)
        }
        val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (computedHash != manifest.fileSha256Hex) {
            return "SHA-256 mismatch: computed $computedHash, expected ${manifest.fileSha256Hex}"
        }

        isComplete = true
        verifiedSha256 = computedHash
        return null
    }

    /**
     * Get the concatenated file bytes. Only call after successful tryFinalize().
     */
    fun getFileBytes(): ByteArray {
        check(isComplete) { "Transfer not yet verified" }
        val result = ByteArray(manifest.fileSize.toInt())
        var offset = 0
        for (chunk in chunks) {
            val data = chunk!!
            System.arraycopy(data, 0, result, offset, data.size)
            offset += data.size
        }
        return result
    }

    /** Progress as a fraction 0.0 .. 1.0 */
    val progress: Float
        get() = if (manifest.totalChunks == 0) 0f
                else receivedCount.toFloat() / manifest.totalChunks
}
