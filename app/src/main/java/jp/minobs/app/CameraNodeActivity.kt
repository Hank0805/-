package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Turns any spare Android phone into a Wi-Fi camera source for the host Mini OBS. */
class CameraNodeActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var urlInput: EditText
    private lateinit var idSpinner: Spinner
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastSend = 0L
    private val enabled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("camera_node", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,18) }
        root.addView(TextView(this).apply { text = "Mini OBS Camera Node"; textSize = 24f })
        root.addView(TextView(this).apply { text = "ホスト端末のRemote URL（?code=付き）を貼り付けます。最大3台まで接続できます。" })
        urlInput = EditText(this).apply { hint = "http://192.168.x.x:8787/?code=123456"; setText(prefs.getString("url", "")) }
        root.addView(urlInput)
        idSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@CameraNodeActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Camera 1","Camera 2","Camera 3")) }
        root.addView(idSpinner)
        val btn = Button(this).apply { text = "カメラ送信を開始" }
        root.addView(btn)
        status = TextView(this).apply { text = "待機中" }
        root.addView(status)
        preview = TextureView(this).apply { surfaceTextureListener = this@CameraNodeActivity }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        btn.setOnClickListener {
            if (enabled.get()) { enabled.set(false); closeCamera(); btn.text = "カメラ送信を開始"; status.text = "停止" }
            else {
                val u = urlInput.text.toString().trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) { toast("Remote URLを貼り付けてください"); return@setOnClickListener }
                prefs.edit().putString("url", u).apply()
                enabled.set(true); btn.text = "カメラ送信を停止"
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 22)
                else if (preview.isAvailable) openCamera()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && enabled.get() && preview.isAvailable) openCamera()
        else if (requestCode == 22) { enabled.set(false); toast("カメラ権限が必要です") }
    }

    private fun openCamera() {
        if (!enabled.get() || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        closeCamera()
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: return
        reader = ImageReader.newInstance(640, 360, android.graphics.ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastSend >= 250 && enabled.get()) {
                    lastSend = now
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    worker.execute { postFrame(bytes) }
                }
                img.close()
            }, null)
        }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { camera = device; createSession(device) }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; runOnUiThread { status.text = "Camera error $error" } }
        }, null)
    }

    private fun createSession(device: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(640, 360)
        val display = Surface(texture)
        val jpeg = reader?.surface ?: return
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(display); addTarget(jpeg)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        device.createCaptureSession(listOf(display, jpeg), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; runCatching { s.setRepeatingRequest(req.build(), null, null) }; runOnUiThread { status.text = "送信中…" } }
            override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.text = "Camera session failed" } }
        }, null)
    }

    private fun postFrame(jpeg: ByteArray) {
        val raw = urlInput.text.toString().trim()
        val code = Regex("[?&]code=([^&]+)").find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val base = raw.substringBefore('?').trimEnd('/')
        val node = idSpinner.selectedItemPosition + 1
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/api/camera/frame?code=$code&id=$node").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 1500; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg"); conn.setFixedLengthStreamingMode(jpeg.size)
            conn.outputStream.use { it.write(jpeg) }
            val ok = conn.responseCode in 200..299
            runOnUiThread { status.text = if (ok) "Camera $node 送信中" else "Host error ${conn.responseCode}" }
        } catch (_: Exception) { runOnUiThread { status.text = "ホストへ再接続中…" } }
        finally { conn?.disconnect() }
    }

    private fun closeCamera() {
        runCatching { session?.close() }; runCatching { camera?.close() }; runCatching { reader?.close() }
        session = null; camera = null; reader = null
    }

    override fun onDestroy() { enabled.set(false); closeCamera(); worker.shutdownNow(); super.onDestroy() }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (enabled.get()) openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
