package jp.minobs.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the raw studio buses while the normal RootEncoder recorder writes the
 * program MP4. When recording stops the PCM buses are encoded to AAC and then
 * muxed into one MP4 together with the original program video + mixed audio.
 *
 * Result track layout:
 *   video, MIX audio, microphone, internal/game audio, optional USB/HDMI audio.
 */
class StudioMultiTrackRecorder(private val workRoot: File) {

  data class Result(
    val file: File,
    val audioTracks: Int,
    val hadUsb: Boolean
  )

  private var sessionDir: File? = null
  private var micOut: FileOutputStream? = null
  private var internalOut: FileOutputStream? = null
  private var usbOut: FileOutputStream? = null
  private val active = AtomicBoolean(false)
  private var micSignal = false
  private var internalSignal = false
  private var usbSignal = false

  @Synchronized
  fun start(): Boolean {
    stopRawCapture()
    val dir = File(workRoot, "multitrack_${System.currentTimeMillis()}")
    if (!dir.mkdirs() && !dir.isDirectory) return false
    return try {
      sessionDir = dir
      micOut = FileOutputStream(File(dir, "mic.pcm"))
      internalOut = FileOutputStream(File(dir, "internal.pcm"))
      usbOut = FileOutputStream(File(dir, "usb.pcm"))
      micSignal = false
      internalSignal = false
      usbSignal = false
      active.set(true)
      true
    } catch (_: Throwable) {
      closeOutputs()
      dir.deleteRecursively()
      sessionDir = null
      false
    }
  }

  @Synchronized
  fun write(mic: ByteArray, internal: ByteArray, usb: ByteArray, timestampUs: Long) {
    if (!active.get()) return
    runCatching {
      micOut?.write(mic)
      internalOut?.write(internal)
      usbOut?.write(usb)
      if (!micSignal) micSignal = hasSignal(mic)
      if (!internalSignal) internalSignal = hasSignal(internal)
      if (!usbSignal) usbSignal = hasSignal(usb)
    }
  }

  @Synchronized
  fun stopRawCapture(): File? {
    if (!active.getAndSet(false)) return sessionDir
    closeOutputs()
    return sessionDir
  }

  fun finalizeRecording(
    programMp4: File,
    destination: File,
    sampleRate: Int = 44_100,
    channels: Int = 2,
    bitrate: Int = 128_000
  ): Result? {
    val dir = stopRawCapture() ?: return null
    if (!programMp4.exists() || programMp4.length() <= 0L) return null

    val audioFiles = mutableListOf<File>()
    fun encode(name: String, enabled: Boolean): File? {
      if (!enabled) return null
      val pcm = File(dir, "$name.pcm")
      if (!pcm.exists() || pcm.length() <= 0L) return null
      val out = File(dir, "$name.m4a")
      return if (encodePcmToM4a(pcm, out, sampleRate, channels, bitrate)) out else null
    }

    encode("mic", micSignal)?.let(audioFiles::add)
    encode("internal", internalSignal)?.let(audioFiles::add)
    encode("usb", usbSignal)?.let(audioFiles::add)

    val ok = muxTracks(programMp4, audioFiles, destination)
    val result = if (ok) Result(destination, 1 + audioFiles.size, usbSignal) else null
    runCatching { dir.deleteRecursively() }
    return result
  }

  fun cancel() {
    val dir = stopRawCapture()
    runCatching { dir?.deleteRecursively() }
    sessionDir = null
  }

  @Synchronized
  private fun closeOutputs() {
    runCatching { micOut?.flush() }; runCatching { micOut?.close() }
    runCatching { internalOut?.flush() }; runCatching { internalOut?.close() }
    runCatching { usbOut?.flush() }; runCatching { usbOut?.close() }
    micOut = null; internalOut = null; usbOut = null
  }

  private fun hasSignal(bytes: ByteArray): Boolean {
    var i = 0
    while (i + 1 < bytes.size) {
      val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
      if (sample > 64 || sample < -64) return true
      i += 2
    }
    return false
  }

  private fun encodePcmToM4a(
    pcm: File,
    output: File,
    sampleRate: Int,
    channels: Int,
    bitrate: Int
  ): Boolean {
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var input: FileInputStream? = null
    return try {
      val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
      }
      codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      input = FileInputStream(pcm)

      val info = MediaCodec.BufferInfo()
      val chunk = ByteArray(16 * 1024)
      val bytesPerFrame = channels * 2L
      var bytesSubmitted = 0L
      var inputEos = false
      var outputEos = false
      var muxerStarted = false
      var trackIndex = -1

      while (!outputEos) {
        if (!inputEos) {
          val index = codec.dequeueInputBuffer(10_000)
          if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: error("AAC input buffer unavailable")
            buffer.clear()
            val count = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
            val ptsUs = (bytesSubmitted / bytesPerFrame) * 1_000_000L / sampleRate
            if (count < 0) {
              codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputEos = true
            } else {
              buffer.put(chunk, 0, count)
              codec.queueInputBuffer(index, 0, count, ptsUs, 0)
              bytesSubmitted += count
            }
          }
        }

        when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (!muxerStarted) {
              trackIndex = muxer.addTrack(codec.outputFormat)
              muxer.start()
              muxerStarted = true
            }
          }
          MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
          else -> if (outIndex >= 0) {
            val out = codec.getOutputBuffer(outIndex)
            if (out != null && info.size > 0 && muxerStarted) {
              if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                muxer.writeSampleData(trackIndex, out, info)
              }
            }
            outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            codec.releaseOutputBuffer(outIndex, false)
          }
        }
      }
      true
    } catch (_: Throwable) {
      runCatching { output.delete() }
      false
    } finally {
      runCatching { input?.close() }
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { muxer?.stop() }
      runCatching { muxer?.release() }
    }
  }

  private data class TrackSource(val file: File, val track: Int, val format: MediaFormat)

  private fun muxTracks(program: File, extraAudio: List<File>, destination: File): Boolean {
    var muxer: MediaMuxer? = null
    return try {
      val sources = mutableListOf<TrackSource>()
      fun collect(file: File, includeVideo: Boolean, includeAudio: Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        for (i in 0 until ex.trackCount) {
          val f = ex.getTrackFormat(i)
          val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
          if ((includeVideo && mime.startsWith("video/")) || (includeAudio && mime.startsWith("audio/"))) {
            sources += TrackSource(file, i, f)
          }
        }
        ex.release()
      }
      collect(program, includeVideo = true, includeAudio = true)
      extraAudio.forEach { collect(it, includeVideo = false, includeAudio = true) }
      if (sources.none { it.format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }) return false

      destination.parentFile?.mkdirs()
      if (destination.exists()) destination.delete()
      muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val outTracks = sources.map { muxer.addTrack(it.format) }
      muxer.start()

      val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
      val info = MediaCodec.BufferInfo()
      sources.forEachIndexed { sourceIndex, source ->
        val ex = MediaExtractor()
        try {
          ex.setDataSource(source.file.absolutePath)
          ex.selectTrack(source.track)
          ex.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
          while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime.coerceAtLeast(0L)
            info.flags = ex.sampleFlags
            muxer.writeSampleData(outTracks[sourceIndex], buffer, info)
            ex.advance()
          }
        } finally {
          ex.release()
        }
      }
      true
    } catch (_: Throwable) {
      runCatching { destination.delete() }
      false
    } finally {
      runCatching { muxer?.stop() }
      runCatching { muxer?.release() }
    }
  }
}
