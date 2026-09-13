from pathlib import Path
import re

service_path = Path('app/src/main/java/jp/minobs/app/ScreenStreamService.kt')
s = service_path.read_text()

field_anchor = '    private val audioEffect = StudioAudioEffect()\n'
if 'private val encodedTransition = StudioEncodedTransition()' not in s:
    s = s.replace(field_anchor, field_anchor + '''    private val encodedTransition = StudioEncodedTransition()\n    private val multitrack by lazy { StudioMultiTrackRecorder(File(cacheDir, "MiniOBS_multitrack")) }\n    private var multitrackActive = false\n''')

scene_anchor = '    fun setActiveScene(scene: String) {'
if 'fun runEncodedTransition(' not in s:
    s = s.replace(scene_anchor, '''    fun runEncodedTransition(transition: StudioTransition, durationMs: Int, applyScene: () -> Unit) {\n        val engine = stream\n        if (!captureReady || engine == null || transition == StudioTransition.CUT) {\n            applyScene()\n            return\n        }\n        encodedTransition.run(engine, transition, durationMs, applyScene)\n    }\n\n''' + scene_anchor)

new_toggle = r'''    fun toggleRecording(): Boolean {
        val engine = stream ?: return false
        if (!captureReady) return false
        return try {
            if (!normalRecording) {
                replayWasEnabled = replay.pauseForNormalRecord()
                val folder = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MiniOBS").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(folder, "MiniOBS_$stamp.mp4")
                engine.startRecord(file.absolutePath) { _ -> }
                multitrackActive = studioMixer != null && multitrack.start()
                if (multitrackActive) {
                    studioMixer?.onRawTracks = { mic, internal, usb, timestampUs ->
                        multitrack.write(mic, internal, usb, timestampUs)
                    }
                }
                normalRecording = true
                recordingFile = file
                recordingStartedAt = System.currentTimeMillis()
                journal.beginRecording(file.absolutePath)
                sendStatus(if (multitrackActive) "録画中: ${file.name} / マルチトラック" else "録画中: ${file.name}")
            } else {
                if (engine.isRecording) engine.stopRecord()
                studioMixer?.onRawTracks = null
                multitrack.stopRawCapture()
                val baseFile = recordingFile
                val finalizeMulti = multitrackActive && baseFile != null
                multitrackActive = false
                normalRecording = false
                journal.finishRecording()
                recordingStartedAt = 0L
                recordingFile = null
                if (finalizeMulti && baseFile != null) {
                    val finalFile = File(baseFile.parentFile, baseFile.nameWithoutExtension + "_multitrack.mp4")
                    sendStatus("マルチトラックMP4を作成中…")
                    Thread({
                        val result = multitrack.finalizeRecording(baseFile, finalFile)
                        mainHandler.post {
                            if (result != null) {
                                exportVideoToGallery(result.file, result.file.name)
                                runCatching { baseFile.delete() }
                                sendStatus("録画保存: ${result.file.name} / 音声${result.audioTracks}トラック")
                            } else {
                                exportVideoToGallery(baseFile, baseFile.name)
                                sendStatus("マルチトラック化に失敗。通常録画を保存しました")
                            }
                        }
                    }, "MiniOBS-MultiTrackMux").start()
                } else {
                    baseFile?.let { exportVideoToGallery(it, it.name) }
                    sendStatus("録画保存: ${baseFile?.name ?: "完了"}")
                    multitrack.cancel()
                }
                if (replayWasEnabled) {
                    replay.resumeAfterNormalRecord()
                    replayWasEnabled = false
                }
            }
            true
        } catch (e: Exception) {
            studioMixer?.onRawTracks = null
            multitrack.cancel()
            multitrackActive = false
            sendStatus("録画エラー: ${e.message ?: "不明なエラー"}")
            false
        }
    }

'''
pattern = re.compile(r'    fun toggleRecording\(\): Boolean \{.*?\n    \}\n\n(?=    private fun internalStartRecord)', re.S)
s, count = pattern.subn(new_toggle, s, count=1)
if count != 1:
    raise SystemExit(f'toggleRecording replacement failed: {count}')

# Ensure multitrack capture is released during shutdown.
old_stop = '        replay.stop(); autoScene.stop(); chatManager.stopAll(); remoteServer?.stop(); remoteServer = null\n'
new_stop = old_stop + '        studioMixer?.onRawTracks = null; multitrack.cancel(); multitrackActive = false\n'
if new_stop not in s:
    s = s.replace(old_stop, new_stop)

# Surface multitrack state to Remote Studio/health JSON.
old_status = '            "destinations" to lastEndpoints.size, "replay" to replay.status(), "droppedVideo" to droppedVideo, "droppedAudio" to droppedAudio,\n'
new_status = '            "destinations" to lastEndpoints.size, "replay" to replay.status(), "multitrack" to multitrackActive, "droppedVideo" to droppedVideo, "droppedAudio" to droppedAudio,\n'
if old_status in s:
    s = s.replace(old_status, new_status)

service_path.write_text(s)

activity_path = Path('app/src/main/java/jp/minobs/app/ObsStudioActivity.kt')
a = activity_path.read_text()
new_take = r'''  private fun takePreviewToProgram() {
    val id = if (workspace.project.studioMode) {
      workspace.project.previewSceneId
    } else {
      workspace.selectedSceneId
    }
    if (id.isBlank()) return

    workspace.project.programSceneId = id
    workspace.selectedSceneId = id
    workspace.autosave()

    val transition = workspace.project.transition
    val duration = workspace.project.transitionMs.coerceIn(80, 5000)
    if (transition == StudioTransition.CUT) {
      rebuildProgram()
    } else {
      val s = service
      if (s != null && s.isCaptureReady()) {
        s.runEncodedTransition(transition, duration) {
          rebuildProgram()
        }
      } else {
        // Offline editor fallback: animate the local program surface only.
        binding.programFrame.animate()
          .alpha(.15f)
          .setDuration((duration / 2L).coerceAtLeast(40L))
          .withEndAction {
            rebuildProgram()
            binding.programFrame.animate().alpha(1f).setDuration((duration / 2L).coerceAtLeast(40L)).start()
          }.start()
      }
    }
    automation.run("SCENE_CHANGE")
    refreshAll()
  }

'''
pattern_take = re.compile(r'  private fun takePreviewToProgram\(\) \{.*?\n  \}\n\n(?=  private fun renderPreviewScene)', re.S)
a, count = pattern_take.subn(new_take, a, count=1)
if count != 1:
    raise SystemExit(f'takePreviewToProgram replacement failed: {count}')
activity_path.write_text(a)
