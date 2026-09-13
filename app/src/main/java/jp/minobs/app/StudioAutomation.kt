package jp.minobs.app

import android.os.Handler
import android.os.Looper

class StudioAutomation(
  private val workspace: StudioWorkspace,
  private val serviceProvider: () -> ScreenStreamService?,
  private val onSceneRequest: (String) -> Unit,
  private val onToast: (String) -> Unit
) {
  private val handler = Handler(Looper.getMainLooper())
  private var micMuted = false

  fun run(trigger: String) {
    workspace.project.macros.filter { it.enabled && it.trigger.equals(trigger, true) }.forEach { macro ->
      handler.postDelayed({ action(macro.action) }, macro.delayMs)
    }
  }

  fun smartAction() {
    val service = serviceProvider()
    if (service?.isCaptureReady() != true) { onToast("最初にCaptureを開始してください"); return }
    workspace.project.scenes.firstOrNull { it.name.equals("WAIT", true) }?.let { onSceneRequest(it.id) }
    onToast("3秒後にGAME + REC")
    handler.postDelayed({
      workspace.project.scenes.firstOrNull { it.name.equals("GAME", true) }?.let { onSceneRequest(it.id) }
      if (service.isRecordingNow() != true) service.toggleRecording()
      run("SMART")
    }, 3000)
  }

  private fun action(value: String) {
    val service = serviceProvider() ?: return
    when {
      value.equals("record", true) -> service.toggleRecording()
      value.equals("replay", true) -> service.saveReplay()
      value.equals("panic", true) -> service.togglePrivacy()
      value.equals("mute", true) -> { micMuted = !micMuted; service.setMicrophoneMuted(micMuted) }
      value.startsWith("scene:", true) -> workspace.project.scenes.firstOrNull { it.name.equals(value.substringAfter(':'), true) }?.let { onSceneRequest(it.id) }
    }
  }
}
