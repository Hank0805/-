package jp.minobs.app

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scene transition that runs inside the same GL chain used by the encoder.
 * The old program frame is captured once, the new scene is installed below it,
 * and this filter reveals the new scene over time. Therefore the transition is
 * present in recording/RTMP output, not only in the local Activity preview.
 */
class StudioEncodedTransition {
  private val main = Handler(Looper.getMainLooper())
  private val generation = AtomicInteger(0)

  fun run(
    engine: MultiRtmpStream,
    transition: StudioTransition,
    durationMs: Int,
    applyNewScene: () -> Unit,
    onDone: () -> Unit = {}
  ) {
    val id = generation.incrementAndGet()
    if (transition == StudioTransition.CUT || durationMs <= 40) {
      applyNewScene()
      onDone()
      return
    }

    var delivered = false
    val timeout = Runnable {
      if (!delivered && generation.get() == id) {
        delivered = true
        applyNewScene()
        onDone()
      }
    }
    main.postDelayed(timeout, 900)

    runCatching {
      engine.getGlInterface().takePhoto { oldFrame ->
        main.post {
          if (generation.get() != id || delivered) return@post
          delivered = true
          main.removeCallbacks(timeout)
          applyNewScene()
          installOverlay(engine, oldFrame, transition, durationMs.coerceIn(80, 5000), id, onDone)
        }
      }
    }.onFailure {
      main.removeCallbacks(timeout)
      if (generation.get() == id && !delivered) {
        delivered = true
        applyNewScene()
        onDone()
      }
    }
  }

  private fun installOverlay(
    engine: MultiRtmpStream,
    bitmap: Bitmap,
    transition: StudioTransition,
    durationMs: Int,
    id: Int,
    onDone: () -> Unit
  ) {
    val overlay = TransitionImageFilter().apply {
      setImage(bitmap)
      setScale(100f, 100f)
      setPosition(0f, 0f)
      mode = when (transition) {
        StudioTransition.FADE -> 1f
        StudioTransition.SLIDE -> 2f
        StudioTransition.ZOOM -> 3f
        StudioTransition.WIPE -> 4f
        StudioTransition.STINGER -> 5f
        StudioTransition.CUT -> 0f
      }
      progress = 0f
      alpha = 1f
    }
    runCatching { engine.getGlInterface().addFilter(overlay) }
      .onFailure { onDone(); return }

    val start = android.os.SystemClock.uptimeMillis()
    val frame = object : Runnable {
      override fun run() {
        if (generation.get() != id) {
          runCatching { engine.getGlInterface().removeFilter(overlay) }
          return
        }
        val elapsed = android.os.SystemClock.uptimeMillis() - start
        overlay.progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        if (overlay.progress < 1f) {
          main.postDelayed(this, 16)
        } else {
          runCatching { engine.getGlInterface().removeFilter(overlay) }
          onDone()
        }
      }
    }
    main.post(frame)
  }

  private class TransitionImageFilter : ImageFilterRender() {
    @Volatile var progress = 0f
    @Volatile var mode = 1f

    override fun initGlFilter(context: Context) {
      fragment = R.raw.studio_transition_fragment
      super.initGlFilter(context)
    }

    override fun drawFilter() {
      super.drawFilter()
      val current = IntArray(1)
      GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, current, 0)
      val program = current[0]
      if (program <= 0) return
      GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uProgress"), progress.coerceIn(0f, 1f))
      GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMode"), mode)
    }
  }
}
