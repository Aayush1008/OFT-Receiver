package com.oftreceiver

import com.oftreceiver.protocol.OftProtocol
import com.oftreceiver.protocol.OftProtocol.ParseResult
import com.oftreceiver.protocol.TransferSession
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Unit tests for the OFT1 protocol layer.
 * Runs on JVM (no Android framework needed) using useJvmBase64 = true.
 */
class OftProtocolTest {

    // ── Helper: build valid test data ──────────────────────────────────

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun crc32Hex(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return String.format("%08x", crc.value)
    }

    /** Encode bytes as Base64url without padding (URL-safe alphabet). */
    private fun encodeBase64url(data: ByteArray): String {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    /**
     * Build a complete valid test scenario:
     * Returns (manifest_frame, list_of_data_frames, raw_file_bytes)
     */
    private fun buildTestFrames(
        fileContent: ByteArray = "Hello, OFT world! This is a test file.".toByteArray(),
        chunkSize: Int = 16
    ): Triple<String, List<String>, ByteArray> {
        val sha = sha256Hex(fileContent)
        val sessionId = sha.substring(0, 16)
        val totalChunks = (fileContent.size + chunkSize - 1) / chunkSize
        val filenameB64 = encodeBase64url("test-file.txt".toByteArray())

        val manifest = "OFT1|M|$sessionId|$totalChunks|${fileContent.size}|$sha|$filenameB64"

        val dataFrames = mutableListOf<String>()
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, fileContent.size)
            val chunk = fileContent.copyOfRange(start, end)
            val crc = crc32Hex(chunk)
            val payload = encodeBase64url(chunk)
            dataFrames.add("OFT1|D|$sessionId|$i|$crc|$payload")
        }

        return Triple(manifest, dataFrames, fileContent)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 1. BASE64 PADDING RESTORATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `restoreBase64Padding adds no padding when length divisible by 4`() {
        assertEquals("abcd", OftProtocol.restoreBase64Padding("abcd"))
        assertEquals("abcdabcd", OftProtocol.restoreBase64Padding("abcdabcd"))
    }

    @Test
    fun `restoreBase64Padding adds one pad when length mod 4 is 3`() {
        assertEquals("abc=", OftProtocol.restoreBase64Padding("abc"))
    }

    @Test
    fun `restoreBase64Padding adds two pads when length mod 4 is 2`() {
        assertEquals("ab==", OftProtocol.restoreBase64Padding("ab"))
    }

    @Test
    fun `restoreBase64Padding adds three pads when length mod 4 is 1`() {
        assertEquals("a===", OftProtocol.restoreBase64Padding("a"))
    }

    @Test
    fun `restoreBase64Padding handles empty string`() {
        assertEquals("", OftProtocol.restoreBase64Padding(""))
    }

    @Test
    fun `decodeBase64urlJvm correctly decodes URL-safe base64 without padding`() {
        // "Hello" in base64url without padding is "SGVsbG8"
        val encoded = encodeBase64url("Hello".toByteArray())
        val decoded = OftProtocol.decodeBase64urlJvm(encoded)
        assertArrayEquals("Hello".toByteArray(), decoded)
    }

    @Test
    fun `decodeBase64urlJvm handles URL-safe characters`() {
        // Test with bytes that produce + and / in standard base64
        val data = byteArrayOf(-1, -2, -3, 0x3E, 0x3F)
        val encoded = encodeBase64url(data)
        val decoded = OftProtocol.decodeBase64urlJvm(encoded)
        assertArrayEquals(data, decoded)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. MANIFEST VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `valid manifest parses correctly`() {
        val (manifestStr, _, _) = buildTestFrames()
        val result = OftProtocol.parseFrame(manifestStr, useJvmBase64 = true)
        assertTrue("Expected Manifest, got $result", result is ParseResult.Manifest)
        val m = result as ParseResult.Manifest
        assertEquals("test-file.txt", m.filename)
        assertTrue(m.totalChunks > 0)
        assertTrue(m.fileSize > 0)
    }

    @Test
    fun `manifest with wrong field count is error`() {
        val result = OftProtocol.parseFrame("OFT1|M|abc|1|100|sha256", useJvmBase64 = true)
        // 6 fields with M type — neither 7-field manifest nor 6-field data
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun `manifest with invalid session_id rejected`() {
        val sha = sha256Hex("test".toByteArray())
        val badSessionId = "ZZZZ" + sha.substring(4, 16) // uppercase = invalid
        val frame = "OFT1|M|$badSessionId|1|4|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).reason.contains("session_id"))
    }

    @Test
    fun `manifest with session_id not matching sha256 prefix rejected`() {
        val sha = sha256Hex("test".toByteArray())
        val wrongSession = "0000000000000000"  // valid hex but won't match sha
        val frame = "OFT1|M|$wrongSession|1|4|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).reason.contains("does not match"))
    }

    @Test
    fun `manifest with zero total_chunks rejected`() {
        val sha = sha256Hex("test".toByteArray())
        val sid = sha.substring(0, 16)
        val frame = "OFT1|M|$sid|0|4|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).reason.contains("positive"))
    }

    @Test
    fun `manifest with file_size exceeding 10MiB rejected`() {
        val sha = sha256Hex("test".toByteArray())
        val sid = sha.substring(0, 16)
        val tooBig = (10 * 1024 * 1024 + 1).toString()
        val frame = "OFT1|M|$sid|1|$tooBig|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).reason.contains("file_size"))
    }

    @Test
    fun `manifest with file_size zero rejected`() {
        val sha = sha256Hex("test".toByteArray())
        val sid = sha.substring(0, 16)
        val frame = "OFT1|M|$sid|1|0|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun `manifest with invalid sha256 length rejected`() {
        val shortSha = "abcdef0123456789"  // only 16 chars, not 64
        val frame = "OFT1|M|$shortSha|1|4|$shortSha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun `manifest filename with directory separators is sanitized`() {
        val sha = sha256Hex("x".toByteArray())
        val sid = sha.substring(0, 16)
        val badName = encodeBase64url("../../etc/passwd".toByteArray())
        val frame = "OFT1|M|$sid|1|1|$sha|$badName"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Manifest)
        val m = result as ParseResult.Manifest
        assertFalse(m.filename.contains("/"))
        assertFalse(m.filename.contains("\\"))
    }

    @Test
    fun `manifest empty filename falls back to received-file bin`() {
        val sha = sha256Hex("x".toByteArray())
        val sid = sha.substring(0, 16)
        val emptyName = encodeBase64url("".toByteArray())
        val frame = "OFT1|M|$sid|1|1|$sha|$emptyName"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Manifest)
        assertEquals("received-file.bin", (result as ParseResult.Manifest).filename)
    }

    @Test
    fun `non-OFT1 QR code returns Ignored`() {
        val result = OftProtocol.parseFrame("https://example.com", useJvmBase64 = true)
        assertTrue(result is ParseResult.Ignored)
    }

    @Test
    fun `manifest at exact 10MiB limit accepted`() {
        val sha = sha256Hex("x".toByteArray())
        val sid = sha.substring(0, 16)
        val exactMax = (10 * 1024 * 1024).toString()
        val frame = "OFT1|M|$sid|100|$exactMax|$sha|${encodeBase64url("f.bin".toByteArray())}"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        assertTrue(result is ParseResult.Manifest)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. CRC-32 REJECTION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `data frame with correct CRC accepted`() {
        val (manifestStr, dataFrames, _) = buildTestFrames()
        val result = OftProtocol.parseFrame(dataFrames[0], useJvmBase64 = true)
        assertTrue("Expected DataFrame, got $result", result is ParseResult.DataFrame)
    }

    @Test
    fun `data frame with wrong CRC rejected`() {
        val (_, dataFrames, _) = buildTestFrames()
        // Corrupt the CRC field
        val parts = dataFrames[0].split("|").toMutableList()
        parts[4] = "deadbeef"  // wrong CRC
        val corrupted = parts.joinToString("|")
        val result = OftProtocol.parseFrame(corrupted, useJvmBase64 = true)
        assertTrue(result is ParseResult.Error)
        assertTrue((result as ParseResult.Error).reason.contains("CRC"))
    }

    @Test
    fun `data frame with corrupted payload fails CRC`() {
        val payload = "Hello World".toByteArray()
        val correctCrc = crc32Hex(payload)
        val encoded = encodeBase64url(payload)
        // Change one character in the base64 to corrupt the payload
        val corruptedEncoded = encoded.substring(0, encoded.length - 1) + "A"
        val sha = sha256Hex(payload)
        val sid = sha.substring(0, 16)
        val frame = "OFT1|D|$sid|0|$correctCrc|$corruptedEncoded"
        val result = OftProtocol.parseFrame(frame, useJvmBase64 = true)
        // Either Error (CRC mismatch) or Error (decode failure) — both are acceptable rejections
        assertTrue(result is ParseResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. DUPLICATE CHUNKS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `identical duplicate chunk is silently ignored`() {
        val fileContent = "AAAA".toByteArray()
        val sha = sha256Hex(fileContent)
        val sid = sha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 1, fileContent.size.toLong(), sha, "test.bin")
        val session = TransferSession(manifest)

        val chunk = fileContent.copyOf()
        assertTrue(session.acceptChunk(0, chunk))
        assertEquals(1, session.receivedCount)

        // Same data again
        assertFalse(session.acceptChunk(0, chunk))
        assertEquals(1, session.receivedCount) // count unchanged
    }

    @Test
    fun `conflicting duplicate chunk is rejected and original kept`() {
        val fileContent = "AAAA".toByteArray()
        val sha = sha256Hex(fileContent)
        val sid = sha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 1, fileContent.size.toLong(), sha, "test.bin")
        val session = TransferSession(manifest)

        val original = "AAAA".toByteArray()
        assertTrue(session.acceptChunk(0, original))

        // Conflicting data for same index
        val conflicting = "BBBB".toByteArray()
        assertFalse(session.acceptChunk(0, conflicting))
        assertEquals(1, session.receivedCount)
        assertNotNull(session.lastRejection)
        assertTrue(session.lastRejection!!.contains("Conflicting"))

        // Original data is kept
        assertArrayEquals(original, session.chunks[0])
    }

    @Test
    fun `chunk index out of range rejected`() {
        val fileContent = "AAAA".toByteArray()
        val sha = sha256Hex(fileContent)
        val sid = sha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 1, fileContent.size.toLong(), sha, "test.bin")
        val session = TransferSession(manifest)

        assertFalse(session.acceptChunk(1, "data".toByteArray()))  // only index 0 valid
        assertFalse(session.acceptChunk(-1, "data".toByteArray()))
        assertEquals(0, session.receivedCount)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. FINAL LENGTH VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `finalize fails when not all chunks received`() {
        val fileContent = "AAAABBBB".toByteArray()
        val sha = sha256Hex(fileContent)
        val sid = sha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 2, fileContent.size.toLong(), sha, "test.bin")
        val session = TransferSession(manifest)

        session.acceptChunk(0, "AAAA".toByteArray())
        // chunk 1 not received

        val error = session.tryFinalize()
        assertNotNull(error)
        assertTrue(error!!.contains("Not all chunks"))
    }

    @Test
    fun `finalize fails when total bytes mismatch`() {
        // Create a manifest that says file is 8 bytes, but we'll provide 10
        val realContent = "AAAAABBBBB".toByteArray()  // 10 bytes
        val sha = sha256Hex("AAAABBBB".toByteArray()) // sha of 8-byte file
        val sid = sha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 2, 8, sha, "test.bin")
        val session = TransferSession(manifest)

        session.acceptChunk(0, "AAAAA".toByteArray())  // 5 bytes
        session.acceptChunk(1, "BBBBB".toByteArray())  // 5 bytes, total = 10 != 8

        val error = session.tryFinalize()
        assertNotNull(error)
        assertTrue(error!!.contains("Total bytes"))
    }

    @Test
    fun `finalize fails when non-final chunks have different sizes`() {
        // 3 chunks: first two should be same size, but we make them different
        val sha = sha256Hex("xyzxyzxyzxyz".toByteArray())
        val sid = sha.substring(0, 16)
        // Say file is 10 bytes with 3 chunks
        val manifest = ParseResult.Manifest(sid, 3, 10, sha, "test.bin")
        val session = TransferSession(manifest)

        session.acceptChunk(0, "AAA".toByteArray())   // 3 bytes
        session.acceptChunk(1, "BBBB".toByteArray())  // 4 bytes — different from chunk 0!
        session.acceptChunk(2, "CCC".toByteArray())   // 3 bytes (final, can differ)

        val error = session.tryFinalize()
        assertNotNull(error)
        assertTrue(error!!.contains("size"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. SHA-256 VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `successful end-to-end transfer with valid SHA-256`() {
        val fileContent = "Hello, OFT world! This is a test file.".toByteArray()
        val chunkSize = 16
        val sha = sha256Hex(fileContent)
        val sid = sha.substring(0, 16)
        val totalChunks = (fileContent.size + chunkSize - 1) / chunkSize

        val manifest = ParseResult.Manifest(sid, totalChunks, fileContent.size.toLong(), sha, "test.txt")
        val session = TransferSession(manifest)

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, fileContent.size)
            val chunk = fileContent.copyOfRange(start, end)
            assertTrue(session.acceptChunk(i, chunk))
        }

        val error = session.tryFinalize()
        assertNull("Expected no error, got: $error", error)
        assertTrue(session.isComplete)
        assertEquals(sha, session.verifiedSha256)
        assertArrayEquals(fileContent, session.getFileBytes())
    }

    @Test
    fun `SHA-256 mismatch is caught`() {
        // Give correct structure but wrong sha256 in manifest
        val fileContent = "Hello".toByteArray()
        val wrongSha = "a" .repeat(64)  // definitely wrong
        val sid = wrongSha.substring(0, 16)
        val manifest = ParseResult.Manifest(sid, 1, fileContent.size.toLong(), wrongSha, "test.bin")
        val session = TransferSession(manifest)

        session.acceptChunk(0, fileContent)

        val error = session.tryFinalize()
        assertNotNull(error)
        assertTrue(error!!.contains("SHA-256 mismatch"))
        assertFalse(session.isComplete)
    }

    @Test
    fun `SHA-256 verified across multiple chunks in order`() {
        // Build a file with known chunks and verify SHA-256 matches
        val part1 = "First chunk data!".toByteArray()  // 17 bytes
        val part2 = "Second chunk here".toByteArray()   // 17 bytes
        val part3 = "End".toByteArray()                 // 3 bytes
        val full = part1 + part2 + part3
        val sha = sha256Hex(full)
        val sid = sha.substring(0, 16)

        val manifest = ParseResult.Manifest(sid, 3, full.size.toLong(), sha, "multi.bin")
        val session = TransferSession(manifest)

        // Accept out of order
        assertTrue(session.acceptChunk(2, part3))
        assertTrue(session.acceptChunk(0, part1))
        assertTrue(session.acceptChunk(1, part2))

        val error = session.tryFinalize()
        assertNull("Expected success, got: $error", error)
        assertTrue(session.isComplete)
        assertArrayEquals(full, session.getFileBytes())
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. CRC-32 COMPUTATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `crc32Hex matches java util zip CRC32`() {
        val data = "Hello, World!".toByteArray()
        val expected = crc32Hex(data)
        assertEquals(expected, OftProtocol.crc32Hex(data))
    }

    @Test
    fun `crc32Hex output is 8 chars lowercase hex`() {
        val result = OftProtocol.crc32Hex("test".toByteArray())
        assertEquals(8, result.length)
        assertTrue(result.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. FILENAME SANITIZATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sanitizeFilename removes slashes`() {
        assertEquals("etc_passwd", OftProtocol.sanitizeFilename("/etc/passwd"))
        assertEquals(".._.._foo", OftProtocol.sanitizeFilename("../../foo"))
    }

    @Test
    fun `sanitizeFilename removes control chars`() {
        assertEquals("ab", OftProtocol.sanitizeFilename("a\u0001b"))
    }

    @Test
    fun `sanitizeFilename falls back on empty`() {
        assertEquals("received-file.bin", OftProtocol.sanitizeFilename(""))
        assertEquals("received-file.bin", OftProtocol.sanitizeFilename("   "))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 9. INTEGRATION: FULL FRAME PARSE → SESSION → VERIFY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full integration from raw frames to verified file`() {
        val (manifestStr, dataFrames, fileContent) = buildTestFrames()

        // Parse manifest
        val mResult = OftProtocol.parseFrame(manifestStr, useJvmBase64 = true)
        assertTrue(mResult is ParseResult.Manifest)
        val manifest = mResult as ParseResult.Manifest

        // Create session
        val session = TransferSession(manifest)

        // Feed data frames
        for (frameStr in dataFrames) {
            val dResult = OftProtocol.parseFrame(frameStr, useJvmBase64 = true)
            assertTrue("Expected DataFrame, got $dResult", dResult is ParseResult.DataFrame)
            val df = dResult as ParseResult.DataFrame
            assertEquals(manifest.sessionId, df.sessionId)
            session.acceptChunk(df.chunkIndex, df.payloadBytes)
        }

        // Verify
        assertEquals(manifest.totalChunks, session.receivedCount)
        val error = session.tryFinalize()
        assertNull("Finalize should succeed, got: $error", error)
        assertTrue(session.isComplete)
        assertArrayEquals(fileContent, session.getFileBytes())
    }

    @Test
    fun `data frames before manifest are reported correctly`() {
        val (_, dataFrames, _) = buildTestFrames()
        // Parsing a data frame works, but session should reject it without manifest
        val result = OftProtocol.parseFrame(dataFrames[0], useJvmBase64 = true)
        assertTrue(result is ParseResult.DataFrame)
        // The ViewModel handles the "no manifest" logic — parser just parses
    }
}
