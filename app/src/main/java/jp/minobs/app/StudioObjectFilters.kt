package jp.minobs.app

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

private fun setStudioUniforms(
  cropLeft: Float,
  cropTop: Float,
  cropRight: Float,
  cropBottom: Float,
  chromaEnabled: Boolean,
  chromaColor: Int,
  chromaSimilarity: Float,
  chromaSmoothness: Float
) {
  val current = IntArray(1)
  GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, current, 0)
  val program = current[0]
  if (program <= 0) return
  GLES20.glUniform4f(
    GLES20.glGetUniformLocation(program, "uCrop"),
    (cropLeft / 100f).coerceIn(0f, .49f),
    (cropTop / 100f).coerceIn(0f, .49f),
    (cropRight / 100f).coerceIn(0f, .49f),
    (cropBottom / 100f).coerceIn(0f, .49f)
  )
  GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaEnabled"), if (chromaEnabled) 1f else 0f)
  val r = ((chromaColor shr 16) and 0xff) / 255f
  val g = ((chromaColor shr 8) and 0xff) / 255f
  val b = (chromaColor and 0xff) / 255f
  GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uKeyColor"), r, g, b)
  GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSimilarity"), chromaSimilarity.coerceIn(.01f, 1f))
  GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSmoothness"), chromaSmoothness.coerceIn(.001f, .5f))
}

class StudioSurfaceFilterRender @JvmOverloads constructor(
  callback: SurfaceFilterRender.SurfaceReadyCallback? = null
) : SurfaceFilterRender(callback) {
  var cropLeft = 0f
  var cropTop = 0f
  var cropRight = 0f
  var cropBottom = 0f
  var chromaEnabled = false
  var chromaColor: Int = 0xFF00FF00.toInt()
  var chromaSimilarity = .28f
  var chromaSmoothness = .10f

  override fun initGlFilter(context: Context) {
    fragment = R.raw.studio_surface_fragment
    super.initGlFilter(context)
  }

  override fun drawFilter() {
    super.drawFilter()
    setStudioUniforms(cropLeft, cropTop, cropRight, cropBottom, chromaEnabled, chromaColor, chromaSimilarity, chromaSmoothness)
  }

  fun applySource(source: StudioSource) {
    val t = source.transform
    setScale(t.width.coerceIn(1f, 100f), t.height.coerceIn(1f, 100f))
    setPosition(t.x.coerceIn(-100f, 100f), t.y.coerceIn(-100f, 100f))
    rotation = t.rotation
    alpha = t.alpha.coerceIn(0f, 1f)
    cropLeft = t.cropLeft
    cropTop = t.cropTop
    cropRight = t.cropRight
    cropBottom = t.cropBottom
    chromaEnabled = source.chromaEnabled
    chromaColor = source.chromaColor
    chromaSimilarity = source.chromaSimilarity
    chromaSmoothness = source.chromaSmoothness
  }
}

class StudioImageFilterRender : ImageFilterRender() {
  var cropLeft = 0f
  var cropTop = 0f
  var cropRight = 0f
  var cropBottom = 0f
  var chromaEnabled = false
  var chromaColor: Int = 0xFF00FF00.toInt()
  var chromaSimilarity = .28f
  var chromaSmoothness = .10f

  override fun initGlFilter(context: Context) {
    fragment = R.raw.studio_image_fragment
    super.initGlFilter(context)
  }

  override fun drawFilter() {
    super.drawFilter()
    setStudioUniforms(cropLeft, cropTop, cropRight, cropBottom, chromaEnabled, chromaColor, chromaSimilarity, chromaSmoothness)
  }

  fun applySource(source: StudioSource) {
    val t = source.transform
    setScale(t.width.coerceIn(1f, 100f), t.height.coerceIn(1f, 100f))
    setPosition(t.x.coerceIn(-100f, 100f), t.y.coerceIn(-100f, 100f))
    rotation = t.rotation
    alpha = t.alpha.coerceIn(0f, 1f)
    cropLeft = t.cropLeft
    cropTop = t.cropTop
    cropRight = t.cropRight
    cropBottom = t.cropBottom
    chromaEnabled = source.chromaEnabled
    chromaColor = source.chromaColor
    chromaSimilarity = source.chromaSimilarity
    chromaSmoothness = source.chromaSmoothness
  }

  fun setStudioImage(bitmap: Bitmap?) = setImage(bitmap)
}
