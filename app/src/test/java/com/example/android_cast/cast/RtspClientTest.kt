package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake RTSP server. Test pre-loads canned response strings; the client reads them
 * line-by-line via [readLine]. Sent requests are captured in [sent] for assertion.
 */
private class FakeRtspServer : RtspClient.Transport {
    override var connected: Boolean = false
    val sent = StringBuilder()
    private val replies = ArrayDeque<String>()

    fun enqueue(response: String) {
        val lines = response.split("\n")
        for (line in lines) replies.addLast(line)
    }

    override fun connect() { connected = true }
    override fun write(line: String) { sent.append(line) }
    override fun readLine(): String? = if (replies.isEmpty()) null else replies.removeFirst()
    override fun close() { connected = false }
}

class RtspClientTest {

    private fun cannedResponse(headers: Map<String, String>, body: String? = null): String {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\n")
        sb.append("CSeq: 1\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null) sb.append("Content-Length: ${body.length}\r\n")
        sb.append("\r\n")
        val bodyLines = body?.split("\n") ?: emptyList()
        for (l in bodyLines) sb.append("$l\n")
        return sb.toString()
    }

    @Test
    fun `setup extracts interleaved channel from server Transport header`() {
        val server = FakeRtspServer().apply {
            // OPTIONS, ANNOUNCE, SETUP, RECORD responses
            enqueue(cannedResponse(mapOf("Public" to "OPTIONS, ANNOUNCE")))
            enqueue(cannedResponse(mapOf("Session" to "ABC123")))
            enqueue(cannedResponse(mapOf(
                "Session" to "ABC123",
                "Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3;mode=record",
            )))
            enqueue(cannedResponse(mapOf("Session" to "ABC123")))
        }
        val client = RtspClient(server)
        client.connect()
        client.announce(sdp())
        val channel = client.setup()
        client.record()

        assertEquals(2, channel)
        assertEquals(RtspClient.State.streaming, client.state.value)
        // Session header carried through subsequent requests
        assertTrue(server.sent.contains("Session: ABC123"))
    }

    @Test
    fun `teardown returns to idle and resets session`() {
        val server = FakeRtspServer().apply {
            enqueue(cannedResponse(mapOf("Public" to "OPTIONS")))
            enqueue(cannedResponse(mapOf("Session" to "X")))
            enqueue(cannedResponse(mapOf("Session" to "X", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record")))
            enqueue(cannedResponse(mapOf("Session" to "X")))
            enqueue(cannedResponse(mapOf("Session" to "X")))  // teardown
        }
        val client = RtspClient(server)
        client.connect()
        client.announce(sdp())
        client.setup()
        client.record()
        client.teardown()

        assertEquals(RtspClient.State.idle, client.state.value)
        assertTrue(server.sent.contains("TEARDOWN"))
        assertTrue(server.sent.contains("Session: X"))
    }

    @Test
    fun `default setup transport sends mode=record interleaved 0-1`() {
        val server = FakeRtspServer().apply {
            enqueue(cannedResponse(mapOf("Public" to "OPTIONS")))
            enqueue(cannedResponse(mapOf("Session" to "S")))
            enqueue(cannedResponse(mapOf("Session" to "S", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record")))
            enqueue(cannedResponse(mapOf("Session" to "S")))
        }
        val client = RtspClient(server)
        client.connect()
        // connect() sends OPTIONS and reads response #1
        client.announce(sdp())        // reads response #2
        client.setup()                 // reads response #3
        client.record()                // reads response #4

        assertTrue(server.sent.contains("Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"))
    }

    @Test
    fun `teardown without connect is a no-op returning to idle`() {
        val server = FakeRtspServer()
        val client = RtspClient(server)
        client.teardown()
        assertEquals(RtspClient.State.idle, client.state.value)
        assertNull("nothing should be written when not connected",
            server.sent.toString().takeIf { it.isNotEmpty() })
    }

    private fun sdp() = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=No Name\r\nt=0 0\r\n" +
        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
}
