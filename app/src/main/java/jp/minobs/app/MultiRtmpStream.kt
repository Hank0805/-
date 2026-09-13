package jp.minobs.app

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * One hardware encoder feeding several RTMP/RTMPS destinations.
 * This avoids creating a second MediaProjection/encoder for restreaming.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MultiRtmpStream(
    context: Context,
    private val checker: ConnectChecker,
    videoSource: VideoSource,
    audioSource: AudioSource
) : StreamBase(context, videoSource, audioSource) {

    private val clientListener = object : StreamClientListener {
        override fun onRequestKeyframe() = requestKeyframe()
    }
    private val primary = RtmpClient(checker)
    private val primaryWrapper = RtmpStreamClient(primary, clientListener)
    private val clients = mutableListOf(primary)
    private var videoCodec = VideoCodec.H264
    private var audioCodec = AudioCodec.AAC
    private var sampleRate = 44_100
    private var stereo = true
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    fun startMulti(endpoints: List<String>) {
        val clean = endpoints.map { it.trim() }.filter { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }.distinct().take(4)
        require(clean.isNotEmpty()) { "At least one RTMP/RTMPS endpoint is required" }
        startStream(clean.joinToString("\n"))
    }

    fun destinationCount(): Int = clients.size

    override fun getStreamClient(): StreamBaseClient = primaryWrapper

    override fun setVideoCodecImp(codec: VideoCodec) {
        require(codec == VideoCodec.H264 || codec == VideoCodec.H265) { "RTMP multi output supports H264/H265" }
        videoCodec = codec
        clients.forEach { it.setVideoCodec(codec) }
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        require(codec == AudioCodec.AAC) { "RTMP multi output supports AAC" }
        audioCodec = codec
        clients.forEach { it.setAudioCodec(codec) }
    }

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
        this.sampleRate = sampleRate
        this.stereo = isStereo
        clients.forEach { it.setAudioInfo(sampleRate, isStereo) }
    }

    override fun startStreamImp(endPoint: String) {
        val endpoints = endPoint.split('\n').map { it.trim() }
            .filter { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }
            .distinct().take(4)
        if (endpoints.isEmpty()) {
            checker.onConnectionFailed("No valid RTMP destination")
            return
        }

        // Keep primary object stable because getStreamClient() exposes its wrapper.
        while (clients.size > 1) clients.removeLast().disconnect()
        repeat(endpoints.size - 1) { clients += RtmpClient(checker) }

        val resolution = getVideoResolution()
        clients.forEachIndexed { index, client ->
            client.setVideoCodec(videoCodec)
            client.setAudioCodec(audioCodec)
            client.setAudioInfo(sampleRate, stereo)
            client.setVideoResolution(resolution.width, resolution.height)
            client.setFps(getVideoFps())
            val s = sps; if (s != null) client.setVideoInfo(ByteBuffer.wrap(s), pps?.let(ByteBuffer::wrap), vps?.let(ByteBuffer::wrap))
            client.connect(endpoints[index])
        }
    }

    override fun stopStreamImp() {
        clients.forEach { runCatching { it.disconnect() } }
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        this.sps = sps.toByteArraySafe()
        this.pps = pps?.toByteArraySafe()
        this.vps = vps?.toByteArraySafe()
        clients.forEach { client ->
            client.setVideoInfo(
                ByteBuffer.wrap(this.sps!!),
                this.pps?.let(ByteBuffer::wrap),
                this.vps?.let(ByteBuffer::wrap)
            )
        }
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        clients.forEach { it.sendVideo(videoBuffer.duplicate(), info) }
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        clients.forEach { it.sendAudio(audioBuffer.duplicate(), info) }
    }

    private fun ByteBuffer.toByteArraySafe(): ByteArray {
        val d = duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }
}
