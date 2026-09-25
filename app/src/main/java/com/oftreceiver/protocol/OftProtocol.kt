package com.oftreceiver.protocol

import java.util.zip.CRC32

/**
 * OFT1 wire protocol parser and validator.
 *
 * All QR payloads are printable ASCII with fields separated by '|'.
 * First field is always "OFT1".
 *
 * Manifest frame (7 fields):
 *   OFT1|M|<session_id>|<total_chunks>|<file_size>|<file_sha256_hex>|<filename_base64url_no_pad>
 *
 * Data frame (6 fields):
 *   OFT1|D|<session_id>|<chunk_index>|<payload_crc32_hex>|<payload_base64url_no_pad>
 */
object OftProtocol {

    const val MAX_FILE_SIZE = 10 * 1024 * 1024  // 10 MiB

    private val HEX_PATTERN = Regex("^[0-9a-f]+$")
    private val DECIMAL_PATTERN = Regex("^[0-9]+$")

    // ── Base64url helpers ──────────────────────────────────────────────

    /**
     * Restore standard Base64 padding to a Base64url-encoded string
     * (no-padding variant). Appends '=' until length is divisible by 4.
     */
    fun restoreBase64Padding(input: String): String {
        val remainder = input.length % 4
        return if (remainder == 0) input
        else input + "=".repeat(4 - remainder)
    }

    /**
     * Decode a Base64url string (URL-safe alphabet, padding stripped).
     * Converts URL-safe characters to standard Base64, restores padding,
     * then decodes.
     */
    fun decodeBase64url(encoded: String): ByteArray {
        val standardBase64 = restoreBase64Padding(
            encoded.replace('-', '+').replace('_', '/')
        )
        return android.util.Base64.decode(standardBase64, android.util.Base64.DEFAULT)
    }

    // For unit tests that run on JVM without android.util.Base64
    fun decodeBase64urlJvm(encoded: String): ByteArray {
        val standardBase64 = restoreBase64Padding(
            encoded.replace('-', '+').replace('_', '/')
        )
        return java.util.Base64.getDecoder().decode(standardBase64)
    }

    // ── CRC-32 helper ──────────────────────────────────────────────────

    /** Compute CRC-32 as 8-char lower-case hex, matching java.util.zip.CRC32. */
    fun crc32Hex(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return String.format("%08x", crc.value)
    }

    // ── Frame types ────────────────────────────────────────────────────

    sealed class ParseResult {
        data class Manifest(
            val sessionId: String,
            val totalChunks: Int,
            val fileSize: Long,
            val fileSha256Hex: String,
            val filename: String
        ) : ParseResult()

        data class DataFrame(
            val sessionId: String,
            val chunkIndex: Int,
            val payloadCrc32Hex: String,
            val payloadBytes: ByteArray
        ) : ParseResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is DataFrame) return false
                return sessionId == other.sessionId &&
                        chunkIndex == other.chunkIndex &&
                        payloadCrc32Hex == other.payloadCrc32Hex &&
                        payloadBytes.contentEquals(other.payloadBytes)
            }
            override fun hashCode(): Int {
                var result = sessionId.hashCode()
                result = 31 * result + chunkIndex
                result = 31 * result + payloadCrc32Hex.hashCode()
                result = 31 * result + payloadBytes.contentHashCode()
                return result
            }
        }

        data class Error(val reason: String) : ParseResult()
        object Ignored : ParseResult()
    }

    // ── Parsing ────────────────────────────────────────────────────────

    /**
     * Parse a raw QR payload string into a typed frame result.
     * Returns [ParseResult.Ignored] for non-OFT1 QR codes.
     *
     * @param useJvmBase64 Set true in unit tests running on plain JVM.
     */
    fun parseFrame(raw: String, useJvmBase64: Boolean = false): ParseResult {
        val fields = raw.split('|')
        if (fields.isEmpty() || fields[0] != "OFT1") return ParseResult.Ignored

        return when {
            fields.size == 7 && fields[1] == "M" -> parseManifest(fields, useJvmBase64)
            fields.size == 6 && fields[1] == "D" -> parseDataFrame(fields, useJvmBase64)
            else -> ParseResult.Error("Unrecognized OFT1 frame structure")
        }
    }

    private fun parseManifest(fields: List<String>, useJvmBase64: Boolean): ParseResult {
        val sessionId = fields[2]
        val totalChunksStr = fields[3]
        val fileSizeStr = fields[4]
        val sha256Hex = fields[5]
        val filenameB64 = fields[6]

        // Validate session_id: exactly 16 lower-case hex chars
        if (sessionId.length != 16 || !HEX_PATTERN.matches(sessionId))
            return ParseResult.Error("Invalid session_id: must be 16 lower-case hex chars")

        // Validate total_chunks: positive integer
        if (!DECIMAL_PATTERN.matches(totalChunksStr))
            return ParseResult.Error("Invalid total_chunks: not a decimal integer")
        val totalChunks = totalChunksStr.toLongOrNull()
            ?: return ParseResult.Error("total_chunks out of range")
        if (totalChunks <= 0 || totalChunks > Int.MAX_VALUE)
            return ParseResult.Error("total_chunks must be a positive integer within range")

        // Validate file_size: between 1 and 10 MiB inclusive
        if (!DECIMAL_PATTERN.matches(fileSizeStr))
            return ParseResult.Error("Invalid file_size: not a decimal integer")
        val fileSize = fileSizeStr.toLongOrNull()
            ?: return ParseResult.Error("file_size out of range")
        if (fileSize < 1 || fileSize > MAX_FILE_SIZE)
            return ParseResult.Error("file_size must be between 1 and $MAX_FILE_SIZE bytes")

        // Validate SHA-256: exactly 64 lower-case hex chars
        if (sha256Hex.length != 64 || !HEX_PATTERN.matches(sha256Hex))
            return ParseResult.Error("Invalid file_sha256_hex: must be 64 lower-case hex chars")

        // Validate session_id == first 16 chars of sha256
        if (sessionId != sha256Hex.substring(0, 16))
            return ParseResult.Error("session_id does not match first 16 chars of SHA-256")

        // Decode filename
        val filename = try {
            val bytes = if (useJvmBase64) decodeBase64urlJvm(filenameB64) else decodeBase64url(filenameB64)
            sanitizeFilename(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            "received-file.bin"
        }

        return ParseResult.Manifest(
            sessionId = sessionId,
            totalChunks = totalChunks.toInt(),
            fileSize = fileSize,
            fileSha256Hex = sha256Hex,
            filename = filename
        )
    }

    private fun parseDataFrame(fields: List<String>, useJvmBase64: Boolean): ParseResult {
        val sessionId = fields[2]
        val chunkIndexStr = fields[3]
        val crc32Hex = fields[4]
        val payloadB64 = fields[5]

        // Validate session_id
        if (sessionId.length != 16 || !HEX_PATTERN.matches(sessionId))
            return ParseResult.Error("Invalid session_id in data frame")

        // Validate chunk_index
        if (!DECIMAL_PATTERN.matches(chunkIndexStr))
            return ParseResult.Error("Invalid chunk_index: not a decimal integer")
        val chunkIndex = chunkIndexStr.toLongOrNull()
            ?: return ParseResult.Error("chunk_index out of range")
        if (chunkIndex < 0 || chunkIndex > Int.MAX_VALUE)
            return ParseResult.Error("chunk_index out of range")

        // Validate CRC field: 8 lower-case hex chars
        if (crc32Hex.length != 8 || !HEX_PATTERN.matches(crc32Hex))
            return ParseResult.Error("Invalid CRC-32 format: must be 8 lower-case hex chars")

        // Decode payload
        val payloadBytes = try {
            if (useJvmBase64) decodeBase64urlJvm(payloadB64) else decodeBase64url(payloadB64)
        } catch (e: Exception) {
            return ParseResult.Error("Failed to decode Base64url payload: ${e.message}")
        }

        // Verify CRC-32
        val computedCrc = crc32Hex(payloadBytes)
        if (computedCrc != crc32Hex)
            return ParseResult.Error("CRC-32 mismatch: expected $crc32Hex, computed $computedCrc")

        return ParseResult.DataFrame(
            sessionId = sessionId,
            chunkIndex = chunkIndex.toInt(),
            payloadCrc32Hex = crc32Hex,
            payloadBytes = payloadBytes
        )
    }

    // ── Filename sanitization ──────────────────────────────────────────

    /**
     * Remove directory separators and control characters.
     * Fall back to "received-file.bin" if result is empty.
     */
    fun sanitizeFilename(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .filter { it.code >= 0x20 }  // strip control chars
            .trim()
        return if (cleaned.isEmpty()) "received-file.bin" else cleaned
    }
}
