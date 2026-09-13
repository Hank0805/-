package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.minobs.app.databinding.ActivityObsStudioBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

class ObsStudioActivity : AppCompatActivity(), SurfaceHolder.Callback {

  private lateinit var binding: ActivityObsStudioBinding
  private lateinit var workspace: StudioWorkspace
  private lateinit var dialogs: StudioDialogs
  private lateinit var automation: StudioAutomation

  private val renderer by lazy { StudioRenderer(this) }
  private val dynamic by lazy { StudioDynamicSources(this, renderer) }
  private val handler = Handler(Looper.getMainLooper())

  private var service: ScreenStreamService? = null
  private var bound = false
  private var micVolume = 1f
  private var gameVolume = 1f
  private var micMuted = false
  private var pendingImageId: String? = null
  private var pendingMediaId: String? = null
  private var pendingSlideId: String? = null
  private var recordSegmentStartedAt = 0L

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as? ScreenStreamService.LocalBinder)?.service()
      bound = service != null
      renderer.attach(service)
      attachPreview()
      rebuildProgram()
      refreshAll()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      bound = false
      service = null
      renderer.attach(null)
      refreshAll()
    }
  }

  private val statusReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == ScreenStreamService.ACTION_STATUS) refreshControls()
    }
  }

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { launchCapturePicker() }

  private val captureLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
    val p = workspace.project
    val config = StreamConfig(
      width = p.outputWidth,
      height = p.outputHeight,
      fps = p.fps,
      bitrate = p.bitrate,
      rotation = 0,
      audioMode = StreamConfig.AudioMode.MIX
    )
    if (service?.prepareCapture(result.resultCode, result.data!!, config) == true) {
      startService(Intent(this, ScreenStreamService::class.java))
      handler.postDelayed({
        attachPreview()
        rebuildProgram()
        refreshAll()
      }, 350)
    } else {
      toast("Capture start failed")
    }
  }

  private val imageLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    val id = pendingImageId
    pendingImageId = null
    if (uri == null || id == null) return@registerForActivityResult
    persistReadPermission(uri)
    workspace.source(id)?.let { source ->
      source.data = uri.toString()
      workspace.autosave()
      renderer.loadImage(source, uri)
      refreshSources()
    }
  }

  private val mediaLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    val id = pendingMediaId
    pendingMediaId = null
    if (uri == null || id == null) return@registerForActivityResult
    persistReadPermission(uri)
    workspace.source(id)?.let { source ->
      source.data = uri.toString()
      workspace.autosave()
      rebuildProgram()
      refreshSources()
    }
  }

  private val slidesLauncher = registerForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    val id = pendingSlideId
    pendingSlideId = null
    if (id == null || uris.isEmpty()) return@registerForActivityResult
    uris.forEach(::persistReadPermission)
    workspace.source(id)?.let { source ->
      source.data = uris.joinToString("\n")
      workspace.autosave()
      dynamic.startSlideshow(source)
      refreshSources()
    }
  }

  private val exportLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    if (uri == null) return@registerForActivityResult
    runCatching {
      contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(workspace.exportJson()) }
    }.onSuccess { toast("Project exported") }
      .onFailure { toast("Export failed") }
  }

  private val importLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri == null) return@registerForActivityResult
    runCatching {
      contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("read")
    }.onSuccess {
      workspace.importJson(it)
      refreshAll()
      rebuildProgram()
      toast("Project imported")
    }.onFailure { toast("Import failed") }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityObsStudioBinding.inflate(layoutInflater)
    setContentView(binding.root)

    workspace = StudioWorkspace(this)
    dialogs = StudioDialogs(this)
    automation = StudioAutomation(
      workspace = workspace,
      serviceProvider = { service },
      onSceneRequest = ::requestScene,
      onToast = ::toast
    )

    binding.programSurface.holder.addCallback(this)
    setupUi()
    ensureService()
    refreshAll()
  }

  override fun onStart() {
    super.onStart()
    val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      registerReceiver(statusReceiver, filter)
    }
    handler.post(statusTask)
  }

  override fun onStop() {
    runCatching { unregisterReceiver(statusReceiver) }
    handler.removeCallbacks(statusTask)
    super.onStop()
  }

  override fun onDestroy() {
    renderer.clear()
    dynamic.clear()
    if (bound) unbindService(connection)
    super.onDestroy()
  }

  private fun setupUi() {
    binding.spinnerTransition.adapter = ArrayAdapter(
      this,
      android.R.layout.simple_spinner_dropdown_item,
      StudioTransition.entries.map { it.name }
    )
    binding.spinnerTransition.setSelection(
      StudioTransition.entries.indexOf(workspace.project.transition).coerceAtLeast(0)
    )
    binding.spinnerTransition.onItemSelectedListener = ItemSelected { position ->
      workspace.project.transition = StudioTransition.entries[position]
      workspace.autosave()
    }

    binding.seekTransitionMs.progress = (workspace.project.transitionMs - 100).coerceIn(0, 1900)
    binding.txtTransitionMs.text = "${workspace.project.transitionMs} ms"
    binding.seekTransitionMs.setOnSeekBarChangeListener(seek { progress ->
      workspace.project.transitionMs = progress + 100
      binding.txtTransitionMs.text = "${progress + 100} ms"
      workspace.autosave()
    })

    binding.btnClassic.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    binding.btnCapture.setOnClickListener {
      if (service?.isCaptureReady() == true) service?.stopCapture() else requestCapture()
    }
    binding.btnRecord.setOnClickListener {
      if (service?.toggleRecording() == true) {
        recordSegmentStartedAt = System.currentTimeMillis()
        automation.run("RECORD_TOGGLE")
      }
      refreshControls()
    }
    binding.btnStream.setOnClickListener { toggleStream() }
    binding.btnReplay.setOnClickListener { toast("Replay clips: ${service?.saveReplay() ?: 0}") }
    binding.btnPanic.setOnClickListener { service?.togglePrivacy(); refreshControls() }
    binding.btnRemote.setOnClickListener {
      toast(service?.startRemoteStudio() ?: "Start Capture first")
    }
    binding.btnSmart.setOnClickListener { automation.smartAction() }
    binding.btnScreenshot.setOnClickListener { saveScreenshot() }
    binding.btnTake.setOnClickListener { takePreviewToProgram() }

    binding.btnSceneAdd.setOnClickListener {
      dialogs.text("Scene name") { workspace.addScene(it); refreshAll() }
    }
    binding.btnSceneDup.setOnClickListener {
      workspace.duplicateScene(workspace.selectedSceneId)
      refreshAll()
    }
    binding.btnSceneRemove.setOnClickListener {
      workspace.removeScene(workspace.selectedSceneId)
      refreshAll()
      rebuildProgram()
    }

    binding.btnSourceAdd.setOnClickListener { showAddSource() }
    binding.btnSourceDup.setOnClickListener {
      workspace.selectedSourceId?.let {
        workspace.duplicateSource(it)
        refreshSources()
        rebuildProgram()
      }
    }
    binding.btnSourceRemove.setOnClickListener {
      workspace.selectedSourceId?.let {
        renderer.remove(it)
        workspace.removeSource(it)
        refreshSources()
        rebuildProgram()
      }
    }
    binding.btnSourceUp.setOnClickListener {
      workspace.selectedSourceId?.let { workspace.moveSource(it, 1); refreshSources(); rebuildProgram() }
    }
    binding.btnSourceDown.setOnClickListener {
      workspace.selectedSourceId?.let { workspace.moveSource(it, -1); refreshSources(); rebuildProgram() }
    }

    binding.btnUndo.setOnClickListener { if (workspace.undo()) { refreshAll(); rebuildProgram() } }
    binding.btnRedo.setOnClickListener { if (workspace.redo()) { refreshAll(); rebuildProgram() } }
    binding.btnCenterSource.setOnClickListener {
      transformSelected { it.x = 50f - it.width / 2f; it.y = 50f - it.height / 2f }
    }
    binding.btnFitSource.setOnClickListener {
      transformSelected { it.x = 5f; it.y = 5f; it.width = 90f; it.height = 90f }
    }
    binding.seekOpacity.setOnSeekBarChangeListener(seek { progress ->
      workspace.source()?.let { source ->
        source.transform.alpha = progress / 100f
        renderer.updateTransform(source)
        workspace.autosave()
        updateTransformText(source)
      }
    })
    binding.btnVisibility.setOnClickListener {
      workspace.source()?.let { source ->
        workspace.checkpoint()
        source.visible = !source.visible
        workspace.autosave()
        renderer.updateVisibility(source)
        refreshSources()
        refreshProperties()
      }
    }
    binding.btnLock.setOnClickListener {
      workspace.source()?.let { source ->
        workspace.checkpoint()
        source.locked = !source.locked
        workspace.autosave()
        refreshProperties()
        refreshSources()
      }
    }
    binding.btnGlobal.setOnClickListener {
      workspace.selectedSourceId?.let { workspace.toggleGlobal(it); refreshSources(); rebuildProgram() }
    }
    binding.btnFilters.setOnClickListener { showFilters() }
    binding.btnPreset.setOnClickListener { showPresets() }

    binding.seekMic.setOnSeekBarChangeListener(seek { progress ->
      micVolume = progress / 100f
      service?.setAudioVolumes(micVolume, gameVolume)
      binding.txtMicMixer.text = "MIC  $progress%"
    })
    binding.seekGame.setOnSeekBarChangeListener(seek { progress ->
      gameVolume = progress / 100f
      service?.setAudioVolumes(micVolume, gameVolume)
      binding.txtGameMixer.text = "GAME  $progress%"
    })
    binding.btnMicMute.setOnClickListener {
      micMuted = !micMuted
      service?.setMicrophoneMuted(micMuted)
      binding.btnMicMute.text = if (micMuted) "MIC Unmute" else "MIC Mute"
    }
    binding.btnAudioRouting.setOnClickListener { showAudioRouting() }
    binding.btnAudioFilters.setOnClickListener { showAudioFilters() }

    binding.btnPreflight.setOnClickListener { showPreflight() }
    binding.btnMacros.setOnClickListener { showMacros() }
    binding.btnProject.setOnClickListener { showProjectMenu() }
    binding.btnOutput.setOnClickListener { showOutput() }
    binding.btnPerformance.setOnClickListener { showPerformance() }

    binding.transformOverlay.showSafeArea = workspace.project.safeArea
    binding.transformOverlay.onCheckpoint = { workspace.checkpoint() }
    binding.transformOverlay.onTransformChanged = { source ->
      renderer.updateTransform(source)
      workspace.autosave()
      updateTransformText(source)
    }
  }

  private fun showAddSource() {
    val labels = arrayOf(
      "Text", "Image", "Camera", "Browser", "Media / Video", "Slideshow",
      "Clock", "Timer", "Remote Camera", "Chat Overlay", "Title Card"
    )
    AlertDialog.Builder(this).setTitle("Add Source").setItems(labels) { _, index ->
      when (index) {
        0 -> dialogs.text("Text") {
          workspace.addSource(StudioSourceType.TEXT, "Text", it); refreshSources(); rebuildProgram()
        }
        1 -> {
          val source = workspace.addSource(StudioSourceType.IMAGE, "Image")
          pendingImageId = source.id
          imageLauncher.launch(arrayOf("image/*"))
          refreshSources()
        }
        2 -> {
          workspace.addSource(StudioSourceType.CAMERA, "Camera")
          requestCamera()
          refreshSources(); rebuildProgram()
        }
        3 -> dialogs.text("Browser URL") {
          val source = workspace.addSource(StudioSourceType.BROWSER, "Browser", it)
          refreshSources(); dynamic.startBrowser(source)
        }
        4 -> {
          val source = workspace.addSource(StudioSourceType.MEDIA, "Media")
          pendingMediaId = source.id
          mediaLauncher.launch(arrayOf("video/*", "audio/*"))
          refreshSources()
        }
        5 -> {
          val source = workspace.addSource(StudioSourceType.SLIDESHOW, "Slideshow")
          pendingSlideId = source.id
          slidesLauncher.launch(arrayOf("image/*"))
          refreshSources()
        }
        6 -> {
          workspace.addSource(StudioSourceType.CLOCK, "Clock", "HH:mm:ss")
          refreshSources(); rebuildProgram()
        }
        7 -> {
          workspace.addSource(StudioSourceType.TIMER, "Timer", "${System.currentTimeMillis()}|0")
          refreshSources(); rebuildProgram()
        }
        8 -> {
          workspace.addSource(StudioSourceType.EXTERNAL, "Remote Camera", "remote:1")
          refreshSources(); toast("Use Camera Node / Remote Studio")
        }
        9 -> {
          workspace.addSource(StudioSourceType.CHAT, "Chat", "Chat Overlay")
          refreshSources(); rebuildProgram()
        }
        10 -> dialogs.text("Title") {
          val source = workspace.addSource(StudioSourceType.TEXT, "Title Card", it)
          source.transform = StudioTransform(12f, 38f, 76f, 20f)
          refreshSources(); rebuildProgram()
        }
      }
    }.show()
  }

  private fun refreshAll() {
    refreshScenes()
    refreshSources()
    refreshProperties()
    refreshControls()
    renderPreviewScene()
  }

  private fun refreshScenes() {
    binding.sceneList.removeAllViews()
    workspace.project.scenes.forEach { scene ->
      val button = Button(this).apply {
        text = buildString {
          if (workspace.project.programSceneId == scene.id) append("● ")
          if (workspace.project.previewSceneId == scene.id) append("▶ ")
          append(scene.name)
        }
        setAllCaps(false)
        setOnClickListener {
          workspace.selectedSceneId = scene.id
          if (workspace.project.studioMode) {
            workspace.project.previewSceneId = scene.id
          } else {
            workspace.project.programSceneId = scene.id
            rebuildProgram()
          }
          workspace.autosave()
          refreshAll()
        }
      }
      binding.sceneList.addView(
        button,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44)
      )
    }
  }

  private fun refreshSources() {
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
          workspace.checkpoint()
          source.visible = !source.visible
          workspace.autosave()
          renderer.updateVisibility(source)
          refreshSources()
        }
      }
      val name = Button(this).apply {
        text = (if (source.locked) "🔒 " else "") +
          (if (source.global) "🌐 " else "") + source.name
        setAllCaps(false)
        setOnClickListener { selectSource(source) }
        setOnLongClickListener { showSourceMenu(source); true }
      }
      row.addView(eye, LinearLayout.LayoutParams(54, 44))
      row.addView(name, LinearLayout.LayoutParams(0, 44, 1f))
      binding.sourceList.addView(row)
    }
  }

  private fun selectSource(source: StudioSource) {
    workspace.selectedSourceId = source.id
    binding.transformOverlay.select(source)
    refreshProperties()
  }

  private fun refreshProperties() {
    val source = workspace.source()
    binding.txtSelectedSource.text = "Source Properties — ${source?.name ?: "none"}"
    binding.transformOverlay.select(source)
    binding.seekOpacity.progress = ((source?.transform?.alpha ?: 1f) * 100).toInt()
    binding.btnVisibility.text = if (source?.visible != false) "👁 Visible" else "○ Hidden"
    binding.btnLock.text = if (source?.locked == true) "🔒 Locked" else "🔓 Unlock"
    binding.btnGlobal.text = if (source?.global == true) "Scene Source" else "Global"
    if (source != null) updateTransformText(source) else binding.txtTransformValues.text = "No source selected"
  }

  private fun updateTransformText(source: StudioSource) {
    val t = source.transform
    binding.txtTransformValues.text =
      "X %.1f  Y %.1f  W %.1f  H %.1f  R %d°  A %d%%".format(
        t.x, t.y, t.width, t.height, t.rotation, (t.alpha * 100).toInt()
      )
  }

  private fun transformSelected(block: (StudioTransform) -> Unit) {
    workspace.source()?.let { source ->
      workspace.checkpoint()
      block(source.transform)
      workspace.autosave()
      renderer.updateTransform(source)
      binding.transformOverlay.select(source)
      updateTransformText(source)
    }
  }

  private fun rebuildProgram() {
    val scene = workspace.project.scenes.firstOrNull {
      it.id == workspace.project.programSceneId
    } ?: return
    renderer.rebuild(workspace.project, scene)
    (scene.sources + workspace.project.globalSources).forEach(dynamic::hydrate)
    binding.transformOverlay.select(workspace.source())
  }

  private fun requestScene(id: String) {
    workspace.project.previewSceneId = id
    takePreviewToProgram()
  }

  private fun takePreviewToProgram() {
    val id = if (workspace.project.studioMode) {
      workspace.project.previewSceneId
    } else {
      workspace.selectedSceneId
    }
    if (id.isBlank()) return

    workspace.project.programSceneId = id
    workspace.selectedSceneId = id
    workspace.autosave()

    val duration = workspace.project.transitionMs.toLong()
    if (workspace.project.transition == StudioTransition.CUT) {
      rebuildProgram()
    } else {
      binding.programFrame.animate()
        .alpha(.15f)
        .setDuration((duration / 2).coerceAtLeast(40))
        .withEndAction {
          rebuildProgram()
          binding.programFrame.animate()
            .alpha(1f)
            .setDuration((duration / 2).coerceAtLeast(40))
            .start()
        }.start()
    }
    automation.run("SCENE_CHANGE")
    refreshAll()
  }

  private fun renderPreviewScene() {
    val scene = workspace.project.scenes.firstOrNull {
      it.id == workspace.project.previewSceneId
    } ?: return

    val jpeg = service?.remotePreviewJpeg()
    val base = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLACK)
    if (base != null) canvas.drawBitmap(base, null, android.graphics.Rect(0, 0, 640, 360), null)

    val border = android.graphics.Paint().apply {
      style = android.graphics.Paint.Style.STROKE
      strokeWidth = 3f
      color = Color.GREEN
    }
    val textPaint = android.graphics.Paint().apply {
      color = Color.WHITE
      textSize = 18f
    }
    (scene.sources + workspace.project.globalSources)
      .filter { it.visible && it.type != StudioSourceType.SCREEN }
      .forEach { source ->
        val t = source.transform
        val rect = android.graphics.RectF(
          640 * t.x / 100f,
          360 * t.y / 100f,
          640 * (t.x + t.width) / 100f,
          360 * (t.y + t.height) / 100f
        )
        canvas.drawRect(rect, border)
        canvas.drawText(source.name, rect.left + 3, rect.top + 20, textPaint)
      }
    binding.imgPreviewScene.setImageBitmap(bitmap)
    binding.txtPreviewLabel.text = "PREVIEW — ${scene.name}"
  }

  private fun showSourceMenu(source: StudioSource) {
    val items = arrayOf("Rename", "Duplicate", "Save preset", "Toggle global", "Reset transform", "Remove")
    AlertDialog.Builder(this).setTitle(source.name).setItems(items) { _, index ->
      when (index) {
        0 -> dialogs.text("Rename", source.name) { source.name = it; workspace.autosave(); refreshSources() }
        1 -> { workspace.duplicateSource(source.id); refreshSources(); rebuildProgram() }
        2 -> dialogs.text("Preset name", source.name) { workspace.savePreset(it, source); toast("Preset saved") }
        3 -> { workspace.toggleGlobal(source.id); refreshSources(); rebuildProgram() }
        4 -> {
          workspace.checkpoint(); source.transform = StudioTransform(); workspace.autosave()
          rebuildProgram(); selectSource(source)
        }
        5 -> { renderer.remove(source.id); workspace.removeSource(source.id); refreshSources(); rebuildProgram() }
      }
    }.show()
  }

  private fun showFilters() {
    val source = workspace.source() ?: return
    val items = arrayOf("Rotate +90°", "Opacity 100%", "Opacity 50%", "Fade in", "Slide in", "Add filter label")
    AlertDialog.Builder(this).setTitle("Filters — ${source.name}").setItems(items) { _, index ->
      workspace.checkpoint()
      when (index) {
        0 -> source.transform.rotation = (source.transform.rotation + 90) % 360
        1 -> source.transform.alpha = 1f
        2 -> source.transform.alpha = .5f
        3 -> animateSource(source, true)
        4 -> animateSource(source, false)
        5 -> dialogs.text("Filter name") { source.filters += StudioFilter(it); toast("Filter chain updated") }
      }
      workspace.autosave(); renderer.updateTransform(source); refreshProperties()
    }.show()
  }

  private fun animateSource(source: StudioSource, fade: Boolean) {
    val end = source.transform.copyValue()
    if (fade) source.transform.alpha = 0f else source.transform.x = -end.width
    renderer.updateTransform(source)
    val start = System.currentTimeMillis()
    val task = object : Runnable {
      override fun run() {
        val f = ((System.currentTimeMillis() - start) / 500f).coerceIn(0f, 1f)
        if (fade) source.transform.alpha = end.alpha * f
        else source.transform.x = (-end.width) * (1f - f) + end.x * f
        renderer.updateTransform(source)
        if (f < 1f) handler.postDelayed(this, 16) else {
          source.transform = end
          workspace.autosave()
        }
      }
    }
    handler.post(task)
  }

  private fun showPresets() {
    val selected = workspace.source()
    val names = workspace.presetNames()
    val items = arrayListOf("Save current").apply { addAll(names) }
    AlertDialog.Builder(this).setTitle("Presets").setItems(items.toTypedArray()) { _, index ->
      if (index == 0 && selected != null) {
        dialogs.text("Preset name", selected.name) { workspace.savePreset(it, selected) }
      } else if (index > 0) {
        workspace.loadPreset(items[index])?.let { preset ->
          workspace.scene()?.sources?.add(preset)
          workspace.selectedSourceId = preset.id
          workspace.autosave(); refreshSources(); rebuildProgram()
        }
      }
    }.show()
  }

  private fun showAudioRouting() {
    val source = workspace.source() ?: return
    val items = arrayOf(
      "Stream output: ${source.streamAudio}",
      "Record output: ${source.recordAudio}",
      "Monitor: ${source.audioMonitor}",
      "Track: ${source.audioTrack}",
      "Delay: ${source.audioDelayMs}ms"
    )
    AlertDialog.Builder(this).setTitle("Advanced Audio").setItems(items) { _, index ->
      workspace.checkpoint()
      when (index) {
        0 -> source.streamAudio = !source.streamAudio
        1 -> source.recordAudio = !source.recordAudio
        2 -> source.audioMonitor = !source.audioMonitor
        3 -> source.audioTrack = (source.audioTrack % 6) + 1
        4 -> dialogs.text("Delay ms", source.audioDelayMs.toString()) {
          source.audioDelayMs = it.toIntOrNull() ?: 0
        }
      }
      workspace.autosave()
      showAudioRouting()
    }.show()
  }

  private fun showAudioFilters() {
    val presets = arrayOf("game", "voice", "broadcast", "flat")
    AlertDialog.Builder(this).setTitle("Audio filters").setItems(presets) { _, index ->
      service?.setAudioPreset(presets[index])
      toast(presets[index])
    }.show()
  }

  private fun showMacros() {
    val items = arrayListOf("Add macro", "Run MANUAL").apply {
      addAll(workspace.project.macros.map { (if (it.enabled) "✓ " else "○ ") + it.name })
    }
    AlertDialog.Builder(this).setTitle("Macros").setItems(items.toTypedArray()) { _, index ->
      when {
        index == 0 -> addMacro()
        index == 1 -> automation.run("MANUAL")
        else -> {
          val macro = workspace.project.macros[index - 2]
          macro.enabled = !macro.enabled
          workspace.autosave()
          showMacros()
        }
      }
    }.show()
  }

  private fun addMacro() {
    dialogs.text("Macro: trigger,action", "MANUAL,replay") { value ->
      val parts = value.split(',', limit = 2)
      if (parts.size == 2) {
        workspace.project.macros += StudioMacro(
          name = "Macro ${workspace.project.macros.size + 1}",
          trigger = parts[0].trim().uppercase(),
          action = parts[1].trim()
        )
        workspace.autosave()
      }
    }
  }

  fun runMacros(trigger: String) = automation.run(trigger)

  private fun showProjectMenu() {
    val items = arrayOf("Export project", "Import project", "Autosave", "Reset", "Toggle Safe Area", "Toggle Studio Mode")
    AlertDialog.Builder(this).setTitle("Project").setItems(items) { _, index ->
      when (index) {
        0 -> exportLauncher.launch("MiniOBS_Project.json")
        1 -> importLauncher.launch(arrayOf("application/json", "text/plain"))
        2 -> { workspace.autosave(); toast("Autosaved") }
        3 -> { workspace.reset(); refreshAll(); rebuildProgram() }
        4 -> {
          workspace.project.safeArea = !workspace.project.safeArea
          binding.transformOverlay.showSafeArea = workspace.project.safeArea
          workspace.autosave()
        }
        5 -> {
          workspace.project.studioMode = !workspace.project.studioMode
          workspace.autosave()
          toast("Studio Mode ${workspace.project.studioMode}")
        }
      }
    }.show()
  }

  private fun showOutput() {
    val p = workspace.project
    dialogs.text(
      "Output WxH FPS kbps",
      "${p.outputWidth}x${p.outputHeight} ${p.fps} ${p.bitrate / 1000}"
    ) { value ->
      val parts = value.replace('x', ' ').split(' ').filter { it.isNotBlank() }
      if (parts.size >= 4) {
        workspace.checkpoint()
        p.outputWidth = parts[0].toIntOrNull() ?: p.outputWidth
        p.outputHeight = parts[1].toIntOrNull() ?: p.outputHeight
        p.fps = parts[2].toIntOrNull() ?: p.fps
        p.bitrate = (parts[3].toIntOrNull() ?: p.bitrate / 1000) * 1000
        workspace.autosave()
      }
      showEndpointDialog()
    }
  }

  private fun showEndpointDialog() {
    dialogs.text("RTMP URLs separated by |", getEndpoints().joinToString("|")) {
      saveEndpoints(it.split('|'))
    }
  }

  private fun showPerformance() {
    val names = StudioPerformanceMode.entries.map { it.name }.toTypedArray()
    AlertDialog.Builder(this).setTitle("Performance").setItems(names) { _, index ->
      workspace.project.performanceMode = StudioPerformanceMode.entries[index]
      if (workspace.project.performanceMode == StudioPerformanceMode.BATTERY) {
        workspace.project.fps = 30
        workspace.project.bitrate = workspace.project.bitrate.coerceAtMost(5_000_000)
      }
      workspace.autosave(); toast(names[index])
    }.show()
  }

  private fun showPreflight() {
    val endpoints = getEndpoints()
    val lines = listOf(
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "✓ Audio permission" else "✕ Audio permission",
      if (service?.isCaptureReady() == true) "✓ Capture ready" else "! Capture stopped",
      if (endpoints.isNotEmpty()) "✓ ${endpoints.size} stream destination(s)" else "! No RTMP destination",
      "✓ Project autosave enabled",
      "✓ Free storage ${filesDir.freeSpace / 1024 / 1024} MB"
    )
    AlertDialog.Builder(this).setTitle("Preflight").setMessage(lines.joinToString("\n")).setPositiveButton("OK", null).show()
  }

  private fun toggleStream() {
    val s = service ?: return
    if (s.isStreamingNow()) {
      s.stopRtmp()
    } else {
      val endpoints = getEndpoints()
      if (endpoints.isEmpty()) { toast("OutputでRTMP URLを設定してください"); return }
      s.startMultiRtmp(endpoints)
    }
    automation.run("STREAM_TOGGLE")
    refreshControls()
  }

  private fun getEndpoints(): List<String> =
    getSharedPreferences("studio_output", MODE_PRIVATE)
      .getString("rtmp", "")
      .orEmpty()
      .lines()
      .map { it.trim() }
      .filter { it.startsWith("rtmp://") || it.startsWith("rtmps://") }
      .take(4)

  private fun saveEndpoints(values: List<String>) {
    getSharedPreferences("studio_output", MODE_PRIVATE)
      .edit()
      .putString("rtmp", values.joinToString("\n"))
      .apply()
  }

  private fun requestCapture() {
    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
    val missing = permissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) launchCapturePicker() else permissionLauncher.launch(missing.toTypedArray())
  }

  private fun launchCapturePicker() {
    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    captureLauncher.launch(manager.createScreenCaptureIntent())
  }

  private fun requestCamera() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.CAMERA), 919)
    }
  }

  private fun ensureService() {
    if (!bound) bindService(Intent(this, ScreenStreamService::class.java), connection, BIND_AUTO_CREATE)
  }

  private fun attachPreview() {
    val s = service ?: return
    if (s.isCaptureReady() && binding.programSurface.holder.surface.isValid) {
      s.attachPreview(binding.programSurface)
    }
  }

  override fun surfaceCreated(holder: SurfaceHolder) = attachPreview()
  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachPreview()
  override fun surfaceDestroyed(holder: SurfaceHolder) { service?.detachPreview() }

  private fun refreshControls() {
    val ready = service?.isCaptureReady() == true
    val live = service?.isStreamingNow() == true
    val recording = service?.isRecordingNow() == true
    binding.btnCapture.text = if (ready) "Stop Capture" else "Start Capture"
    binding.btnStream.text = if (live) "Stop Streaming" else "Start Streaming"
    binding.btnRecord.text = if (recording) "Stop Recording" else "Start Recording"
    binding.txtStudioStatus.text = when {
      service?.isPrivacyMode() == true -> "PRIVACY"
      live -> "LIVE"
      recording -> "REC"
      ready -> "READY"
      else -> "OFFLINE"
    }
    binding.txtStudioStatus.setTextColor(
      when {
        live || recording -> Color.RED
        ready -> Color.GREEN
        else -> Color.LTGRAY
      }
    )
  }

  private val statusTask = object : Runnable {
    override fun run() {
      updateHealthAndMeters()
      checkRecordingSplit()
      if (workspace.project.studioMode) renderPreviewScene()
      handler.postDelayed(this, 1400)
    }
  }

  private fun updateHealthAndMeters() {
    runCatching {
      service?.remoteStatus()?.let { status ->
        binding.txtHealth.text =
          "${status.optString("resolution")} ${status.optInt("fps")}fps | " +
            "${status.optLong("bitrateKbps")}kbps | Drop ${status.optLong("droppedVideo")}/${status.optLong("droppedAudio")} | " +
            "${status.optDouble("temperature")}℃ | Battery ${status.optInt("battery")}% | Free ${status.optLong("storageFreeMb")}MB"
        if (status.optInt("battery") in 1..14) automation.run("LOW_BATTERY")
      }
    }

    val wav = runCatching { service?.remoteMonitorWav() }.getOrNull() ?: return
    if (wav.size <= 44) return
    var sum = 0.0
    var count = 0
    var i = 44
    while (i + 1 < wav.size) {
      val sample = ((wav[i].toInt() and 0xff) or (wav[i + 1].toInt() shl 8)).toShort().toInt()
      sum += sample.toDouble() * sample
      count++
      i += 2
    }
    if (count > 0) {
      val rms = sqrt(sum / count) / 32768.0
      val db = if (rms > 0.0) 20.0 * log10(rms) else -60.0
      val level = (((db + 60.0) / 60.0) * 100.0).toInt().coerceIn(0, 100)
      binding.meterMic.progress = (level * micVolume).toInt().coerceIn(0, 100)
      binding.meterGame.progress = (level * gameVolume).toInt().coerceIn(0, 100)
    }
  }

  private fun checkRecordingSplit() {
    val minutes = workspace.project.autoSplitMinutes
    if (minutes <= 0 || service?.isRecordingNow() != true || recordSegmentStartedAt <= 0L) return
    val limit = minutes * 60_000L
    if (System.currentTimeMillis() - recordSegmentStartedAt >= limit) {
      service?.toggleRecording()
      handler.postDelayed({
        service?.toggleRecording()
        recordSegmentStartedAt = System.currentTimeMillis()
      }, 400)
    }
  }

  private fun saveScreenshot() {
    val jpeg = service?.remotePreviewJpeg()
    if (jpeg == null) { toast("Start Capture first"); return }
    runCatching {
      val name = "MiniOBS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MiniOBS")
      }
      val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
      contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
    }.onSuccess { toast("Screenshot saved") }
      .onFailure { toast("Screenshot failed") }
  }

  private fun persistReadPermission(uri: Uri) {
    runCatching {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private fun seek(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
      if (fromUser) change(progress)
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
  }

  private class ItemSelected(private val onSelected: (Int) -> Unit) :
    android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
      parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
    ) = onSelected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
  }

  private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) {
      when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { service?.toggleRecording(); return true }
        KeyEvent.KEYCODE_BUTTON_A -> { takePreviewToProgram(); return true }
        KeyEvent.KEYCODE_BUTTON_B -> { service?.togglePrivacy(); return true }
      }
    }
    return super.dispatchKeyEvent(event)
  }
}
