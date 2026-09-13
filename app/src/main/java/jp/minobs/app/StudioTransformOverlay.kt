package jp.minobs.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

class StudioTransformOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 190, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
  private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 70, 70); strokeWidth = 2f }
  private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
  private val safe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
  private var source: StudioSource? = null
  var showSafeArea: Boolean = true; set(value) { field = value; invalidate() }
  var onTransformChanged: ((StudioSource) -> Unit)? = null
  var onCheckpoint: (() -> Unit)? = null
  private var lastX = 0f; private var lastY = 0f
  private var startDist = 0f; private var startAngle = 0f; private var startW = 0f; private var startH = 0f; private var startRot = 0
  private var mode = 0
  private var lastTap = 0L

  fun select(s: StudioSource?) { source = s; invalidate() }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (showSafeArea) {
      canvas.drawRect(width * .05f, height * .05f, width * .95f, height * .95f, safe)
      canvas.drawRect(width * .10f, height * .10f, width * .90f, height * .90f, safe)
    }
    val s = source ?: return
    if (s.type == StudioSourceType.SCREEN) return
    val r = rect(s.transform)
    canvas.save(); canvas.rotate(s.transform.rotation.toFloat(), r.centerX(), r.centerY())
    canvas.drawRect(r, border)
    val hr = 10f
    listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { canvas.drawCircle(it.first, it.second, hr, handle) }
    canvas.drawCircle(r.centerX(), r.top - 28f, hr, handle)
    canvas.restore()
    if (kotlin.math.abs(s.transform.x + s.transform.width / 2f - 50f) < 1f) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guide)
    if (kotlin.math.abs(s.transform.y + s.transform.height / 2f - 50f) < 1f) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guide)
  }

  override fun onTouchEvent(e: MotionEvent): Boolean {
    val s = source ?: return false
    if (s.locked || s.type == StudioSourceType.SCREEN) return false
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val now = System.currentTimeMillis()
        if (now - lastTap < 280) { onCheckpoint?.invoke(); s.transform = StudioTransform(5f, 5f, 90f, 90f, s.transform.rotation, s.transform.alpha); snap(s.transform); changed(s); lastTap = 0; return true }
        lastTap = now; onCheckpoint?.invoke(); lastX = e.x; lastY = e.y; mode = 1; parent?.requestDisallowInterceptTouchEvent(true); return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) {
        startDist = distance(e); startAngle = angle(e); startW = s.transform.width; startH = s.transform.height; startRot = s.transform.rotation; mode = 2; return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (mode == 2 && e.pointerCount >= 2) {
          val factor = if (startDist > 0f) distance(e) / startDist else 1f
          s.transform.width = (startW * factor).coerceIn(3f, 100f); s.transform.height = (startH * factor).coerceIn(3f, 100f)
          s.transform.rotation = (startRot + Math.toDegrees((angle(e) - startAngle).toDouble()).roundToInt()) % 360
        } else {
          s.transform.x += (e.x - lastX) * 100f / width.coerceAtLeast(1); s.transform.y += (e.y - lastY) * 100f / height.coerceAtLeast(1)
          lastX = e.x; lastY = e.y; snap(s.transform)
        }
        changed(s); return true
      }
      MotionEvent.ACTION_POINTER_UP -> { mode = 1; lastX = e.x; lastY = e.y; return true }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; parent?.requestDisallowInterceptTouchEvent(false); return true }
    }
    return true
  }

  private fun snap(t: StudioTransform) {
    t.x = t.x.coerceIn(-95f, 95f); t.y = t.y.coerceIn(-95f, 95f)
    val cx = t.x + t.width / 2f; val cy = t.y + t.height / 2f
    if (kotlin.math.abs(cx - 50f) < 1.8f) t.x = 50f - t.width / 2f
    if (kotlin.math.abs(cy - 50f) < 1.8f) t.y = 50f - t.height / 2f
    if (kotlin.math.abs(t.x) < 1.4f) t.x = 0f; if (kotlin.math.abs(t.y) < 1.4f) t.y = 0f
    if (kotlin.math.abs((t.x + t.width) - 100f) < 1.4f) t.x = 100f - t.width
    if (kotlin.math.abs((t.y + t.height) - 100f) < 1.4f) t.y = 100f - t.height
  }

  private fun changed(s: StudioSource) { onTransformChanged?.invoke(s); invalidate() }
  private fun rect(t: StudioTransform) = RectF(width * t.x / 100f, height * t.y / 100f, width * (t.x + t.width) / 100f, height * (t.y + t.height) / 100f)
  private fun distance(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
  private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
}
