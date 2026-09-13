package jp.minobs.app

import com.pedro.encoder.input.audio.CustomAudioEffect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Lightweight PCM16 studio chain: gate -> 3 band tone -> compressor -> limiter.
 * It also keeps a small PCM ring so the remote controller can audition recent audio.
 */
class StudioAudioEffect : CustomAudioEffect() {
    @Volatile var enabled = true
    @Volatile var gateEnabled = true
    @Volatile var compressorEnabled = true
    @Volatile var limiterEnabled = true
    @Volatile var gateThreshold = 500
    @Volatile var compressorThreshold = 12000
    @Volatile var compressorRatio = 3.5f
    @Volatile var outputGain = 1.0f
    @Volatile var lowGain = 1.0f
    @Volatile var midGain = 1.0f
    @Volatile var highGain = 1.0f

    private val lowState = floatArrayOf(0f, 0f)
    private val highLowState = floatArrayOf(0f, 0f)
    private val monitorLock = ReentrantLock()
    private val monitor = ArrayDeque<ByteArray>()
    private var monitorBytes = 0
    private val maxMonitorBytes = 44_100 * 2 * 2 * 4 // about 4 sec stereo PCM16

    override fun process(pcmBuffer: ByteArray): ByteArray {
        if (pcmBuffer.size < 2) return pcmBuffer
        val out = pcmBuffer.copyOf()
        if (enabled) processPcm16(out)
        pushMonitor(out)
        return out
    }

    private fun processPcm16(data: ByteArray) {
        var i = 0
        var frame = 0
        while (i + 1 < data.size) {
            val channel = frame and 1
            var sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt().toFloat()

            if (gateEnabled && abs(sample) < gateThreshold) sample *= 0.12f

            // Cheap, stable three-band tone split. Designed for real-time phone use, not mastering.
            lowState[channel] += 0.055f * (sample - lowState[channel])
            highLowState[channel] += 0.30f * (sample - highLowState[channel])
            val low = lowState[channel]
            val high = sample - highLowState[channel]
            val mid = sample - low - high
            sample = (low * lowGain + mid * midGain + high * highGain) * outputGain

            if (compressorEnabled) {
                val a = abs(sample)
                if (a > compressorThreshold) {
                    val sign = if (sample < 0f) -1f else 1f
                    sample = sign * (compressorThreshold + (a - compressorThreshold) / compressorRatio)
                }
            }
            if (limiterEnabled) sample = sample.coerceIn(-30000f, 30000f)

            val s = sample.toInt().coerceIn(-32768, 32767).toShort().toInt()
            data[i] = (s and 0xff).toByte()
            data[i + 1] = ((s ushr 8) and 0xff).toByte()
            i += 2
            frame++
        }
    }

    fun applyPreset(name: String) {
        when (name.lowercase()) {
            "voice" -> { gateThreshold = 650; compressorThreshold = 10500; compressorRatio = 4f; lowGain = 0.90f; midGain = 1.12f; highGain = 1.08f; outputGain = 1.05f }
            "game" -> { gateThreshold = 500; compressorThreshold = 12500; compressorRatio = 3f; lowGain = 1.0f; midGain = 1.04f; highGain = 1.04f; outputGain = 1f }
            "broadcast" -> { gateThreshold = 600; compressorThreshold = 9500; compressorRatio = 4.5f; lowGain = 1.04f; midGain = 1.10f; highGain = 1.06f; outputGain = 1.08f }
            else -> { gateThreshold = 400; compressorThreshold = 14000; compressorRatio = 2.5f; lowGain = 1f; midGain = 1f; highGain = 1f; outputGain = 1f }
        }
    }

    private fun pushMonitor(bytes: ByteArray) = monitorLock.withLock {
        monitor.addLast(bytes.copyOf())
        monitorBytes += bytes.size
        while (monitorBytes > maxMonitorBytes && monitor.isNotEmpty()) monitorBytes -= monitor.removeFirst().size
    }

    /** Returns a normal PCM WAV containing the newest audio. */
    fun monitorWav(maxSeconds: Int = 3): ByteArray {
        val pcm = monitorLock.withLock {
            val target = 44_100 * 2 * 2 * maxSeconds.coerceIn(1, 4)
            val chunks = ArrayDeque<ByteArray>()
            var size = 0
            val reversed = monitor.toList().asReversed()
            for (chunk in reversed) {
                chunks.addFirst(chunk)
                size += chunk.size
                if (size >= target) break
            }
            val out = ByteArrayOutputStream(size)
            chunks.forEach { out.write(it) }
            out.toByteArray().let { if (it.size > target) it.copyOfRange(it.size - target, it.size) else it }
        }
        return wavHeader(pcm.size) + pcm
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataSize); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2)
        b.putInt(44_100); b.putInt(44_100 * 2 * 2); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataSize)
        return b.array()
    }
}
