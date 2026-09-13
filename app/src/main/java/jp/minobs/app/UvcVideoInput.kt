package jp.minobs.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

class UvcVideoInput(private val context: Context) {
  private var helper: CameraHelper? = null
  private var surface: Surface? = null
  private var selected: UsbDevice? = null

  fun createFilter(source: StudioSource, onStatus: (String) -> Unit = {}): StudioSurfaceFilterRender {
    stop()
    return StudioSurfaceFilterRender { texture ->
      texture.setDefaultBufferSize(1920, 1080)
      val output = Surface(texture)
      surface = output
      val camera = CameraHelper()
      helper = camera
      camera.setStateCallback(object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
          if (selected == null && matches(device, source.data)) {
            selected = device
            onStatus("USB映像デバイスを検出: ${device.productName ?: device.deviceName}")
            camera.selectDevice(device)
          }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
          selected = device
          runCatching { camera.openCamera(bestSize(camera.getSupportedSizeList())) }
            .onFailure { runCatching { camera.openCamera() } }
        }

        override fun onCameraOpen(device: UsbDevice) {
          runCatching {
            camera.startPreview()
            camera.addSurface(output, false)
            val size = camera.previewSize
            onStatus("USBキャプチャ開始 ${size?.width ?: 0}x${size?.height ?: 0}")
          }.onFailure { onStatus("USBキャプチャ開始失敗: ${it.message}") }
        }

        override fun onCameraClose(device: UsbDevice) {
          runCatching { camera.removeSurface(output) }
          onStatus("USBキャプチャ停止")
        }

        override fun onDeviceClose(device: UsbDevice) = Unit
        override fun onDetach(device: UsbDevice) {
          if (selected?.deviceId == device.deviceId) {
            selected = null
            onStatus("USBキャプチャが取り外されました")
          }
        }
        override fun onCancel(device: UsbDevice) { onStatus("USBカメラ権限がキャンセルされました") }
      })

      val devices = camera.deviceList.orEmpty()
      val target = devices.firstOrNull { matches(it, source.data) } ?: devices.firstOrNull()
      if (target != null) {
        selected = target
        camera.selectDevice(target)
      } else {
        onStatus("UVC対応USBキャプチャが見つかりません")
      }
    }.also { it.applySource(source) }
  }

  private fun matches(device: UsbDevice, spec: String): Boolean {
    if (!spec.startsWith("usb:", true)) return true
    val parts = spec.split(':')
    val vendor = parts.getOrNull(1)?.toIntOrNull() ?: return true
    val product = parts.getOrNull(2)?.toIntOrNull()
    return device.vendorId == vendor && (product == null || device.productId == product)
  }

  private fun bestSize(sizes: List<Size>?): Size? {
    if (sizes.isNullOrEmpty()) return null
    return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
      ?: sizes.firstOrNull { it.width == 1280 && it.height == 720 }
      ?: sizes.maxByOrNull { it.width * it.height }
  }

  fun stop() {
    val h = helper
    val s = surface
    if (h != null && s != null) runCatching { h.removeSurface(s) }
    runCatching { h?.stopPreview() }
    runCatching { h?.closeCamera() }
    runCatching { h?.release() }
    runCatching { s?.release() }
    helper = null
    surface = null
    selected = null
  }

  companion object {
    data class DeviceDiagnostic(
      val name: String,
      val vendorId: Int,
      val productId: Int,
      val usbClass: Int,
      val hasUsbAudioInput: Boolean
    )

    fun diagnostics(context: Context): List<DeviceDiagnostic> {
      val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
      val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val usbAudio = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
      return usb.deviceList.values.map { device ->
        DeviceDiagnostic(
          name = device.productName ?: device.deviceName,
          vendorId = device.vendorId,
          productId = device.productId,
          usbClass = device.deviceClass,
          hasUsbAudioInput = usbAudio.isNotEmpty()
        )
      }
    }
  }
}
