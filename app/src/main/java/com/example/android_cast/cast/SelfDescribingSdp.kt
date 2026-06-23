package com.example.android_cast.cast

/** Builds the SDP the Android sender ANNOUNCEs to the Mac receiver. */
class SelfDescribingSdp(private val hostName: String) {
    fun sdp(): String = buildString {
        append("v=0\r\n")
        append("o=- 0 0 IN IP4 $hostName\r\n")
        append("s=MirrorCast\r\n")
        append("c=IN IP4 $hostName\r\n")
        append("t=0 0\r\n")
        append("m=video 0 RTP/AVP 96\r\n")
        append("a=rtpmap:96 H264/90000\r\n")
        append("a=fmtp:96 packetization-mode=1\r\n")
        append("a=control:streamid=0\r\n")
    }
}
