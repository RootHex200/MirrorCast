package com.example.android_cast.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal RTSP *client* (sender-side). The Android device opens an RTSP TCP
 * connection to the Mac receiver, runs `OPTIONS → ANNOUNCE → SETUP → RECORD →
 * TEARDOWN`, and tracks the session ID returned by the server.
 *
 * RTP can either be interleaved on the same TCP socket (the `$\n` framing) or
 * sent on a separate UDP port negotiated via the SETUP Transport header. Issue #4
 * already does UDP packetization; this client negotiates the channel.
 *
 * Pure JVM testable: tests inject a fake [Transport].
 */
class RtspClient(
    private val transport: Transport,
) {
    /** Testable seam: real impl owns a Socket; fakes record sent lines. */
    interface Transport {
        fun connect()
        fun write(line: String)
        fun readLine(): String?
        fun close()
        val connected: Boolean
    }

    enum class State { idle, handshake, streaming, teardown }

    private val _state = MutableStateFlow(State.idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var sessionId: String? = null
    private var cseq: Int = 0

    fun connect() {
        transport.connect()
        _state.value = State.handshake
        // Real senders run OPTIONS first; mirrors the receiver side.
        sendRequest("OPTIONS", uri = "*", extraHeaders = emptyMap())
    }

    fun announce(sdp: String) {
        require(transport.connected) { "not connected" }
        sendRequest("ANNOUNCE", uri = "*", extraHeaders = mapOf(
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.length.toString(),
        ), body = sdp)
        drainResponse()
    }

    /** Returns the interleaved RTP channel negotiated with the server. */
    fun setup(transportHeader: String = "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"): Int {
        val response = sendRequest("SETUP", uri = "*", extraHeaders = mapOf(
            "Transport" to transportHeader,
        ))
        drainBodyAfter(response.contentLength)
        val setSession = response.headers["Session"] ?: response.headers["session"]
        if (setSession != null) sessionId = setSession
        val serverTransport = response.headers["Transport"] ?: response.headers["transport"] ?: ""
        val channel = extractInterleavedChannel(serverTransport) ?: 0
        return channel
    }

    fun record() {
        val response = sendRequest("RECORD", uri = "*", extraHeaders = sessionHeader())
        drainBodyAfter(response.contentLength)
        _state.value = State.streaming
    }

    fun teardown() {
        if (!transport.connected) {
            _state.value = State.idle
            return
        }
        _state.value = State.teardown
        try {
            val response = sendRequest("TEARDOWN", uri = "*", extraHeaders = sessionHeader())
            drainBodyAfter(response.contentLength)
        } catch (_: Throwable) {
            // best-effort
        }
        transport.close()
        _state.value = State.idle
        sessionId = null
    }

    private fun sessionHeader(): Map<String, String> {
        val id = sessionId ?: return emptyMap()
        return mapOf("Session" to id)
    }

    private fun sendRequest(
        method: String,
        uri: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        cseq += 1
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        for ((k, v) in extraHeaders) {
            sb.append("$k: $v\r\n")
        }
        if (body != null) sb.append("\r\n$body")
        else sb.append("\r\n")
        transport.write(sb.toString())
        return readResponse()
    }

    private fun readResponse(): Response {
        val statusLine = transport.readLine() ?: return Response(0, "", emptyMap(), 0)
        val parts = statusLine.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = transport.readLine()?.trimEnd('\r', '\n') ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        return Response(code, statusLine, headers, contentLength)
    }

    private fun drainResponse() {
        // Response already read by sendRequest; only body if Content-Length set.
        val resp = Response(0, "", emptyMap(), 0)
        drainBodyAfter(resp.contentLength)
    }

    private fun drainBodyAfter(contentLength: Int) {
        if (contentLength <= 0) return
        // Body bytes arrive as further readLine() calls in the simple test transport.
        var remaining = contentLength
        val sb = StringBuilder()
        while (remaining > 0) {
            val line = transport.readLine() ?: break
            sb.appendLine(line)
            remaining -= line.length + 2
        }
    }

    private fun extractInterleavedChannel(transportHeader: String): Int? {
        val range = transportHeader.substringAfter("interleaved=", "").takeWhile { it.isDigit() }
        return range.toIntOrNull()
    }

    data class Response(val code: Int, val statusLine: String, val headers: Map<String, String>, val contentLength: Int)
}

/**
 * Real Socket-backed RTSP transport that also supports interleaved RTP frames.
 * Reads bytes one at a time for RTSP response lines (so interleaved `$`-prefixed
 * frames don't get consumed by a buffered reader).
 */
class SocketRtspTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 10_000,
) : RtspClient.Transport {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: java.io.InputStream? = null

    override var connected: Boolean = false
        private set

    override fun connect() {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        sock.soTimeout = timeoutMs
        out = sock.getOutputStream()
        input = sock.getInputStream()
        socket = sock
        connected = true
    }

    override fun write(line: String) {
        writeRaw(line.toByteArray(Charsets.US_ASCII))
        out?.flush()
    }

    /** Write raw bytes (used for interleaved RTP frames alongside RTSP requests). */
    fun writeRaw(bytes: ByteArray) {
        out?.write(bytes)
        out?.flush()
    }

    /**
     * Reads one line, but if a `$` (0x24) shows up as the first non-CRLF byte, this
     * is an interleaved frame from the server: we read its 4-byte header + payload
     * and return null so the RTSP state machine doesn't choke. Real RTSP responses
     * always start with 'R' (RTSP/...).
     */
    override fun readLine(): String? {
        val inp = input ?: return null
        val sb = StringBuilder()
        var prevCR = false
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            if (b == 0x24 && sb.isEmpty()) {
                // Interleaved frame: discard header + payload; let caller retry.
                val ch = inp.read()
                val hi = inp.read()
                val lo = inp.read()
                if (ch < 0 || hi < 0 || lo < 0) return null
                val len = (hi shl 8) or lo
                val skip = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = inp.read(skip, off, len - off)
                    if (n < 0) return null
                    off += n
                }
                continue  // try next line
            }
            if (b == 0x0A && prevCR) {
                sb.deleteCharAt(sb.length - 1)  // drop the CR
                return sb.toString()
            }
            sb.append(b.toChar())
            prevCR = (b == 0x0D)
        }
    }

    override fun close() {
        connected = false
        runCatching { input?.close() }
        runCatching { out?.close() }
        runCatching { socket?.close() }
        socket = null
        out = null
        input = null
    }
}
