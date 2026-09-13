package jp.minobs.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object ReplayClipMerger {
  fun merge(inputs: List<File>, output: File): Boolean {
    if (inputs.isEmpty()) return false
    if (inputs.size == 1) return runCatching { inputs.first().copyTo(output, overwrite = true); true }.getOrDefault(false)

    return runCatching {
      output.parentFile?.mkdirs()
      val first = MediaExtractor()
      first.setDataSource(inputs.first().absolutePath)
      val formats = mutableListOf<MediaFormat>()
      for (i in 0 until first.trackCount) formats += first.getTrackFormat(i)
      first.release()

      val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val videoFormat = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
      val audioFormat = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
      val videoOut = videoFormat?.let { muxer.addTrack(it) } ?: -1
      val audioOut = audioFormat?.let { muxer.addTrack(it) } ?: -1
      if (videoOut < 0 && audioOut < 0) error("No media tracks")
      muxer.start()

      var baseUs = 0L
      inputs.filter { it.exists() && it.length() > 0L }.forEach { file ->
        val durationUs = fileDurationUs(file)
        writeFile(file, muxer, videoOut, audioOut, baseUs)
        baseUs += durationUs.coerceAtLeast(1L)
      }

      muxer.stop()
      muxer.release()
      output.exists() && output.length() > 0L
    }.getOrElse {
      runCatching { output.delete() }
      false
    }
  }

  private fun writeFile(
    file: File,
    muxer: MediaMuxer,
    videoOut: Int,
    audioOut: Int,
    baseUs: Long
  ) {
    val extractor = MediaExtractor()
    extractor.setDataSource(file.absolutePath)
    for (track in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(track)
      val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
      val outTrack = when {
        mime.startsWith("video/") -> videoOut
        mime.startsWith("audio/") -> audioOut
        else -> -1
      }
      if (outTrack < 0) continue

      extractor.selectTrack(track)
      extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
      val maxInput = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(1_048_576)
      val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(262_144).coerceAtMost(8_388_608))
      val info = android.media.MediaCodec.BufferInfo()
      while (true) {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        info.offset = 0
        info.size = size
        info.presentationTimeUs = baseUs + extractor.sampleTime.coerceAtLeast(0L)
        info.flags = extractor.sampleFlags
        muxer.writeSampleData(outTrack, buffer, info)
        if (!extractor.advance()) break
      }
      extractor.unselectTrack(track)
    }
    extractor.release()
  }

  private fun fileDurationUs(file: File): Long {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      var max = 0L
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
          max = maxOf(max, format.getLong(MediaFormat.KEY_DURATION))
        }
      }
      max
    } finally {
      extractor.release()
    }
  }
}
