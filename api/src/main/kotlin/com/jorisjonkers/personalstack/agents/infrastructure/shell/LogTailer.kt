package com.jorisjonkers.personalstack.agents.infrastructure.shell

import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Polls an append-only log file and streams new output as bounded text
 * chunks. Ported from agent-gateway's `LogTailer` — tmux's pipe-pane
 * writes raw pane output here, so this is the streaming-from-PTY
 * mechanism without a fifo or a JNI tmux library, unchanged by the
 * process now running in-container.
 *
 * Bounded frames keep a noisy agent from forcing a multi-megabyte
 * receive buffer per session, and the UTF-8 boundary carry keeps a
 * multi-byte codepoint from being decoded — or split across frames —
 * in halves.
 */
class LogTailer(
    private val file: Path,
    private val intervalMs: Long = 40,
    private val maxChunkChars: Int = MAX_CHUNK_CHARS,
    private val onText: (String) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LogTailer::class.java)
    private val offset = AtomicLong(0)

    // Trailing bytes of an incomplete UTF-8 sequence held back from the
    // previous read until the rest of the codepoint arrives.
    private var carry = ByteArray(0)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "log-tailer-${file.fileName}").apply { isDaemon = true }
        }

    fun start() {
        offset.set(currentLength())
        executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun currentLength(): Long =
        try {
            RandomAccessFile(file.toFile(), "r").use { it.length() }
        } catch (e: java.io.IOException) {
            log.warn("sizing {} failed: {}", file, e.message)
            0L
        }

    private fun poll() {
        try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val length = raf.length()
                var currentOffset = offset.get()
                if (length < currentOffset) {
                    // The log was truncated to stay under its disk cap;
                    // restart from the new beginning.
                    offset.set(0)
                    carry = ByteArray(0)
                    currentOffset = 0
                }
                if (length <= currentOffset) return
                raf.seek(currentOffset)
                val toRead = (length - currentOffset).coerceAtMost(MAX_READ_BYTES.toLong()).toInt()
                val raw = ByteArray(toRead)
                val read = raf.read(raw)
                if (read <= 0) return
                offset.addAndGet(read.toLong())
                emit(raw, read)
            }
        } catch (e: java.io.IOException) {
            log.warn("tail of {} failed: {}", file, e.message)
        }
    }

    private fun emit(
        raw: ByteArray,
        read: Int,
    ) {
        val buf = if (carry.isEmpty()) raw.copyOf(read) else carry + raw.copyOf(read)
        val complete = completeUtf8Length(buf)
        carry = if (complete < buf.size) buf.copyOfRange(complete, buf.size) else EMPTY
        if (complete == 0) return
        chunked(String(buf, 0, complete, Charsets.UTF_8), maxChunkChars, onText)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val MAX_CHUNK_CHARS = 16 * 1024
        private const val MAX_READ_BYTES = 64 * 1024
        private const val BYTE_TO_UNSIGNED_MASK = 0xFF
        private const val NO_UTF8_LEAD_BYTE = -1
        private const val UTF8_CONTINUATION_MASK = 0xC0
        private const val UTF8_CONTINUATION_PREFIX = 0x80
        private const val UTF8_SINGLE_BYTE_LIMIT = 0x80
        private const val UTF8_TWO_BYTE_LEAD_START = 0xC0
        private const val UTF8_TWO_BYTE_LEAD_END = 0xDF
        private const val UTF8_THREE_BYTE_LEAD_START = 0xE0
        private const val UTF8_THREE_BYTE_LEAD_END = 0xEF
        private const val UTF8_FOUR_BYTE_LEAD_START = 0xF0
        private const val UTF8_FOUR_BYTE_LEAD_END = 0xF7
        private const val UTF8_SINGLE_BYTE_SEQUENCE_LENGTH = 1
        private const val UTF8_TWO_BYTE_SEQUENCE_LENGTH = 2
        private const val UTF8_THREE_BYTE_SEQUENCE_LENGTH = 3
        private const val UTF8_FOUR_BYTE_SEQUENCE_LENGTH = 4
        private val EMPTY = ByteArray(0)

        /**
         * Index of the first byte of an incomplete trailing UTF-8
         * sequence, or `buf.size` if the buffer ends on a complete
         * codepoint. Malformed lead bytes are treated as complete so the
         * decoder substitutes them rather than the carry growing forever.
         */
        internal fun completeUtf8Length(buf: ByteArray): Int {
            if (buf.isEmpty()) {
                return 0
            }
            val leadIndex = trailingLeadByteIndex(buf)
            return if (leadIndex == NO_UTF8_LEAD_BYTE) {
                buf.size
            } else {
                val availableSequenceBytes = buf.size - leadIndex
                val expectedSequenceBytes = utf8SequenceLength(buf[leadIndex].toUnsignedInt())
                if (availableSequenceBytes >= expectedSequenceBytes) buf.size else leadIndex
            }
        }

        private fun trailingLeadByteIndex(buf: ByteArray): Int {
            var i = buf.size - 1
            while (i >= 0 && buf[i].isUtf8Continuation()) {
                i--
            }
            return i
        }

        private fun Byte.isUtf8Continuation(): Boolean {
            val prefix = toInt() and UTF8_CONTINUATION_MASK
            return prefix == UTF8_CONTINUATION_PREFIX
        }

        private fun Byte.toUnsignedInt(): Int = toInt() and BYTE_TO_UNSIGNED_MASK

        private fun utf8SequenceLength(lead: Int): Int =
            when {
                lead < UTF8_SINGLE_BYTE_LIMIT -> UTF8_SINGLE_BYTE_SEQUENCE_LENGTH
                lead in UTF8_TWO_BYTE_LEAD_START..UTF8_TWO_BYTE_LEAD_END -> UTF8_TWO_BYTE_SEQUENCE_LENGTH
                lead in UTF8_THREE_BYTE_LEAD_START..UTF8_THREE_BYTE_LEAD_END -> UTF8_THREE_BYTE_SEQUENCE_LENGTH
                lead in UTF8_FOUR_BYTE_LEAD_START..UTF8_FOUR_BYTE_LEAD_END -> UTF8_FOUR_BYTE_SEQUENCE_LENGTH
                else -> UTF8_SINGLE_BYTE_SEQUENCE_LENGTH
            }

        /**
         * Feeds [text] to [action] in pieces of at most [maxChars],
         * never splitting a surrogate pair across two pieces.
         */
        internal fun chunked(
            text: String,
            maxChars: Int,
            action: (String) -> Unit,
        ) {
            var i = 0
            while (i < text.length) {
                var end = (i + maxChars).coerceAtMost(text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                action(text.substring(i, end))
                i = end
            }
        }
    }
}
