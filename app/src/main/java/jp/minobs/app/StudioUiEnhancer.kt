package jp.minobs.app

import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import jp.minobs.app.databinding.ActivityObsStudioBinding

class StudioUiEnhancer(
  private val activity: ObsStudioActivity,
  private val binding: ActivityObsStudioBinding,
  private val workspace: StudioWorkspace,
  private val renderer: StudioRenderer,
  private val onRefresh: () -> Unit,
  private val onRebuild: () -> Unit,
  private val onPreflight: () -> Unit,
  private val onEndpoint: () -> Unit
) {
  private val state = StudioUiState(activity)
  private val density = activity.resources.displayMetrics.density
  private var costLabel: TextView? = null

  fun install() {
    makeResponsive(binding.root)
    installInsets()
    installPropertyTools()
    installToolButtons()
    installDockResize()
    installCollapsiblePanels()
    applyLandscapeTuning()
  }

  fun refreshSelectedSourceCost() {
    val id = workspace.selectedSourceId
    costLabel?.text = if (id == null) "ソース負荷: --" else "ソース負荷: ${renderer.sourceCostLabel(id)}"
  }

  private fun installInsets() {
    val content = (binding.root as? ViewGroup)?.getChildAt(0) ?: return
    val baseBottom = dp(72)
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
      val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
      content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, baseBottom + nav.bottom)
      insets
    }
    ViewCompat.requestApplyInsets(binding.root)
  }

  private fun makeResponsive(view: View) {
    if (view is Button) {
      view.isAllCaps = false
      view.maxLines = 2
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      view.setPadding(dp(5), dp(4), dp(5), dp(4))
      view.minimumHeight = dp(52)
      view.minWidth = 0
    }
    if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL) {
      val lp = view.layoutParams
      if (lp != null && lp.height in dp(38)..dp(52)) {
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = lp
        view.minimumHeight = dp(52)
      }
    }
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) makeResponsive(view.getChildAt(i))
    }
  }

  private fun installPropertyTools() {
    val headerRow = binding.btnUndo.parent as? ViewGroup ?: return
    val panel = headerRow.parent as? LinearLayout ?: return

    val cost = TextView(activity).apply {
      text = "ソース負荷: --"
      textSize = 11f
      setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    costLabel = cost

    val row1 = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    row1.addView(button("数値変形") {
      val source = workspace.source() ?: return@button
      workspace.checkpoint()
      StudioAdvancedDialogs.showTransform(activity, source) {
        workspace.autosave(); renderer.updateTransform(source); binding.transformOverlay.select(source); onRefresh()
      }
    }, weight())
    row1.addView(button("クロップ") {
      val source = workspace.source() ?: return@button
      workspace.checkpoint()
      StudioAdvancedDialogs.showCrop(activity, source) {
        workspace.autosave(); renderer.updateTransform(source); binding.transformOverlay.select(source); onRefresh()
      }
    }, weight())
    row1.addView(button("グループ") {
      val scene = workspace.scene() ?: return@button
      StudioAdvancedDialogs.showGroup(activity, scene, workspace) { workspace.autosave(); onRefresh() }
    }, weight())

    val row2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    row2.addView(button("クロマキー") {
      val source = workspace.source() ?: return@button
      if (source.type !in setOf(StudioSourceType.CAMERA, StudioSourceType.USB_CAPTURE, StudioSourceType.IMAGE, StudioSourceType.MEDIA)) {
        toast("クロマキーはカメラ・USBキャプチャ・画像・動画で使えます")
        return@button
      }
      workspace.checkpoint()
      StudioAdvancedDialogs.showChroma(activity, source) {
        workspace.autosave(); renderer.updateTransform(source); onRefresh()
      }
    }, weight())
    row2.addView(button("グループ解除") {
      val source = workspace.source() ?: return@button
      if (!workspace.ungroup(source.groupId)) toast("このソースはグループ化されていません")
      onRefresh()
    }, weight())
    row2.addView(button("変形リセット") {
      val source = workspace.source() ?: return@button
      workspace.checkpoint()
      source.transform = when (source.type) {
        StudioSourceType.SCREEN -> StudioTransform(0f, 0f, 100f, 100f)
        StudioSourceType.CAMERA, StudioSourceType.USB_CAPTURE, StudioSourceType.EXTERNAL -> StudioTransform(68f, 5f, 28f, 28f)
        StudioSourceType.TEXT, StudioSourceType.CLOCK, StudioSourceType.TIMER, StudioSourceType.CHAT -> StudioTransform(8f, 8f, 42f, 16f)
        StudioSourceType.IMAGE -> StudioTransform(8f, 58f, 28f, 28f)
        else -> StudioTransform(15f, 15f, 70f, 55f)
      }
      workspace.autosave(); renderer.updateTransform(source); binding.transformOverlay.select(source); onRefresh()
    }, weight())

    panel.addView(cost)
    panel.addView(row1)
    panel.addView(row2)
    makeResponsive(row1)
    makeResponsive(row2)
  }

  private fun installToolButtons() {
    val row = binding.btnPreflight.parent as? ViewGroup ?: return
    val panel = row.parent as? LinearLayout ?: return
    val extra = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    extra.addView(button("USB診断") { StudioAdvancedDialogs.showUsbDiagnostics(activity) }, weight())
    extra.addView(button("配信テスト") { onPreflight(); toast("配信前チェックを実行しました") }, weight())
    extra.addView(button("配信先") { onEndpoint() }, weight())
    extra.addView(button("ワークスペース") { showWorkspaceMenu() }, weight())
    panel.addView(extra, panel.childCount.coerceAtLeast(1))
    makeResponsive(extra)
  }

  private fun installDockResize() {
    val scenePanel = binding.sceneList.parent?.parent as? View ?: return
    val sourcePanel = binding.sourceList.parent?.parent as? View ?: return
    val dockRow = scenePanel.parent as? LinearLayout ?: return
    val lp = dockRow.layoutParams
    lp.height = dp(state.dockHeightDp)
    dockRow.layoutParams = lp

    val parent = dockRow.parent as? LinearLayout ?: return
    val handle = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), 0, dp(8), 0)
      setBackgroundColor(0xFF292B30.toInt())
    }
    val title = TextView(activity).apply {
      text = "↕ シーン / ソース ドック高さ"
      textSize = 11f
    }
    val seek = SeekBar(activity).apply {
      max = 290
      progress = (state.dockHeightDp - 130).coerceIn(0, 290)
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          if (!fromUser) return
          val dpHeight = 130 + progress
          state.dockHeightDp = dpHeight
          dockRow.layoutParams = dockRow.layoutParams.apply { height = dp(dpHeight) }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
      })
    }
    handle.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    handle.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1f))
    val index = parent.indexOfChild(dockRow)
    parent.addView(handle, index + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    var startY = 0f
    var startHeight = 0
    handle.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { startY = event.rawY; startHeight = dockRow.height; true }
        MotionEvent.ACTION_MOVE -> {
          val next = (startHeight + (event.rawY - startY).toInt()).coerceIn(dp(130), dp(420))
          dockRow.layoutParams = dockRow.layoutParams.apply { height = next }
          state.dockHeightDp = (next / density).toInt()
          seek.progress = (state.dockHeightDp - 130).coerceIn(0, 290)
          true
        }
        else -> true
      }
    }
  }

  private fun installCollapsiblePanels() {
    val properties = (binding.btnUndo.parent as? View)?.parent as? LinearLayout
    val audio = (binding.btnMicMute.parent as? View)?.parent as? LinearLayout
    val controls = (binding.btnCapture.parent as? View)?.parent as? LinearLayout
    val tools = (binding.btnPreflight.parent as? View)?.parent as? LinearLayout
    attachCollapse(properties, "properties", "ソースのプロパティ")
    attachCollapse(audio, "audio", "音声ミキサー")
    attachCollapse(controls, "controls", "操作")
    attachCollapse(tools, "tools", "ツール / 自動化 / プロジェクト")
  }

  private fun attachCollapse(panel: LinearLayout?, key: String, title: String) {
    if (panel == null) return
    val toggle = Button(activity).apply {
      text = if (state.isCollapsed(key)) "▶ $title" else "▼ $title"
      gravity = Gravity.START or Gravity.CENTER_VERTICAL
      textSize = 12f
      setTypeface(typeface, Typeface.BOLD)
      setOnClickListener {
        val collapsed = state.toggle(key)
        text = if (collapsed) "▶ $title" else "▼ $title"
        setPanelChildrenVisible(panel, this, collapsed)
      }
    }
    panel.addView(toggle, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    setPanelChildrenVisible(panel, toggle, state.isCollapsed(key))
    makeResponsive(toggle)
  }

  private fun setPanelChildrenVisible(panel: LinearLayout, toggle: View, collapsed: Boolean) {
    for (i in 0 until panel.childCount) {
      val child = panel.getChildAt(i)
      if (child !== toggle) child.visibility = if (collapsed) View.GONE else View.VISIBLE
    }
  }

  private fun showWorkspaceMenu() {
    val names = state.workspaceNames()
    val items = arrayListOf("現在の配置を保存").apply { addAll(names) }
    AlertDialog.Builder(activity).setTitle("OBSワークスペース").setItems(items.toTypedArray()) { _, index ->
      if (index == 0) {
        val input = EditText(activity).apply { hint = "例: 配信用"; setSingleLine(true) }
        AlertDialog.Builder(activity).setTitle("ワークスペース名").setView(input)
          .setPositiveButton("保存") { _, _ -> state.saveWorkspace(input.text.toString()); toast("保存しました") }
          .setNegativeButton("キャンセル", null).show()
      } else {
        if (state.loadWorkspace(items[index])) {
          toast("${items[index]} を読み込みました。画面を再表示すると完全に反映されます")
        }
      }
    }.show()
  }

  private fun applyLandscapeTuning() {
    if (activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
    binding.studioPreviewRow.layoutParams = binding.studioPreviewRow.layoutParams.apply { height = dp(300) }
    val scenePanel = binding.sceneList.parent?.parent as? View
    val dockRow = scenePanel?.parent as? View
    if (dockRow != null) dockRow.layoutParams = dockRow.layoutParams.apply { height = dp(state.dockHeightDp.coerceAtMost(260)) }
  }

  private fun button(textValue: String, action: () -> Unit) = Button(activity).apply {
    text = textValue
    isAllCaps = false
    maxLines = 2
    minimumHeight = dp(52)
    textSize = 12f
    setOnClickListener { action() }
  }

  private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
  private fun dp(value: Int) = (value * density + .5f).toInt()
  private fun toast(text: String) = android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
}
