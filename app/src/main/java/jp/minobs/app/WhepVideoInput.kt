package jp.minobs.app

import android.content.Context
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal WHEP (WebRTC-HTTP Egress Protocol) receiver.
 * A received WebRTC video track is rendered directly into RootEncoder's SurfaceFilterRender,
 * so there is no bitmap copy in the hot path.
 */
class WhepVideoInput(private val context: Context) {
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var renderer: EglRenderer? = null
    private var track: VideoTrack? = null
    private var outputSurface: Surface? = null
    private var filter: SurfaceFilterRender? = null
    private val stopped = AtomicBoolean(true)
    private var onStatus: ((String) -> Unit)? = null

    fun createFilter(
        endpoint: String,
        bearerToken: String? = null,
        onStatus: (String) -> Unit = {}
    ): SurfaceFilterRender {
        stop()
        this.onStatus = onStatus
        stopped.set(false)
        return SurfaceFilterRender { surfaceTexture ->
            if (stopped.get()) return@SurfaceFilterRender
            surfaceTexture.setDefaultBufferSize(1280, 720)
            val surface = Surface(surfaceTexture)
            outputSurface = surface
            start(endpoint.trim(), bearerToken?.trim().orEmpty(), surface)
        }.also { filter = it }
    }

    private fun start(endpoint: String, token: String, surface: Surface) {
        if (!endpoint.startsWith("http://", true) && !endpoint.startsWith("https://", true)) {
            onStatus?.invoke("WHEP URLはhttp(s)://で指定してください")
            return
        }
        Thread({
            try {
                onStatus?.invoke("WHEP: WebRTC初期化中…")
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
                val egl = EglBase.create()
                eglBase = egl
                val f = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                factory = f

                val config = PeerConnection.RTCConfiguration(
                    listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                ).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                val iceComplete = CountDownLatch(1)
                val observer = object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        onStatus?.invoke("WHEP ICE: ${state?.name ?: "?"}")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.countDown()
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) = Unit
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: MediaStream?) = Unit
                    override fun onRemoveStream(stream: MediaStream?) = Unit
                    override fun onDataChannel(dataChannel: DataChannel?) = Unit
                    override fun onRenegotiationNeeded() = Unit
                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        val video = receiver?.track() as? VideoTrack ?: return
                        attachTrack(video, surface)
                    }
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        onStatus?.invoke("WHEP: ${newState?.name ?: "?"}")
                    }
                }
                val pc = f.createPeerConnection(config, observer) ?: error("PeerConnectionの作成に失敗")
                peer = pc
                pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).direction =
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY

                val offerLatch = CountDownLatch(1)
                var offer: SessionDescription? = null
                var offerError: String? = null
                pc.createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) { offer = sdp; offerLatch.countDown() }
                    override fun onCreateFailure(error: String?) { offerError = error; offerLatch.countDown() }
                }, MediaConstraints())
                if (!offerLatch.await(5, TimeUnit.SECONDS)) error("WebRTC offer timeout")
                val created = offer ?: error(offerError ?: "WebRTC offer作成失敗")

                val localLatch = CountDownLatch(1)
                var localError: String? = null
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() { localLatch.countDown() }
                    override fun onSetFailure(error: String?) { localError = error; localLatch.countDown() }
                }, created)
                if (!localLatch.await(5, TimeUnit.SECONDS)) error("Local SDP timeout")
                localError?.let { error(it) }
                iceComplete.await(1800, TimeUnit.MILLISECONDS)
                val localSdp = pc.localDescription?.description ?: created.description

                onStatus?.invoke("WHEP: SDP送信中…")
                val answerSdp = postOffer(endpoint, token, localSdp)
                val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                val answerLatch = CountDownLatch(1)
                var answerError: String? = null
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() { answerLatch.countDown() }
                    override fun onSetFailure(error: String?) { answerError = error; answerLatch.countDown() }
                }, answer)
                if (!answerLatch.await(5, TimeUnit.SECONDS)) error("Remote SDP timeout")
                answerError?.let { error(it) }
                onStatus?.invoke("WHEP: 接続待機中")
            } catch (e: Exception) {
                if (!stopped.get()) onStatus?.invoke("WHEPエラー: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "MiniOBS-WHEP").start()
    }

    private fun attachTrack(video: VideoTrack, surface: Surface) {
        if (stopped.get()) return
        synchronized(this) {
            track?.removeSink(renderer)
            renderer?.release()
            val egl = eglBase ?: return
            val r = EglRenderer("MiniOBS-WHEP-Renderer")
            r.init(egl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
            r.createEglSurface(surface)
            video.addSink(r)
            track = video
            renderer = r
            onStatus?.invoke("WHEP: 映像受信中")
        }
    }

    private fun postOffer(endpoint: String, token: String, sdp: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/sdp")
        connection.setRequestProperty("Accept", "application/sdp")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("WHEP HTTP $code ${body.take(160)}")
        if (body.isBlank()) error("WHEP answer SDPが空です")
        return body
    }

    fun stop() {
        stopped.set(true)
        synchronized(this) {
            track?.removeSink(renderer)
            track = null
            runCatching { renderer?.release() }; renderer = null
            runCatching { peer?.close() }; runCatching { peer?.dispose() }; peer = null
            runCatching { factory?.dispose() }; factory = null
            runCatching { eglBase?.release() }; eglBase = null
            runCatching { outputSurface?.release() }; outputSurface = null
            filter = null
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
