package com.Ynnk.YnnkMsg.email

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlin.String

private const val TAG = "ChunkAssembler"
private const val STALE_TRANSFER_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days

/**
 * Singleton that tracks incoming chunked file transfers keyed by "$fromEmail:$subjectTimestamp".
 *
 * A chunked transfer consists of:
 *  - One MD5 header message: subject ends with {md5_file1;md5_file2;...}
 *  - N chunk data messages: subject ends with {chunkIdx/totalChunks;0/totalChunks;...}
 *    where 0 means no data for that file in this message.
 *
 * Messages may arrive in any order. Assembly happens as soon as all chunks
 * and the MD5 header are received.
 *
 * Transfers from different contacts are isolated by the key, so simultaneous
 * large transfers from multiple contacts are handled independently.
 */
object ChunkAssembler {

    data class AssemblyResult(
        val fromEmail: String,
        var originalNames: List<String> = emptyList(),
        var authorEmail: String = "",
        var decodedMessage: String = "",
        val attachmentPaths: List<String>,
        val timestamp: Long,
        val format: String,
        val isCorrupted: Boolean = false
    )

    private data class TransferState(
        val fromEmail: String,
        val subjectTimestamp: String,

        var originalNames: List<String> = emptyList(),
        var authorEmail: String = "",
        var decodedMessage: String = "",

        // Set when MD5 header message arrives
        //var messageText: String? = null,
        var messageTimestamp: Long = 0L,
        var md5s: List<String>? = null,
        var format: String = "text",
        // Set from either MD5 message or first chunk message
        var fileCount: Int = 0,
        // fileIndex -> (chunkIndex 1-based -> temp file path)
        val chunkPaths: MutableMap<Int, MutableMap<Int, String>> = mutableMapOf(),
        // fileIndex -> original filename (from the attachment's filename header)
        val fileNames: MutableMap<Int, String> = mutableMapOf(),
        // fileIndex -> totalChunks; populated from any chunk message (including 0/N entries)
        val totalChunks: MutableMap<Int, Int> = mutableMapOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val transfers = mutableMapOf<String, TransferState>()

    private fun key(fromEmail: String, timestamp: String) = "$fromEmail:$timestamp"

    /**
     * Register the MD5 header message for a transfer.
     * Returns an AssemblyResult if all chunks were already received before this message.
     */
    @Synchronized
    fun addMd5Message(
        context: Context,
        fromEmail: String,
        subjectTimestamp: String,
        md5s: List<String>,
        originalNames: List<String>,
        authorEmail: String,
        decodedMessage: String,
        sendTime: Long,
        format: String
    ): AssemblyResult? {
        cleanOldTransfers(context)
        val state = transfers.getOrPut(key(fromEmail, subjectTimestamp)) {
            TransferState(fromEmail, subjectTimestamp)
        }
        state.md5s = md5s
        state.fileCount = md5s.size
        state.originalNames = originalNames
        state.authorEmail = authorEmail
        state.decodedMessage = decodedMessage
        state.messageTimestamp = sendTime
        state.format = format
        // Authoritatively overwrite fileNames with original names from params.
        // This is what checkComplete uses when naming the assembled output file,
        // so it must reflect the real filename, not the chunk transport name (01.bin).
        originalNames.forEachIndexed { idx, name -> state.fileNames[idx] = name }
        return checkComplete(context, state)
    }

    /**
     * Register a chunk data message for a transfer.
     *
     * @param chunkEntries one entry per file: (chunkIndex, totalChunks).
     *        chunkIndex == 0 means this message carries no data for that file,
     *        but totalChunks is still recorded so we know the expected total.
     * @param attachments (originalFileName, tempFilePath) for each non-zero entry,
     *        in file-index order.
     * Returns an AssemblyResult if this was the last missing piece.
     */
    @Synchronized
    fun addChunkMessage(
        context: Context,
        fromEmail: String,
        subjectTimestamp: String,
        chunkEntries: List<Pair<Int, Int>>,
        attachments: List<Pair<String, String>>
    ): AssemblyResult? {
        cleanOldTransfers(context)
        val state = transfers.getOrPut(key(fromEmail, subjectTimestamp)) {
            TransferState(fromEmail, subjectTimestamp)
        }
        if (state.fileCount == 0) state.fileCount = chunkEntries.size

        var attachIdx = 0
        chunkEntries.forEachIndexed { fileIdx, (chunkIdx, total) ->
            // Always record totalChunks so we know how many to expect, even for zero entries
            if (total > 0) state.totalChunks[fileIdx] = total

            if (chunkIdx > 0 && attachIdx < attachments.size) {
                val (fileName, filePath) = attachments[attachIdx++]
                state.chunkPaths.getOrPut(fileIdx) { mutableMapOf() }[chunkIdx] = filePath
                if (!state.fileNames.containsKey(fileIdx)) {
                    state.fileNames[fileIdx] = fileName
                }
            }
        }
        return checkComplete(context, state)
    }

    private fun checkComplete(context: Context, state: TransferState): AssemblyResult? {
        val md5s = state.md5s ?: return null   // MD5 header not yet received
        val fileCount = state.fileCount
        if (fileCount == 0) return null

        // Verify all files have known totals and all their chunks have arrived
        for (fileIdx in 0 until fileCount) {
            val total = state.totalChunks[fileIdx] ?: return null
            val received = state.chunkPaths[fileIdx]?.size ?: 0
            if (received < total) return null
        }

        Log.d(TAG, "All chunks received for ${state.fromEmail}:${state.subjectTimestamp} — assembling")

        val outputDir = File(context.getExternalFilesDir(null), "attachments").apply { mkdirs() }
        val assembledPaths = mutableListOf<String>()
        var isCorrupted = false

        for (fileIdx in 0 until fileCount) {
            val total = state.totalChunks[fileIdx]!!
            val chunks = state.chunkPaths[fileIdx]!!
            val fileName = state.fileNames[fileIdx] ?: "attachment_$fileIdx"
            val expectedMd5 = md5s[fileIdx]

            val outputFile = File(outputDir, fileName)
            try {
                outputFile.outputStream().use { out ->
                    for (chunkIdx in 1..total) {
                        val chunkPath = chunks[chunkIdx]
                            ?: throw Exception("Missing chunk $chunkIdx for file $fileIdx")
                        File(chunkPath).inputStream().use { it.copyTo(out) }
                    }
                }
                val actualMd5 = computeMd5(outputFile)
                if (actualMd5 != expectedMd5) {
                    Log.e(TAG, "MD5 mismatch for $fileName: expected=$expectedMd5 actual=$actualMd5")
                    isCorrupted = true
                }
                assembledPaths.add(outputFile.absolutePath)
                Log.d(TAG, "Assembled $fileName (${outputFile.length()} bytes), MD5 status: ${if (actualMd5 == expectedMd5) "OK" else "MISMATCH"}")
            } catch (e: Exception) {
                Log.e(TAG, "Assembly error for file $fileIdx", e)
                isCorrupted = true
            }
        }

        cleanup(state)
        transfers.remove(key(state.fromEmail, state.subjectTimestamp))

        return AssemblyResult(
            fromEmail = state.fromEmail,
            originalNames = state.originalNames,
            state.authorEmail,
            state.decodedMessage,
            assembledPaths,
            timestamp = state.messageTimestamp,
            format = state.format,
            isCorrupted = isCorrupted
        )
    }

    private fun cleanup(state: TransferState) {
        for (fileIdx in 0 until state.fileCount) {
            state.chunkPaths[fileIdx]?.values?.forEach { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
    }

    private fun cleanOldTransfers(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_TRANSFER_MS
        val staleKeys = transfers.entries.filter { it.value.createdAt < cutoff }.map { it.key }
        staleKeys.forEach { k ->
            transfers[k]?.let { cleanup(it) }
            transfers.remove(k)
            Log.d(TAG, "Cleaned stale transfer: $k")
        }
    }

    fun computeMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
