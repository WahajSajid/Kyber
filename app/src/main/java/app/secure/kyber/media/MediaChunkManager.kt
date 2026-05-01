package app.secure.kyber.media

import android.content.Context
import android.util.Base64
import android.util.Log
import app.secure.kyber.backend.beans.MediaChunkDto
import java.io.File
import java.io.RandomAccessFile

object MediaChunkManager {

    private const val TAG = "MediaChunkManager"
    const val CHUNK_SIZE_BYTES = 64 * 1024 // 16 KB raw → ~175 KB Base64 encoded

    /**
     * Build chunks from a file in memory (does not touch disk).
     * Returns List of MediaChunkDto ready for transmission.
     */
    fun buildChunks(
        context: Context,
        filePath: String,
        mediaId: String,
        originalMessageId: String,
        mimeType: String,
        ampsJson: String = "",
        caption: String = "",
        durationMs: Long = 0L,
        onProgress: ((Int) -> Unit)? = null
    ): List<MediaChunkDto> {
        return try {
            val file = resolveFile(context, filePath) ?: return emptyList()
            val totalBytes = file.length()
            if (totalBytes == 0L) return emptyList()

            val totalChunks = ((totalBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()
            val chunks = ArrayList<MediaChunkDto>(totalChunks)
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            val raf = RandomAccessFile(file, "r")

            try {
                for (i in 0 until totalChunks) {
                    val bytesRead = raf.read(buffer)
                    if (bytesRead <= 0) break
                    val chunkData = Base64.encodeToString(
                        buffer.copyOf(bytesRead),
                        Base64.NO_WRAP
                    )
                    chunks.add(
                        MediaChunkDto(
                            mediaId = mediaId,
                            messageId = originalMessageId,
                            index = i,
                            total = totalChunks,
                            mimeType = mimeType,
                            data = chunkData,
                            ampsJson = if (i == 0) ampsJson else "",
                            caption = if (i == 0) caption else "",
                            totalBytes = totalBytes,
                            durationMs = durationMs
                        )
                    )
                    onProgress?.invoke(((i + 1) * 100) / totalChunks)
                }
            } finally {
                raf.close()
            }
            chunks
        } catch (e: Exception) {
            Log.e(TAG, "buildChunks failed", e)
            emptyList()
        }
    }

    /**
     * Reassemble encrypted chunks from filesDir/chunks_{mediaId} into a complete encrypted file.
     * Saves to filesDir/received_media/{mediaId}.
     * Returns the absolute path to the assembled encrypted file, or null on error.
     *
     * Important: The result file is ENCRYPTED. Use MessageEncryptionManager to decrypt before viewing.
     */
    fun assembleChunksFromDisk(
        context: Context,
        mediaId: String,
        mimeType: String,
        onProgress: ((Int) -> Unit)? = null
    ): String? {
        return try {
            val chunkDir = getChunkDir(context, mediaId)
            val chunkFiles = chunkDir.listFiles()
                ?.filter { it.name.startsWith("chunk_") }
                ?.sortedBy { it.name.removePrefix("chunk_").toIntOrNull() ?: 0 }
                ?: return null

            if (chunkFiles.isEmpty()) {
                Log.w(TAG, "No chunk files found in $chunkDir")
                return null
            }

            // Extension for the final file
            val ext = when (mimeType.uppercase()) {
                "AUDIO" -> "m4a"
                "VIDEO" -> "mp4"
                else -> "jpg"
            }

            // Ensure received_media directory exists
            val receivedMediaDir = File(context.filesDir, "received_media").apply { mkdirs() }

            // Write to temp file first, then atomically rename
            val tmpFile = File(receivedMediaDir, "assembling_${mediaId}.$ext")
            val outFile = File(receivedMediaDir, "${mediaId}.$ext")

            tmpFile.outputStream().buffered(32 * 1024).use { out ->
                chunkFiles.forEachIndexed { idx, chunkFile ->
                    // Streaming decode: read Base64 text → decode bytes → write directly
                    // This way we never load the entire file into memory
                    chunkFile.inputStream().bufferedReader().use { reader ->
                        val b64 = reader.readText()
                        val decoded = Base64.decode(b64, Base64.NO_WRAP)
                        out.write(decoded)
                        decoded.fill(0) // Allow GC to collect immediately
                    }
                    onProgress?.invoke(((idx + 1) * 100) / chunkFiles.size)
                }
                out.flush()
            }

            // Atomic rename — file only exists at final path if write fully completes
            if (!tmpFile.renameTo(outFile)) {
                Log.e(TAG, "Failed to rename tmp file to $outFile")
                tmpFile.delete()
                return null
            }

            if (!outFile.exists() || outFile.length() == 0L) {
                Log.e(TAG, "Output file is empty or doesn't exist: $outFile")
                outFile.delete()
                return null
            }

            Log.d(TAG, "Successfully assembled chunks to $outFile (${outFile.length()} bytes)")

            // Clean up chunk directory after successful assembly
            deleteChunkDirectory(context, mediaId)

            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "assembleChunksFromDisk failed for mediaId=$mediaId", e)
            null
        }
    }

    /**
     * Save a single encrypted Base64-encoded chunk to disk at filesDir/chunks_{mediaId}/chunk_{index}.
     */
    fun saveChunkToDisk(context: Context, mediaId: String, index: Int, base64Data: String) {
        try {
            val chunkDir = getChunkDir(context, mediaId)
            if (!chunkDir.exists()) {
                val created = chunkDir.mkdirs()
                Log.d(TAG, "Created chunk directory: $chunkDir (success=$created)")
            }
            val chunkFile = File(chunkDir, "chunk_${index.toString().padStart(6, '0')}")
            chunkFile.writeText(base64Data)
            Log.d(TAG, "Saved chunk $index to $chunkFile")
        } catch (e: Exception) {
            Log.e(TAG, "saveChunkToDisk failed for mediaId=$mediaId, index=$index", e)
        }
    }

    /**
     * Count the number of chunks already saved for a given mediaId.
     */
    fun countSavedChunks(context: Context, mediaId: String): Int {
        val count = getChunkDir(context, mediaId)
            .listFiles()?.count { it.name.startsWith("chunk_") } ?: 0
        Log.d(TAG, "Counted $count chunks for mediaId=$mediaId")
        return count
    }

    /**
     * Recursively delete the chunk directory for a given mediaId.
     * Called after successful assembly or on cleanup.
     */
    fun deleteChunkDirectory(context: Context, mediaId: String) {
        try {
            val chunkDir = getChunkDir(context, mediaId)
            if (chunkDir.exists()) {
                val deleted = chunkDir.deleteRecursively()
                Log.d(TAG, "Deleted chunk directory: $chunkDir (success=$deleted)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteChunkDirectory failed for mediaId=$mediaId", e)
        }
    }

    /**
     * Get the directory path for storing chunks: filesDir/chunks_{mediaId}/
     */
    fun getChunkDir(context: Context, mediaId: String): File =
        File(context.filesDir, "chunks_$mediaId")

    /**
     * Resolve a file path from various formats:
     * - file:// URLs
     * - content:// URIs (copied to temp location)
     * - Standard absolute paths
     */
    fun resolveFile(context: Context, path: String): File? {
        val cleaned = path
            .removePrefix("file://")
            .removePrefix("content://")
        val f = File(cleaned)
        if (f.exists()) return f

        // Try content URI via stream copy
        return try {
            val uri = android.net.Uri.parse(path)
            if (uri.scheme == "content") {
                val ext = when {
                    path.contains("audio") -> "m4a"
                    path.contains("video") -> "mp4"
                    else -> "tmp"
                }
                val tmp = File(context.cacheDir, "tmp_resolve_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                }
                if (tmp.exists() && tmp.length() > 0) tmp else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "resolveFile failed for path=$path", e)
            null
        }
    }
    fun decodeThumbnail(context: Context, b64: String, mediaId: String): String? {
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val dir = java.io.File(context.cacheDir, "thumbnails").apply { mkdirs() }
            val file = java.io.File(dir, "thumb_$mediaId.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("MediaChunkManager", "decodeThumbnail failed", e)
            null
        }
    }
}