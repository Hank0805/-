package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.minobs.app.databinding.ActivityObsStudioBinding

class ObsStudioActivity : AppCompatActivity(), SurfaceHolder.Callback {
  private lateinit var binding: ActivityObsStudioBinding
  private lateinit var workspace: StudioWorkspace
  private lateinit var dialogs: StudioDialogs
  private val renderer by lazy { StudioRenderer(this) }
  private val dynamic by lazy { StudioDynamicSources(this, renderer) }
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var automation: StudioAutomation
  private var service: ScreenStreamService? = null
  private var bound = false
  private var micVolume = 1f
  private var gameVolume = 1f
  private var micMuted = false
  private var pendingImageId: String? = null
  private var pendingMediaId: String? = null
  private var pendingSlideId: String? = null

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as? ScreenStreamService.LocalBinder)?.service(); bound = service != null
      renderer.attach(service); attachPreview(); rebuildProgram(); refreshAll()
    }
    override fun onServiceDisconnected(name: ComponentName?) { bound = false; service = null; renderer.attach(null); refreshAll() }
  }

  private val statusReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == ScreenStreamService.ACTION_STATUS) refreshControls() }
  }

  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchCapturePicker() }
  private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
    val p = workspace.project
    val cfg = StreamConfig(p.outputWidth, p.outputHeight, p.fps, p.bitrate, 0, StreamConfig.AudioMode.MIX)
    if (service?.prepareCapture(result.resultCode, result.data!!, cfg) == true) {
      startService(Intent(this, ScreenStreamService::class.java))
      handler.postDelayed({ attachPreview(); rebuildProgram(); refreshAll() }, 350)
    } else toast("Capture start failed")
  }

  private val imageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val id = pendingImageId; pendingImageId = null; if (uri == null || id == null) return@registerForActivityResult
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    workspace.source(id)?.let { it.data = uri.toString(); workspace.autosave(); renderer.loadImage(it, uri); refreshSources() }
  }
  private val mediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val id = pendingMediaId; pendingMediaId = null; if (uri == null || id == null) return@registerForActivityResult
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    workspace.source(id)?.let { it.data = uri.toString(); workspace.autosave(); rebuildProgram(); refreshSources() }
  }
  private val slidesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    val id = pendingSlideId; pendingSlideId = null; if (id == null || uris.isEmpty()) return@registerForActivityResult
    uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    workspace.source(id)?.let { it.data = uris.joinToString("\n"); workspace.autosave(); dynamic.startSlideshow(it); refreshSources() }
  }
  private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(workspace.exportJson()) } }.onSuccess { toast("Project exported") }
  }
  private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("read") }.onSuccess { workspace.importJson(it); refreshAll(); rebuildProgram(); toast("Project imported") }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState); binding = ActivityObsStudioBinding.inflate(layoutInflater); setContentView(binding.root)
    workspace = StudioWorkspace(this); dialogs = StudioDialogs(this)
    automation = StudioAutomation(workspace, { service }, { id -> requestScene(id) }, ::toast)
    binding.programSurface.holder.addCallback(this); setupUi(); ensureService(); refreshAll()
  }

  override fun onStart() {
    super.onStart(); val f = IntentFilter(ScreenStreamService.ACTION_STATUS)
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED) else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, f) }
    handler.post(statusTask)
  }
  override fun onStop() { runCatching { unregisterReceiver(statusReceiver) }; handler.removeCallbacks(statusTask); super.onStop() }
  override fun onDestroy() { renderer.clear(); dynamic.clear(); if (bound) unbindService(connection); super.onDestroy() }

  private fun setupUi() {
    binding.spinnerTransition.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, StudioTransition.entries.map { it.name })
    binding.spinnerTransition.setSelection(StudioTransition.entries.indexOf(workspace.project.transition).coerceAtLeast(0))
    binding.seekTransitionMs.progress = (workspace.project.transitionMs - 100).coerceIn(0, 1900)
    binding.txtTransitionMs.text = "${workspace.project.transitionMs} ms"
    binding.seekTransitionMs.setOnSeekBarChangeListener(seek { p -> workspace.project.transitionMs=p+100;binding.txtTransitionMs.text="${p+100} ms";workspace.autosave() })
    binding.spinnerTransition.onItemSelectedListener = ItemSelected { workspace.project.transition=StudioTransition.entries[it];workspace.autosave() }
    binding.btnClassic.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    binding.btnCapture.setOnClickListener { if(service?.isCaptureReady()==true)service?.stopCapture() else requestCapture() }
    binding.btnRecord.setOnClickListener { service?.toggleRecording(); automation.run("RECORD_TOGGLE"); refreshControls() }
    binding.btnStream.setOnClickListener { toggleStream() }
    binding.btnReplay.setOnClickListener { toast("Replay clips: ${service?.saveReplay() ?: 0}") }
    binding.btnPanic.setOnClickListener { service?.togglePrivacy(); refreshControls() }
    binding.btnRemote.setOnClickListener { toast(service?.startRemoteStudio() ?: "Start Capture first") }
    binding.btnSmart.setOnClickListener { automation.smartAction() }
    binding.btnScreenshot.setOnClickListener { toast("ScreenshotはToolsメニューから保存できます") }
    binding.btnTake.setOnClickListener { takePreviewToProgram() }
    binding.btnSceneAdd.setOnClickListener { dialogs.text("Scene name") { workspace.addScene(it); refreshAll() } }
    binding.btnSceneDup.setOnClickListener { workspace.duplicateScene(workspace.selectedSceneId);refreshAll() }
    binding.btnSceneRemove.setOnClickListener { workspace.removeScene(workspace.selectedSceneId);refreshAll();rebuildProgram() }
    binding.btnSourceAdd.setOnClickListener { showAddSource() }
    binding.btnSourceDup.setOnClickListener { workspace.selectedSourceId?.let { workspace.duplicateSource(it);refreshSources();rebuildProgram() } }
    binding.btnSourceRemove.setOnClickListener { workspace.selectedSourceId?.let { renderer.remove(it);workspace.removeSource(it);refreshSources();rebuildProgram() } }
    binding.btnSourceUp.setOnClickListener { workspace.selectedSourceId?.let { workspace.moveSource(it,1);refreshSources();rebuildProgram() } }
    binding.btnSourceDown.setOnClickListener { workspace.selectedSourceId?.let { workspace.moveSource(it,-1);refreshSources();rebuildProgram() } }
    binding.btnUndo.setOnClickListener { if(workspace.undo()){refreshAll();rebuildProgram()} }
    binding.btnRedo.setOnClickListener { if(workspace.redo()){refreshAll();rebuildProgram()} }
    binding.btnCenterSource.setOnClickListener { transformSelected { it.x=50f-it.width/2f;it.y=50f-it.height/2f } }
    binding.btnFitSource.setOnClickListener { transformSelected { it.x=5f;it.y=5f;it.width=90f;it.height=90f } }
    binding.seekOpacity.setOnSeekBarChangeListener(seek { p -> workspace.source()?.let { it.transform.alpha=p/100f;renderer.updateTransform(it);workspace.autosave();updateTransformText(it) } })
    binding.btnVisibility.setOnClickListener { workspace.source()?.let { workspace.checkpoint();it.visible=!it.visible;workspace.autosave();renderer.updateVisibility(it);refreshSources();refreshProperties() } }
    binding.btnLock.setOnClickListener { workspace.source()?.let { workspace.checkpoint();it.locked=!it.locked;workspace.autosave();refreshProperties();refreshSources() } }
    binding.btnGlobal.setOnClickListener { workspace.selectedSourceId?.let { workspace.toggleGlobal(it);refreshSources();rebuildProgram() } }
    binding.btnFilters.setOnClickListener { showFilters() }
    binding.btnPreset.setOnClickListener { showPresets() }
    binding.seekMic.setOnSeekBarChangeListener(seek { micVolume=it/100f;service?.setAudioVolumes(micVolume,gameVolume);binding.txtMicMixer.text="MIC  $it%" })
    binding.seekGame.setOnSeekBarChangeListener(seek { gameVolume=it/100f;service?.setAudioVolumes(micVolume,gameVolume);binding.txtGameMixer.text="GAME  $it%" })
    binding.btnMicMute.setOnClickListener { micMuted=!micMuted;service?.setMicrophoneMuted(micMuted);binding.btnMicMute.text=if(micMuted)"MIC Unmute" else "MIC Mute" }
    binding.btnAudioRouting.setOnClickListener { showAudioRouting() }
    binding.btnAudioFilters.setOnClickListener { showAudioFilters() }
    binding.btnPreflight.setOnClickListener { showPreflight() }
    binding.btnMacros.setOnClickListener { showMacros() }
    binding.btnProject.setOnClickListener { showProjectMenu() }
    binding.btnOutput.setOnClickListener { showOutput() }
    binding.btnPerformance.setOnClickListener { showPerformance() }
    binding.transformOverlay.showSafeArea=workspace.project.safeArea
    binding.transformOverlay.onCheckpoint={workspace.checkpoint()}
    binding.transformOverlay.onTransformChanged={renderer.updateTransform(it);workspace.autosave();updateTransformText(it)}
  }

  private fun showAddSource() {
    val labels=arrayOf("Text","Image","Camera","Browser","Media / Video","Slideshow","Clock","Timer","Remote Camera","Chat Overlay","Title Card")
    AlertDialog.Builder(this).setTitle("Add Source").setItems(labels){_,i->when(i){
      0->dialogs.text("Text"){workspace.addSource(StudioSourceType.TEXT,"Text",it);refreshSources();rebuildProgram()}
      1->{val s=workspace.addSource(StudioSourceType.IMAGE,"Image");pendingImageId=s.id;imageLauncher.launch(arrayOf("image/*"));refreshSources()}
      2->{workspace.addSource(StudioSourceType.CAMERA,"Camera");requestCamera();refreshSources();rebuildProgram()}
      3->dialogs.text("Browser URL"){val s=workspace.addSource(StudioSourceType.BROWSER,"Browser",it);refreshSources();dynamic.startBrowser(s)}
      4->{val s=workspace.addSource(StudioSourceType.MEDIA,"Media");pendingMediaId=s.id;mediaLauncher.launch(arrayOf("video/*","audio/*"));refreshSources()}
      5->{val s=workspace.addSource(StudioSourceType.SLIDESHOW,"Slideshow");pendingSlideId=s.id;slidesLauncher.launch(arrayOf("image/*"));refreshSources()}
      6->{workspace.addSource(StudioSourceType.CLOCK,"Clock","HH:mm:ss");refreshSources();rebuildProgram()}
      7->{workspace.addSource(StudioSourceType.TIMER,"Timer","${System.currentTimeMillis()}|0");refreshSources();rebuildProgram()}
      8->{workspace.addSource(StudioSourceType.EXTERNAL,"Remote Camera","remote:1");refreshSources();toast("Use Camera Node / Remote Studio")}
      9->{workspace.addSource(StudioSourceType.CHAT,"Chat","Chat Overlay");refreshSources();rebuildProgram()}
      10->dialogs.text("Title"){val s=workspace.addSource(StudioSourceType.TEXT,"Title Card",it);s.transform=StudioTransform(12f,38f,76f,20f);refreshSources();rebuildProgram()}
    }}.show()
  }

  private fun refreshAll(){refreshScenes();refreshSources();refreshProperties();refreshControls();renderPreviewScene()}
  private fun refreshScenes(){binding.sceneList.removeAllViews();workspace.project.scenes.forEach{scene->val b=Button(this).apply{text=(if(workspace.project.programSceneId==scene.id)"● " else "")+(if(workspace.project.previewSceneId==scene.id)"▶ " else "")+scene.name;textAllCaps=false;setOnClickListener{workspace.selectedSceneId=scene.id;if(workspace.project.studioMode)workspace.project.previewSceneId=scene.id else{workspace.project.programSceneId=scene.id;rebuildProgram()};workspace.autosave();refreshAll()}};binding.sceneList.addView(b,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,44))}}
  private fun refreshSources(){binding.sourceList.removeAllViews();val scene=workspace.scene()?:return;val list=mutableListOf<StudioSource>().apply{addAll(scene.sources);addAll(workspace.project.globalSources)};list.asReversed().forEach{src->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val eye=Button(this).apply{text=if(src.visible)"👁" else "○";minWidth=0;setOnClickListener{workspace.checkpoint();src.visible=!src.visible;workspace.autosave();renderer.updateVisibility(src);refreshSources()}};val name=Button(this).apply{text=(if(src.locked)"🔒 " else "")+(if(src.global)"🌐 " else "")+src.name;textAllCaps=false;setOnClickListener{selectSource(src)};setOnLongClickListener{showSourceMenu(src);true}};row.addView(eye,LinearLayout.LayoutParams(54,44));row.addView(name,LinearLayout.LayoutParams(0,44,1f));binding.sourceList.addView(row)}}
  private fun selectSource(src:StudioSource){workspace.selectedSourceId=src.id;binding.transformOverlay.select(src);refreshProperties()}
  private fun refreshProperties(){val s=workspace.source();binding.txtSelectedSource.text="Source Properties — ${s?.name?:"none"}";binding.transformOverlay.select(s);binding.seekOpacity.progress=((s?.transform?.alpha?:1f)*100).toInt();binding.btnVisibility.text=if(s?.visible!=false)"👁 Visible" else "○ Hidden";binding.btnLock.text=if(s?.locked==true)"🔒 Locked" else "🔓 Unlock";binding.btnGlobal.text=if(s?.global==true)"Scene Source" else "Global";if(s!=null)updateTransformText(s)else binding.txtTransformValues.text="No source selected"}
  private fun updateTransformText(s:StudioSource){val t=s.transform;binding.txtTransformValues.text="X %.1f  Y %.1f  W %.1f  H %.1f  R %d°  A %d%%".format(t.x,t.y,t.width,t.height,t.rotation,(t.alpha*100).toInt())}
  private fun transformSelected(block:(StudioTransform)->Unit){workspace.source()?.let{workspace.checkpoint();block(it.transform);workspace.autosave();renderer.updateTransform(it);binding.transformOverlay.select(it);updateTransformText(it)}}

  private fun rebuildProgram(){val scene=workspace.project.scenes.firstOrNull{it.id==workspace.project.programSceneId}?:return;renderer.rebuild(workspace.project,scene);(scene.sources+workspace.project.globalSources).forEach{dynamic.hydrate(it)};binding.transformOverlay.select(workspace.source())}
  private fun requestScene(id:String){workspace.project.previewSceneId=id;takePreviewToProgram()}
  private fun takePreviewToProgram(){val id=if(workspace.project.studioMode)workspace.project.previewSceneId else workspace.selectedSceneId;if(id.isBlank())return;workspace.project.programSceneId=id;workspace.selectedSceneId=id;workspace.autosave();val d=workspace.project.transitionMs.toLong();if(workspace.project.transition==StudioTransition.CUT){rebuildProgram()}else binding.programFrame.animate().alpha(.15f).setDuration((d/2).coerceAtLeast(40)).withEndAction{rebuildProgram();binding.programFrame.animate().alpha(1f).setDuration((d/2).coerceAtLeast(40)).start()}.start();automation.run("SCENE_CHANGE");refreshAll()}
  private fun renderPreviewScene(){val scene=workspace.project.scenes.firstOrNull{it.id==workspace.project.previewSceneId}?:return;val jpeg=service?.remotePreviewJpeg();val base=jpeg?.let{BitmapFactory.decodeByteArray(it,0,it.size)};val bmp=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888);val c=Canvas(bmp);c.drawColor(Color.BLACK);if(base!=null)c.drawBitmap(base,null,android.graphics.Rect(0,0,640,360),null);val p=android.graphics.Paint().apply{style=android.graphics.Paint.Style.STROKE;strokeWidth=3f;color=Color.GREEN};val tp=android.graphics.Paint().apply{color=Color.WHITE;textSize=18f};(scene.sources+workspace.project.globalSources).filter{it.visible&&it.type!=StudioSourceType.SCREEN}.forEach{s->val t=s.transform;val r=android.graphics.RectF(640*t.x/100,360*t.y/100,640*(t.x+t.width)/100,360*(t.y+t.height)/100);c.drawRect(r,p);c.drawText(s.name,r.left+3,r.top+20,tp)};binding.imgPreviewScene.setImageBitmap(bmp);binding.txtPreviewLabel.text="PREVIEW — ${scene.name}"}

  private fun showSourceMenu(src:StudioSource){val items=arrayOf("Rename","Duplicate","Save preset","Toggle global","Reset transform","Remove");AlertDialog.Builder(this).setTitle(src.name).setItems(items){_,i->when(i){0->dialogs.text("Rename",src.name){src.name=it;workspace.autosave();refreshSources()};1->{workspace.duplicateSource(src.id);refreshSources();rebuildProgram()};2->dialogs.text("Preset name",src.name){workspace.savePreset(it,src);toast("Preset saved")};3->{workspace.toggleGlobal(src.id);refreshSources();rebuildProgram()};4->{workspace.checkpoint();src.transform=StudioTransform();workspace.autosave();rebuildProgram();selectSource(src)};5->{renderer.remove(src.id);workspace.removeSource(src.id);refreshSources();rebuildProgram()}}}.show()}

  private fun showFilters(){val s=workspace.source()?:return;val items=arrayOf("Rotate +90°","Opacity 100%","Opacity 50%","Fade in","Slide in","Add filter label");AlertDialog.Builder(this).setTitle("Filters — ${s.name}").setItems(items){_,i->workspace.checkpoint();when(i){0->s.transform.rotation=(s.transform.rotation+90)%360;1->s.transform.alpha=1f;2->s.transform.alpha=.5f;3->animateSource(s,true);4->animateSource(s,false);5->dialogs.text("Filter name"){s.filters+=StudioFilter(it);toast("Filter chain updated")}};workspace.autosave();renderer.updateTransform(s);refreshProperties()}.show()}
  private fun animateSource(s:StudioSource,fade:Boolean){val end=s.transform.copyValue();if(fade)s.transform.alpha=0f else s.transform.x=-end.width;renderer.updateTransform(s);val start=System.currentTimeMillis();val r=object:Runnable{override fun run(){val f=((System.currentTimeMillis()-start)/500f).coerceIn(0f,1f);if(fade)s.transform.alpha=end.alpha*f else s.transform.x=(-end.width)*(1-f)+end.x*f;renderer.updateTransform(s);if(f<1)handler.postDelayed(this,16)else{s.transform=end;workspace.autosave()}}};handler.post(r)}
  private fun showPresets(){val s=workspace.source();val names=workspace.presetNames();val items=arrayListOf("Save current").apply{addAll(names)};AlertDialog.Builder(this).setTitle("Presets").setItems(items.toTypedArray()){_,i->if(i==0&&s!=null)dialogs.text("Preset name",s.name){workspace.savePreset(it,s)}else if(i>0)workspace.loadPreset(items[i])?.let{p->workspace.scene()?.sources?.add(p);workspace.selectedSourceId=p.id;workspace.autosave();refreshSources();rebuildProgram()}}.show()}

  private fun showAudioRouting(){val s=workspace.source()?:return;val items=arrayOf("Stream output: ${s.streamAudio}","Record output: ${s.recordAudio}","Monitor: ${s.audioMonitor}","Track: ${s.audioTrack}","Delay: ${s.audioDelayMs}ms");AlertDialog.Builder(this).setTitle("Advanced Audio").setItems(items){_,i->workspace.checkpoint();when(i){0->s.streamAudio=!s.streamAudio;1->s.recordAudio=!s.recordAudio;2->s.audioMonitor=!s.audioMonitor;3->s.audioTrack=(s.audioTrack%6)+1;4->dialogs.text("Delay ms",s.audioDelayMs.toString()){s.audioDelayMs=it.toIntOrNull()?:0}};workspace.autosave();showAudioRouting()}.show()}
  private fun showAudioFilters(){val p=arrayOf("game","voice","broadcast","flat");AlertDialog.Builder(this).setTitle("Audio filters").setItems(p){_,i->service?.setAudioPreset(p[i]);toast(p[i])}.show()}
  private fun showMacros(){val items=arrayListOf("Add macro","Run MANUAL").apply{addAll(workspace.project.macros.map{(if(it.enabled)"✓ " else "○ ")+it.name})};AlertDialog.Builder(this).setTitle("Macros").setItems(items.toTypedArray()){_,i->when{i==0->addMacro();i==1->automation.run("MANUAL");else->{val m=workspace.project.macros[i-2];m.enabled=!m.enabled;workspace.autosave();showMacros()}}}.show()}
  private fun addMacro(){dialogs.text("Macro: trigger,action","MANUAL,replay"){v->val a=v.split(',',limit=2);if(a.size==2){workspace.project.macros+=StudioMacro(name="Macro ${workspace.project.macros.size+1}",trigger=a[0].trim().uppercase(),action=a[1].trim());workspace.autosave()}}}
  fun runMacros(trigger:String)=automation.run(trigger)

  private fun showProjectMenu(){val items=arrayOf("Export project","Import project","Autosave","Reset","Toggle Safe Area","Toggle Studio Mode");AlertDialog.Builder(this).setTitle("Project").setItems(items){_,i->when(i){0->exportLauncher.launch("MiniOBS_Project.json");1->importLauncher.launch(arrayOf("application/json","text/plain"));2->{workspace.autosave();toast("Autosaved")};3->{workspace.reset();refreshAll();rebuildProgram()};4->{workspace.project.safeArea=!workspace.project.safeArea;binding.transformOverlay.showSafeArea=workspace.project.safeArea;workspace.autosave()};5->{workspace.project.studioMode=!workspace.project.studioMode;workspace.autosave();toast("Studio Mode ${workspace.project.studioMode}")}}}.show()}
  private fun showOutput(){val p=workspace.project;dialogs.text("Output WxH FPS kbps", "${p.outputWidth}x${p.outputHeight} ${p.fps} ${p.bitrate/1000}"){v->val x=v.replace('x',' ').split(' ').filter{it.isNotBlank()};if(x.size>=4){workspace.checkpoint();p.outputWidth=x[0].toIntOrNull()?:p.outputWidth;p.outputHeight=x[1].toIntOrNull()?:p.outputHeight;p.fps=x[2].toIntOrNull()?:p.fps;p.bitrate=(x[3].toIntOrNull()?:p.bitrate/1000)*1000;workspace.autosave()}};handler.postDelayed({showEndpointDialog()},300)}
  private fun showEndpointDialog(){dialogs.text("RTMP URLs separated by |",getEndpoints().joinToString("|")){saveEndpoints(it.split('|'))}}
  private fun showPerformance(){val n=StudioPerformanceMode.entries.map{it.name}.toTypedArray();AlertDialog.Builder(this).setTitle("Performance").setItems(n){_,i->workspace.project.performanceMode=StudioPerformanceMode.entries[i];if(workspace.project.performanceMode==StudioPerformanceMode.BATTERY){workspace.project.fps=30;workspace.project.bitrate=workspace.project.bitrate.coerceAtMost(5_000_000)};workspace.autosave();toast(n[i])}.show()}
  private fun showPreflight(){val e=getEndpoints();val text=listOf(if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)"✓ Audio permission" else "✕ Audio permission",if(service?.isCaptureReady()==true)"✓ Capture ready" else "! Capture stopped",if(e.isNotEmpty())"✓ ${e.size} stream destination(s)" else "! No RTMP destination","✓ Project autosave enabled").joinToString("\n");AlertDialog.Builder(this).setTitle("Preflight").setMessage(text).setPositiveButton("OK",null).show()}

  private fun toggleStream(){val s=service?:return;if(s.isStreamingNow())s.stopRtmp()else{val ep=getEndpoints();if(ep.isEmpty()){toast("OutputでRTMP URLを設定してください");return};s.startMultiRtmp(ep)};automation.run("STREAM_TOGGLE");refreshControls()}
  private fun getEndpoints():List<String>=getSharedPreferences("studio_output",MODE_PRIVATE).getString("rtmp","")!!.lines().map{it.trim()}.filter{it.startsWith("rtmp://")||it.startsWith("rtmps://")}.take(4)
  private fun saveEndpoints(v:List<String>)=getSharedPreferences("studio_output",MODE_PRIVATE).edit().putString("rtmp",v.joinToString("\n")).apply()
  private fun requestCapture(){val p=mutableListOf(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33)p+=Manifest.permission.POST_NOTIFICATIONS;val m=p.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isEmpty())launchCapturePicker()else permissionLauncher.launch(m.toTypedArray())}
  private fun launchCapturePicker(){captureLauncher.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())}
  private fun requestCamera(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),919)}
  private fun ensureService(){if(!bound)bindService(Intent(this,ScreenStreamService::class.java),connection,BIND_AUTO_CREATE)}
  private fun attachPreview(){val s=service?:return;if(s.isCaptureReady()&&binding.programSurface.holder.surface.isValid)s.attachPreview(binding.programSurface)}
  override fun surfaceCreated(holder:SurfaceHolder)=attachPreview();override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int)=attachPreview();override fun surfaceDestroyed(holder:SurfaceHolder){service?.detachPreview()}
  private fun refreshControls(){val ready=service?.isCaptureReady()==true;val live=service?.isStreamingNow()==true;val rec=service?.isRecordingNow()==true;binding.btnCapture.text=if(ready)"Stop Capture" else "Start Capture";binding.btnStream.text=if(live)"Stop Streaming" else "Start Streaming";binding.btnRecord.text=if(rec)"Stop Recording" else "Start Recording";binding.txtStudioStatus.text=when{service?.isPrivacyMode()==true->"PRIVACY";live->"LIVE";rec->"REC";ready->"READY";else->"OFFLINE"};binding.txtStudioStatus.setTextColor(if(live||rec)Color.RED else if(ready)Color.GREEN else Color.LTGRAY)}
  private val statusTask=object:Runnable{override fun run(){runCatching{service?.remoteStatus()?.let{j->binding.txtHealth.text="${j.optString("resolution")} ${j.optInt("fps")}fps | ${j.optLong("bitrateKbps")}kbps | Drop ${j.optLong("droppedVideo")}/${j.optLong("droppedAudio")} | ${j.optDouble("temperature")}℃ | Battery ${j.optInt("battery")}%"}};if(workspace.project.studioMode)renderPreviewScene();handler.postDelayed(this,1400)}}
  private fun seek(f:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){if(u)f(p)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}}
  private class ItemSelected(val f:(Int)->Unit):android.widget.AdapterView.OnItemSelectedListener{override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long)=f(pos);override fun onNothingSelected(p:android.widget.AdapterView<*>?){}}
  private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
  override fun dispatchKeyEvent(e:KeyEvent):Boolean{if(e.action==KeyEvent.ACTION_DOWN){when(e.keyCode){KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{service?.toggleRecording();return true};KeyEvent.KEYCODE_BUTTON_A->{takePreviewToProgram();return true};KeyEvent.KEYCODE_BUTTON_B->{service?.togglePrivacy();return true}}};return super.dispatchKeyEvent(e)}
}
