package app.secure.kyber.media

import android.content.Context
import android.util.Base64
import android.util.Log
import app.secure.kyber.backend.beans.MediaChunkDto
import java.io.File
import java.io.RandomAccessFile

object MediaChunkManager {

    private const val TAG = "MediaChunkManager"
    const val CHUNK_SIZE_BYTES = 32 * 1024 // 64 KB raw → ~88 KB per message payload

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
     * Reassemble chunks already on disk (sorted by index) into a single file.
     * Returns the assembled file path or null on error.
     */
    fun assembleChunks(
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

            if (chunkFiles.isEmpty()) return null

            val ext = when (mimeType.uppercase()) {
                "AUDIO" -> "m4a"; "VIDEO" -> "mp4"; else -> "jpg"
            }
            val outFile = File(context.cacheDir, "assembled_${mediaId}.$ext")
            // Write to temp file first, rename atomically when complete
            val tmpFile = File(context.cacheDir, "assembling_${mediaId}.$ext")

            tmpFile.outputStream().buffered(32 * 1024).use { out ->
                chunkFiles.forEachIndexed { idx, chunkFile ->
                    // Streaming decode: read Base64 text → decode → write directly
                    // Never hold entire file in memory
                    chunkFile.inputStream().bufferedReader().use { reader ->
                        val b64 = reader.readText()
                        val decoded = Base64.decode(b64, Base64.NO_WRAP)
                        out.write(decoded)
                        decoded.fill(0) // allow GC immediately
                    }
                    onProgress?.invoke(((idx + 1) * 100) / chunkFiles.size)
                }
                out.flush()
            }

            // Atomic rename — only exists as final path if fully written
            tmpFile.renameTo(outFile)

            if (!outFile.exists() || outFile.length() == 0L) {
                tmpFile.delete()
                return null
            }

            chunkDir.deleteRecursively()
            "file://${outFile.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "assembleChunks failed", e)
            null
        }
    }

    fun saveChunkToDisk(context: Context, mediaId: String, index: Int, base64Data: String) {
        try {
            val chunkDir = getChunkDir(context, mediaId)
            if (!chunkDir.exists()) chunkDir.mkdirs()
            File(chunkDir, "chunk_${index.toString().padStart(6, '0')}").writeText(base64Data)
        } catch (e: Exception) {
            Log.e(TAG, "saveChunkToDisk failed", e)
        }
    }

    fun countSavedChunks(context: Context, mediaId: String): Int {
        return getChunkDir(context, mediaId)
            .listFiles()?.count { it.name.startsWith("chunk_") } ?: 0
    }

    private fun getChunkDir(context: Context, mediaId: String): File =
        File(context.cacheDir, "chunks_$mediaId")

    private fun resolveFile(context: Context, path: String): File? {
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
            null
        }
    }
}