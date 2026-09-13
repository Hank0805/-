package jp.minobs.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object StudioAdvancedDialogs {

  private fun numeric(context: Context, label: String, value: Number): Pair<LinearLayout, EditText> {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val title = TextView(context).apply { text = label; setPadding(0, 8, 12, 0) }
    val input = EditText(context).apply {
      inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
      setText(value.toString())
      selectAll()
    }
    row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .45f))
    row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .55f))
    return row to input
  }

  fun showTransform(context: Context, source: StudioSource, onApply: () -> Unit) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
    val fields = linkedMapOf<String, EditText>()
    listOf(
      "X (%)" to source.transform.x,
      "Y (%)" to source.transform.y,
      "幅 (%)" to source.transform.width,
      "高さ (%)" to source.transform.height,
      "回転 (°)" to source.transform.rotation,
      "不透明度 (%)" to (source.transform.alpha * 100f)
    ).forEach { (label, value) ->
      val (row, input) = numeric(context, label, value)
      fields[label] = input
      root.addView(row)
    }
    AlertDialog.Builder(context)
      .setTitle("変形を数値で編集 — ${source.name}")
      .setView(root)
      .setPositiveButton("適用") { _, _ ->
        source.transform.x = fields.getValue("X (%)").text.toString().toFloatOrNull()?.coerceIn(-100f, 100f) ?: source.transform.x
        source.transform.y = fields.getValue("Y (%)").text.toString().toFloatOrNull()?.coerceIn(-100f, 100f) ?: source.transform.y
        source.transform.width = fields.getValue("幅 (%)").text.toString().toFloatOrNull()?.coerceIn(1f, 100f) ?: source.transform.width
        source.transform.height = fields.getValue("高さ (%)").text.toString().toFloatOrNull()?.coerceIn(1f, 100f) ?: source.transform.height
        source.transform.rotation = fields.getValue("回転 (°)").text.toString().toFloatOrNull()?.toInt()?.rem(360) ?: source.transform.rotation
        source.transform.alpha = ((fields.getValue("不透明度 (%)").text.toString().toFloatOrNull() ?: source.transform.alpha * 100f) / 100f).coerceIn(0f, 1f)
        onApply()
      }
      .setNegativeButton("キャンセル", null)
      .show()
  }

  fun showCrop(context: Context, source: StudioSource, onApply: () -> Unit) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
    val labels = listOf(
      "左 (%)" to source.transform.cropLeft,
      "上 (%)" to source.transform.cropTop,
      "右 (%)" to source.transform.cropRight,
      "下 (%)" to source.transform.cropBottom
    )
    val fields = mutableListOf<EditText>()
    labels.forEach { (label, value) ->
      val (row, input) = numeric(context, label, value)
      fields += input
      root.addView(row)
    }
    root.addView(TextView(context).apply {
      text = "0〜49%で指定。カメラ・USBキャプチャ・画像・動画はGPU上で実際に切り抜かれます。"
      textSize = 12f
    })
    AlertDialog.Builder(context)
      .setTitle("クロップ — ${source.name}")
      .setView(root)
      .setPositiveButton("適用") { _, _ ->
        val v = fields.mapIndexed { index, e ->
          e.text.toString().toFloatOrNull()?.coerceIn(0f, 49f) ?: labels[index].second
        }
        source.transform.cropLeft = v[0]; source.transform.cropTop = v[1]
        source.transform.cropRight = v[2]; source.transform.cropBottom = v[3]
        onApply()
      }
      .setNeutralButton("リセット") { _, _ ->
        source.transform.cropLeft = 0f; source.transform.cropTop = 0f
        source.transform.cropRight = 0f; source.transform.cropBottom = 0f
        onApply()
      }
      .setNegativeButton("キャンセル", null)
      .show()
  }

  fun showChroma(context: Context, source: StudioSource, onApply: () -> Unit) {
    val choices = arrayOf("OFF", "グリーンバック", "ブルーバック", "カスタム（現在色）")
    AlertDialog.Builder(context).setTitle("クロマキー — ${source.name}").setItems(choices) { _, index ->
      when (index) {
        0 -> source.chromaEnabled = false
        1 -> { source.chromaEnabled = true; source.chromaColor = Color.GREEN }
        2 -> { source.chromaEnabled = true; source.chromaColor = Color.BLUE }
        3 -> source.chromaEnabled = true
      }
      if (index == 0) onApply() else showChromaStrength(context, source, onApply)
    }.show()
  }

  private fun showChromaStrength(context: Context, source: StudioSource, onApply: () -> Unit) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
    val (simRow, sim) = numeric(context, "類似度 0〜100", (source.chromaSimilarity * 100).toInt())
    val (smoothRow, smooth) = numeric(context, "滑らかさ 0〜50", (source.chromaSmoothness * 100).toInt())
    root.addView(simRow); root.addView(smoothRow)
    AlertDialog.Builder(context)
      .setTitle("クロマキー調整")
      .setView(root)
      .setPositiveButton("適用") { _, _ ->
        source.chromaSimilarity = ((sim.text.toString().toFloatOrNull() ?: 28f) / 100f).coerceIn(.01f, 1f)
        source.chromaSmoothness = ((smooth.text.toString().toFloatOrNull() ?: 10f) / 100f).coerceIn(.001f, .5f)
        onApply()
      }
      .setNegativeButton("キャンセル", null)
      .show()
  }

  fun showGroup(context: Context, scene: StudioScene, workspace: StudioWorkspace, onApply: () -> Unit) {
    val candidates = scene.sources.filter { it.type != StudioSourceType.SCREEN }
    if (candidates.size < 2) {
      AlertDialog.Builder(context).setMessage("グループ化できるソースが2個以上必要です。")
        .setPositiveButton("OK", null).show()
      return
    }
    val selected = BooleanArray(candidates.size)
    AlertDialog.Builder(context)
      .setTitle("複数選択してグループ化")
      .setMultiChoiceItems(candidates.map { sourceIcon(it.type) + " " + it.name }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
      .setPositiveButton("グループ化") { _, _ ->
        workspace.group(candidates.filterIndexed { index, _ -> selected[index] }.map { it.id }.toSet())
        onApply()
      }
      .setNeutralButton("選択中をグループ解除") { _, _ ->
        workspace.source()?.groupId?.let { workspace.ungroup(it) }
        onApply()
      }
      .setNegativeButton("キャンセル", null)
      .show()
  }

  fun showSceneTransition(context: Context, scene: StudioScene, project: StudioProject, onApply: () -> Unit) {
    val values = StudioTransition.entries
    val labels = arrayOf("プロジェクト設定を使用") + values.map { transitionLabel(it) }.toTypedArray()
    AlertDialog.Builder(context).setTitle("${scene.name} の切り替え").setItems(labels) { _, index ->
      if (index == 0) {
        scene.transitionOverride = null
        scene.transitionMsOverride = null
        onApply()
      } else {
        scene.transitionOverride = values[index - 1]
        val root = EditText(context).apply {
          inputType = InputType.TYPE_CLASS_NUMBER
          setText((scene.transitionMsOverride ?: project.transitionMs).toString())
          selectAll()
        }
        AlertDialog.Builder(context).setTitle("切り替え時間 (ms)").setView(root)
          .setPositiveButton("保存") { _, _ ->
            scene.transitionMsOverride = root.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: project.transitionMs
            onApply()
          }.setNegativeButton("キャンセル", null).show()
      }
    }.show()
  }

  fun showUsbDiagnostics(context: Context) {
    val rows = UvcVideoInput.diagnostics(context)
    val message = if (rows.isEmpty()) "USB機器が見つかりません。キャプチャーボードをUSB-Cへ接続してください。" else rows.joinToString("\n\n") {
      "${it.name}\nVID:${it.vendorId}  PID:${it.productId}  Class:${it.usbClass}\nUSB音声入力: ${if (it.hasUsbAudioInput) "検出" else "未検出"}"
    }
    AlertDialog.Builder(context).setTitle("USBキャプチャ診断").setMessage(message).setPositiveButton("OK", null).show()
  }

  fun sourceIcon(type: StudioSourceType): String = when (type) {
    StudioSourceType.SCREEN -> "📱"
    StudioSourceType.TEXT -> "T"
    StudioSourceType.IMAGE -> "🖼"
    StudioSourceType.CAMERA -> "🎥"
    StudioSourceType.USB_CAPTURE -> "🎮"
    StudioSourceType.BROWSER -> "🌐"
    StudioSourceType.MEDIA -> "▶"
    StudioSourceType.SLIDESHOW -> "▣"
    StudioSourceType.CLOCK -> "🕒"
    StudioSourceType.TIMER -> "⏱"
    StudioSourceType.EXTERNAL -> "📡"
    StudioSourceType.CHAT -> "💬"
  }

  fun transitionLabel(value: StudioTransition): String = when (value) {
    StudioTransition.CUT -> "カット"
    StudioTransition.FADE -> "フェード"
    StudioTransition.SLIDE -> "スライド"
    StudioTransition.ZOOM -> "ズーム"
    StudioTransition.WIPE -> "ワイプ"
    StudioTransition.STINGER -> "スティンガー"
  }

  fun sceneThumbnail(scene: StudioScene, globals: List<StudioSource>): Bitmap {
    val bmp = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.rgb(12, 13, 15))
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f }
    (scene.sources + globals).filter { it.visible }.forEach { source ->
      val t = source.transform
      val l = 240f * t.x / 100f
      val top = 135f * t.y / 100f
      val r = 240f * (t.x + t.width) / 100f
      val b = 135f * (t.y + t.height) / 100f
      fill.color = when (source.type) {
        StudioSourceType.SCREEN -> Color.rgb(35, 47, 65)
        StudioSourceType.CAMERA, StudioSourceType.USB_CAPTURE -> Color.rgb(58, 75, 60)
        StudioSourceType.TEXT, StudioSourceType.CHAT -> Color.rgb(75, 58, 58)
        StudioSourceType.BROWSER -> Color.rgb(58, 65, 82)
        else -> Color.rgb(70, 70, 74)
      }
      canvas.drawRect(l, top, r, b, fill)
      if (source.type != StudioSourceType.SCREEN) canvas.drawRect(l, top, r, b, border)
      canvas.drawText(sourceIcon(source.type), l + 3, (top + 13).coerceAtMost(132f), text)
    }
    return bmp
  }
}
