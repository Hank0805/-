package jp.minobs.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class StudioMixAudioSource(
  private val context: Context,
  mediaProjection: MediaProjection,
  private val effect: StudioAudioEffect
) : AudioSource() {

  enum class MonitorMode { OFF, MIX, MICROPHONE, INTERNAL, USB }

  private val mic = MicrophoneSource(MediaRecorder.AudioSource.DEFAULT)
  private val internal = InternalAudioSource(mediaProjection)
  private var usb: MicrophoneSource? = null

  private val micQueue = ArrayBlockingQueue<ByteArray>(12)
  private val internalQueue = ArrayBlockingQueue<ByteArray>(12)
  private val usbQueue = ArrayBlockingQueue<ByteArray>(12)
  private val running = AtomicBoolean(false)
  private var mixerThread: Thread? = null
  private var monitorTrack: AudioTrack? = null

  private val micDelay = PcmDelayLine()
  private val internalDelay = PcmDelayLine()
  private val usbDelay = PcmDelayLine()

  @Volatile var microphoneVolume = 1f
  @Volatile var internalVolume = 1f
  @Volatile var usbVolume = 1f
  @Volatile var microphoneMuted = false
  @Volatile var internalMuted = false
  @Volatile var usbMuted = false
  @Volatile var microphoneDelayMs = 0
  @Volatile var internalDelayMs = 0
  @Volatile var usbDelayMs = 0
  @Volatile var monitorMode = MonitorMode.OFF
  @Volatile var monitorVolume = .65f

  var onRawTracks: ((mic: ByteArray, internal: ByteArray, usb: ByteArray, timestampUs: Long) -> Unit)? = null

  override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
    val micOk = runCatching { mic.init(sampleRate, isStereo, echoCanceler, noiseSuppressor) }.getOrDefault(false)
    val internalOk = runCatching { internal.init(sampleRate, isStereo, false, false) }.getOrDefault(false)
    prepareUsb(sampleRate, isStereo, echoCanceler, noiseSuppressor)
    return micOk && internalOk
  }

  private fun prepareUsb(sampleRate: Int, stereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val device = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
      it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    } ?: run { usb = null; return }
    val source = MicrophoneSource(MediaRecorder.AudioSource.DEFAULT)
    runCatching { source.setPreferredDevice(device) }
    val ok = runCatching { source.init(sampleRate, stereo, echoCanceler, noiseSuppressor) }.getOrDefault(false)
    usb = source.takeIf { ok }
  }

  override fun start(getMicrophoneData: GetMicrophoneData) {
    this.getMicrophoneData = getMicrophoneData
    if (!running.compareAndSet(false, true)) return
    micQueue.clear(); internalQueue.clear(); usbQueue.clear()

    mic.start(object : GetMicrophoneData {
      override fun inputPCMData(frame: Frame) = offerLatest(micQueue, frame)
    })
    internal.start(object : GetMicrophoneData {
      override fun inputPCMData(frame: Frame) = offerLatest(internalQueue, frame)
    })
    usb?.let { source ->
      runCatching {
        source.start(object : GetMicrophoneData {
          override fun inputPCMData(frame: Frame) = offerLatest(usbQueue, frame)
        })
      }.onFailure { usb = null }
    }

    mixerThread = Thread({ mixLoop() }, "MiniOBS-AudioMixer").apply { start() }
  }

  private fun offerLatest(queue: ArrayBlockingQueue<ByteArray>, frame: Frame) {
    val copy = frame.buffer.copyOfRange(frame.offset, (frame.offset + frame.size).coerceAtMost(frame.buffer.size))
    if (!queue.offer(copy)) {
      queue.poll()
      queue.offer(copy)
    }
  }

  private fun latest(queue: ArrayBlockingQueue<ByteArray>, fallbackSize: Int): ByteArray {
    var latest = queue.poll()
    while (true) {
      val next = queue.poll() ?: break
      latest = next
    }
    return latest ?: ByteArray(fallbackSize)
  }

  private fun mixLoop() {
    while (running.get()) {
      val internalBase = internalQueue.poll(80, TimeUnit.MILLISECONDS)
      val micBase = if (internalBase == null) micQueue.poll(20, TimeUnit.MILLISECONDS) else null
      val base = internalBase ?: micBase ?: continue
      val targetSize = (base.size and -2).coerceAtLeast(2)
      val rawInternal = if (internalBase != null) normalize(internalBase, targetSize) else latest(internalQueue, targetSize)
      val rawMic = if (micBase != null) normalize(micBase, targetSize) else latest(micQueue, targetSize)
      val rawUsb = latest(usbQueue, targetSize)
      val channels = if (isStereo) 2 else 1

      val delayedMic = micDelay.process(rawMic, microphoneDelayMs, sampleRate, channels)
      val delayedInternal = internalDelay.process(rawInternal, internalDelayMs, sampleRate, channels)
      val delayedUsb = usbDelay.process(rawUsb, usbDelayMs, sampleRate, channels)
      val mixed = mixPcm16(delayedMic, delayedInternal, delayedUsb)
      val processed = effect.process(mixed)
      val timestamp = TimeUtils.getCurrentTimeMicro()

      onRawTracks?.invoke(rawMic.copyOf(), rawInternal.copyOf(), rawUsb.copyOf(), timestamp)
      writeMonitor(processed, delayedMic, delayedInternal, delayedUsb)
      getMicrophoneData?.inputPCMData(Frame(processed, 0, processed.size, timestamp))
    }
  }

  private fun normalize(input: ByteArray, size: Int): ByteArray =
    if (input.size == size) input else ByteArray(size).also { input.copyInto(it, 0, 0, minOf(input.size, size)) }

  private fun mixPcm16(micBytes: ByteArray, internalBytes: ByteArray, usbBytes: ByteArray): ByteArray {
    val size = minOf(micBytes.size, internalBytes.size, usbBytes.size).coerceAtLeast(0)
    val out = ByteArray(size)
    var i = 0
    while (i + 1 < size) {
      val m = pcm16(micBytes, i) * (if (microphoneMuted) 0f else microphoneVolume)
      val g = pcm16(internalBytes, i) * (if (internalMuted) 0f else internalVolume)
      val u = pcm16(usbBytes, i) * (if (usbMuted) 0f else usbVolume)
      val value = (m + g + u).toInt().coerceIn(-32768, 32767)
      out[i] = (value and 0xff).toByte()
      out[i + 1] = ((value ushr 8) and 0xff).toByte()
      i += 2
    }
    return out
  }

  private fun pcm16(bytes: ByteArray, index: Int): Int =
    ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()

  private fun writeMonitor(mix: ByteArray, mic: ByteArray, internal: ByteArray, usb: ByteArray) {
    val mode = monitorMode
    if (mode == MonitorMode.OFF) {
      releaseMonitor()
      return
    }
    val track = ensureMonitor() ?: return
    val source = when (mode) {
      MonitorMode.MIX -> mix
      MonitorMode.MICROPHONE -> mic
      MonitorMode.INTERNAL -> internal
      MonitorMode.USB -> usb
      StudioMixAudioSource.MonitorMode.OFF -> return
    }
    runCatching {
      track.setVolume(monitorVolume.coerceIn(0f, 1f))
      track.write(source, 0, source.size, AudioTrack.WRITE_NON_BLOCKING)
    }
  }

  private fun ensureMonitor(): AudioTrack? {
    monitorTrack?.let { return it }
    return runCatching {
      val channelMask = if (isStereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
      val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
      AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelMask).build())
        .setBufferSizeInBytes(max(min, 16384))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build().also { it.play(); monitorTrack = it }
    }.getOrNull()
  }

  private fun releaseMonitor() {
    monitorTrack?.let { track ->
      runCatching { track.stop() }
      runCatching { track.release() }
    }
    monitorTrack = null
  }

  override fun stop() {
    if (!running.compareAndSet(true, false)) return
    mixerThread?.interrupt(); mixerThread = null
    runCatching { mic.stop() }
    runCatching { internal.stop() }
    runCatching { usb?.stop() }
    releaseMonitor()
    getMicrophoneData = null
  }

  override fun isRunning(): Boolean = running.get()

  override fun release() {
    stop()
    runCatching { mic.release() }
    runCatching { internal.release() }
    runCatching { usb?.release() }
  }

  fun hasUsbAudio(): Boolean = usb != null

  private class PcmDelayLine {
    private var ring = ByteArray(0)
    private var index = 0

    fun process(input: ByteArray, delayMs: Int, sampleRate: Int, channels: Int): ByteArray {
      var delayBytes = ((delayMs.coerceIn(0, 2000) / 1000.0) * sampleRate * channels * 2).toInt()
      delayBytes -= delayBytes % 2
      if (delayBytes <= 0) {
        ring = ByteArray(0); index = 0
        return input
      }
      if (ring.size != delayBytes) {
        ring = ByteArray(delayBytes)
        index = 0
      }
      val out = ByteArray(input.size)
      for (i in input.indices) {
        out[i] = ring[index]
        ring[index] = input[i]
        index++
        if (index >= ring.size) index = 0
      }
      return out
    }
  }
}
