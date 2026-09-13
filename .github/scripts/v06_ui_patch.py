from pathlib import Path
import re

p = Path("app/src/main/java/jp/minobs/app/ObsStudioActivity.kt")
s = p.read_text()

needle = "  private lateinit var automation: StudioAutomation\n"
if "private lateinit var uiEnhancer: StudioUiEnhancer" not in s:
    s = s.replace(needle, needle + "  private lateinit var uiEnhancer: StudioUiEnhancer\n")

old = '''    binding.programSurface.holder.addCallback(this)
    setupUi()
    ensureService()
    refreshAll()
'''
new = '''    binding.programSurface.holder.addCallback(this)
    setupUi()
    uiEnhancer = StudioUiEnhancer(
      activity = this,
      binding = binding,
      workspace = workspace,
      renderer = renderer,
      onRefresh = ::refreshAll,
      onRebuild = ::rebuildProgram,
      onPreflight = ::showPreflight,
      onEndpoint = ::showEndpointDialog
    )
    uiEnhancer.install()
    ensureService()
    refreshAll()
'''
if old in s:
    s = s.replace(old, new)
elif "uiEnhancer.install()" not in s:
    raise SystemExit("onCreate hook not found")

new_add = '''  private fun showAddSource() {
    val labels = arrayOf(
      "テキスト", "画像", "カメラ", "USBキャプチャ", "ブラウザ", "メディア / 動画",
      "スライドショー", "時計", "タイマー", "リモートカメラ", "チャット表示", "タイトルカード"
    )
    AlertDialog.Builder(this).setTitle("ソースを追加").setItems(labels) { _, index ->
      when (index) {
        0 -> dialogs.text("テキスト") {
          workspace.addSource(StudioSourceType.TEXT, "テキスト", it); refreshSources(); rebuildProgram()
        }
        1 -> {
          val source = workspace.addSource(StudioSourceType.IMAGE, "画像")
          pendingImageId = source.id
          imageLauncher.launch(arrayOf("image/*"))
          refreshSources()
        }
        2 -> {
          workspace.addSource(StudioSourceType.CAMERA, "カメラ")
          requestCamera()
          refreshSources(); rebuildProgram()
        }
        3 -> {
          workspace.addSource(StudioSourceType.USB_CAPTURE, "USBキャプチャ", "usb:")
          refreshSources(); rebuildProgram()
          toast("UVC対応キャプチャーボードをUSB-Cへ接続してください")
        }
        4 -> dialogs.text("ブラウザURL") {
          val source = workspace.addSource(StudioSourceType.BROWSER, "ブラウザ", it)
          refreshSources(); dynamic.startBrowser(source)
        }
        5 -> {
          val source = workspace.addSource(StudioSourceType.MEDIA, "メディア")
          pendingMediaId = source.id
          mediaLauncher.launch(arrayOf("video/*", "audio/*"))
          refreshSources()
        }
        6 -> {
          val source = workspace.addSource(StudioSourceType.SLIDESHOW, "スライドショー")
          pendingSlideId = source.id
          slidesLauncher.launch(arrayOf("image/*"))
          refreshSources()
        }
        7 -> {
          workspace.addSource(StudioSourceType.CLOCK, "時計", "HH:mm:ss")
          refreshSources(); rebuildProgram()
        }
        8 -> {
          workspace.addSource(StudioSourceType.TIMER, "タイマー", "${System.currentTimeMillis()}|0")
          refreshSources(); rebuildProgram()
        }
        9 -> {
          workspace.addSource(StudioSourceType.EXTERNAL, "リモートカメラ", "remote:1")
          refreshSources(); toast("カメラノード / リモートスタジオから接続してください")
        }
        10 -> {
          workspace.addSource(StudioSourceType.CHAT, "チャット", "チャット表示")
          refreshSources(); rebuildProgram()
        }
        11 -> dialogs.text("タイトル") {
          val source = workspace.addSource(StudioSourceType.TEXT, "タイトルカード", it)
          source.transform = StudioTransform(12f, 38f, 76f, 20f)
          refreshSources(); rebuildProgram()
        }
      }
    }.show()
  }

'''
s, n = re.subn(r'  private fun showAddSource\(\) \{.*?\n  \}\n\n(?=  private fun refreshAll)', new_add, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"showAddSource replace failed {n}")

new_scenes = '''  private fun refreshScenes() {
    binding.sceneList.removeAllViews()
    val density = resources.displayMetrics.density
    workspace.project.scenes.forEach { scene ->
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }
      val thumb = android.widget.ImageView(this).apply {
        setImageBitmap(StudioAdvancedDialogs.sceneThumbnail(scene, workspace.project.globalSources))
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        setPadding(2, 2, 4, 2)
      }
      val button = Button(this).apply {
        text = buildString {
          if (workspace.project.programSceneId == scene.id) append("● ")
          if (workspace.project.previewSceneId == scene.id) append("▶ ")
          append(scene.name)
        }
        setAllCaps(false)
        maxLines = 2
        setOnClickListener {
          workspace.selectedSceneId = scene.id
          if (workspace.project.studioMode) workspace.project.previewSceneId = scene.id
          else {
            workspace.project.programSceneId = scene.id
            rebuildProgram()
          }
          workspace.autosave()
          refreshAll()
        }
        setOnLongClickListener {
          StudioAdvancedDialogs.showSceneTransition(this@ObsStudioActivity, scene, workspace.project) {
            workspace.autosave(); refreshScenes()
          }
          true
        }
      }
      row.addView(thumb, LinearLayout.LayoutParams((76 * density).toInt(), (44 * density).toInt()))
      row.addView(button, LinearLayout.LayoutParams(0, (52 * density).toInt(), 1f))
      binding.sceneList.addView(row)
    }
  }

'''
s, n = re.subn(r'  private fun refreshScenes\(\) \{.*?\n  \}\n\n(?=  private fun refreshSources)', new_scenes, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"refreshScenes replace failed {n}")

new_sources = '''  private fun refreshSources() {
    binding.sourceList.removeAllViews()
    val scene = workspace.scene() ?: return
    val list = mutableListOf<StudioSource>().apply {
      addAll(scene.sources)
      addAll(workspace.project.globalSources)
    }
    list.asReversed().forEach { source ->
      val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
      val eye = Button(this).apply {
        text = if (source.visible) "👁" else "○"
        minWidth = 0
        setOnClickListener {
          workspace.checkpoint(); source.visible = !source.visible; workspace.autosave()
          renderer.updateVisibility(source); refreshSources()
        }
      }
      val name = Button(this).apply {
        text = buildString {
          append(StudioAdvancedDialogs.sourceIcon(source.type)).append(" ")
          if (source.locked) append("🔒 ")
          if (source.global) append("🌐 ")
          if (!source.groupId.isNullOrBlank()) append("🔗 ")
          append(source.name)
        }
        setAllCaps(false)
        maxLines = 2
        setOnClickListener { selectSource(source) }
        setOnLongClickListener { showSourceMenu(source); true }
      }
      row.addView(eye, LinearLayout.LayoutParams(54, 52))
      row.addView(name, LinearLayout.LayoutParams(0, 52, 1f))
      binding.sourceList.addView(row)
    }
    if (::uiEnhancer.isInitialized) uiEnhancer.refreshSelectedSourceCost()
  }

'''
s, n = re.subn(r'  private fun refreshSources\(\) \{.*?\n  \}\n\n(?=  private fun selectSource)', new_sources, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"refreshSources replace failed {n}")

needle = '    if (source != null) updateTransformText(source) else binding.txtTransformValues.text = "ソースが選択されていません"\n'
segment = s[s.find("  private fun refreshProperties"):s.find("  private fun updateTransformText")]
if needle in s and "uiEnhancer.refreshSelectedSourceCost()" not in segment:
    s = s.replace(needle, needle + '    if (::uiEnhancer.isInitialized) uiEnhancer.refreshSelectedSourceCost()\n', 1)

new_menu = '''  private fun showSourceMenu(source: StudioSource) {
    workspace.selectedSourceId = source.id
    binding.transformOverlay.select(source)
    val items = arrayOf(
      "名前を変更", "複製", "変形", "クロップ", "フィルター", "グループ / 解除",
      "プリセット保存", "グローバル切替", "変形をリセット", "削除"
    )
    AlertDialog.Builder(this).setTitle(source.name).setItems(items) { _, index ->
      when (index) {
        0 -> dialogs.text("名前を変更", source.name) { source.name = it; workspace.autosave(); refreshSources() }
        1 -> { workspace.duplicateSource(source.id); refreshSources(); rebuildProgram() }
        2 -> {
          workspace.checkpoint()
          StudioAdvancedDialogs.showTransform(this, source) {
            workspace.autosave(); renderer.updateTransform(source); refreshProperties()
          }
        }
        3 -> {
          workspace.checkpoint()
          StudioAdvancedDialogs.showCrop(this, source) {
            workspace.autosave(); renderer.updateTransform(source); refreshProperties()
          }
        }
        4 -> showFilters()
        5 -> {
          if (!source.groupId.isNullOrBlank()) {
            workspace.ungroup(source.groupId); refreshSources()
          } else workspace.scene()?.let { scene ->
            StudioAdvancedDialogs.showGroup(this, scene, workspace) { refreshSources() }
          }
        }
        6 -> dialogs.text("プリセット名", source.name) { workspace.savePreset(it, source); toast("プリセットを保存しました") }
        7 -> { workspace.toggleGlobal(source.id); refreshSources(); rebuildProgram() }
        8 -> {
          workspace.checkpoint(); source.transform = StudioTransform(); workspace.autosave()
          rebuildProgram(); selectSource(source)
        }
        9 -> { renderer.remove(source.id); workspace.removeSource(source.id); refreshSources(); rebuildProgram() }
      }
    }.show()
  }

'''
s, n = re.subn(r'  private fun showSourceMenu\(source: StudioSource\) \{.*?\n  \}\n\n(?=  private fun showFilters)', new_menu, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"source menu replace failed {n}")

new_filters = '''  private fun showFilters() {
    val source = workspace.source() ?: return
    val items = arrayOf(
      "クロマキー", "右へ90°回転", "不透明度 100%", "不透明度 50%",
      "フェードイン", "スライドイン", "フィルター名を追加"
    )
    AlertDialog.Builder(this).setTitle("フィルター — ${source.name}").setItems(items) { _, index ->
      workspace.checkpoint()
      when (index) {
        0 -> {
          StudioAdvancedDialogs.showChroma(this, source) {
            workspace.autosave(); renderer.updateTransform(source); refreshProperties()
          }
          return@setItems
        }
        1 -> source.transform.rotation = (source.transform.rotation + 90) % 360
        2 -> source.transform.alpha = 1f
        3 -> source.transform.alpha = .5f
        4 -> animateSource(source, true)
        5 -> animateSource(source, false)
        6 -> dialogs.text("フィルター名") { source.filters += StudioFilter(it); workspace.autosave(); toast("フィルターを更新しました") }
      }
      workspace.autosave(); renderer.updateTransform(source); refreshProperties()
    }.show()
  }

'''
s, n = re.subn(r'  private fun showFilters\(\) \{.*?\n  \}\n\n(?=  private fun animateSource)', new_filters, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"filters replace failed {n}")

old = '''    val duration = workspace.project.transitionMs.toLong()
    if (workspace.project.transition == StudioTransition.CUT) {
      rebuildProgram()
    } else {
'''
new = '''    val targetScene = workspace.project.scenes.firstOrNull { it.id == id }
    val transition = targetScene?.transitionOverride ?: workspace.project.transition
    val duration = (targetScene?.transitionMsOverride ?: workspace.project.transitionMs).toLong()
    if (transition == StudioTransition.CUT) {
      rebuildProgram()
    } else {
'''
if old in s:
    s = s.replace(old, new, 1)

s, n = re.subn(
    r'  private fun showEndpointDialog\(\) \{.*?\n  \}\n\n(?=  private fun showPerformance)',
    '''  private fun showEndpointDialog() {
    StreamEndpointSettings.show(this) { toast("配信先設定を保存しました") }
  }

''', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"endpoint dialog replace failed {n}")

s, n = re.subn(
    r'  private fun getEndpoints\(\): List<String> =.*?\n  \}\n\n(?=  private fun requestCapture)',
    '''  private fun getEndpoints(): List<String> = StreamEndpointSettings.endpoints(this)

''', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"endpoint storage replace failed {n}")

s = s.replace('toast("出力設定でRTMP URLを設定してください")', 'toast("出力設定で配信サーバーURLとストリームキーを設定してください")')
p.write_text(s)

u = Path("app/src/main/java/jp/minobs/app/StudioUiEnhancer.kt")
t = u.read_text().replace("import android.widget.AlertDialog", "import android.app.AlertDialog")
u.write_text(t)
