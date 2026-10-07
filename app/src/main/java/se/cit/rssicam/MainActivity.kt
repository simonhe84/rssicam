package se.cit.rssicam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import org.json.JSONObject
import se.cit.rssicam.databinding.ActivityMainBinding
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiScanner: WifiScanner
    private lateinit var bleScanner: BleScanner
    private lateinit var store: CaptureStore

    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    private val captureRequested = AtomicBoolean(false)
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // display geometry
    @Volatile private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var sensorOrientation = 90

    // state shown on screen
    @Volatile private var lastPose: Pose? = null
    @Volatile private var trackingState: TrackingState = TrackingState.STOPPED
    @Volatile private var trackingReason: String = ""
    @Volatile private var refPose: Pose? = null
    @Volatile private var sessionName: String = "room1"
    private var captureCount = 0
    private var lastUiUpdate = 0L

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        wifiScanner = WifiScanner(this)
        bleScanner = BleScanner(this)
        store = CaptureStore(this)

        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        binding.sessionName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { sessionName = s?.toString() ?: "" }
        })
        sessionName = binding.sessionName.text.toString()

        binding.captureButton.setOnClickListener {
            if (session == null) { toast("ARCore 未就绪"); return@setOnClickListener }
            wifiScanner.requestScan()
            captureRequested.set(true)
        }
        binding.setRefButton.setOnClickListener {
            val p = lastPose
            if (p == null || trackingState != TrackingState.TRACKING) { toast("还没有稳定跟踪，无法标记参考点"); return@setOnClickListener }
            refPose = p
            toast("参考点已记录：之后所有坐标同时给出相对参考点的位姿")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMS)
            return
        }
        startEverything()
    }

    private fun startEverything() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                    else -> {}
                }
                val s = Session(this)
                configureSession(s)
                session = s
            } catch (e: UnavailableException) {
                toast("ARCore 不可用: ${e.javaClass.simpleName}")
                return
            } catch (e: Exception) {
                toast("创建 ARCore 会话失败: ${e.message}")
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            toast("相机被占用"); session = null; return
        }
        binding.surfaceView.onResume()
        viewportChanged = true
        wifiScanner.start()
        bleScanner.start()
    }

    private fun configureSession(s: Session) {
        // Pick the camera config with the largest CPU image (used for the photo) at 30 fps.
        val filter = CameraConfigFilter(s).setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        val configs = s.getSupportedCameraConfigs(filter)
        val best = configs.maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
        if (best != null) s.cameraConfig = best

        val cfg = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
        }
        s.configure(cfg)

        // sensor orientation of the camera ARCore uses -> for EXIF orientation
        runCatching {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val ch = cm.getCameraCharacteristics(s.cameraConfig.cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            binding.surfaceView.onPause()
            session?.pause()
        }
        wifiScanner.stop()
        bleScanner.stop()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ permissions

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.NEARBY_WIFI_DEVICES
        return list.toTypedArray()
    }

    private fun hasAllPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (hasAllPermissions()) startEverything()
            else toast("需要相机、位置、蓝牙、附近WiFi权限才能工作")
        }
    }

    // ------------------------------------------------------------ GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewportChanged) {
            s.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        s.setCameraTextureName(background.textureId)
        val frame = try { s.update() } catch (e: CameraNotAvailableException) { return }
        background.draw(frame)

        val camera = frame.camera
        trackingState = camera.trackingState
        trackingReason = if (trackingState != TrackingState.TRACKING) camera.trackingFailureReason.toString() else ""
        if (trackingState == TrackingState.TRACKING) lastPose = camera.pose

        if (captureRequested.compareAndSet(true, false)) {
            doCapture(frame, camera.pose, camera.displayOrientedPose)
        }

        val now = System.currentTimeMillis()
        if (now - lastUiUpdate > 250) {
            lastUiUpdate = now
            runOnUiThread { updateStatus() }
        }
    }

    private fun doCapture(frame: com.google.ar.core.Frame, pose: Pose, displayPose: Pose) {
        val tracking = trackingState == TrackingState.TRACKING
        val image = try { frame.acquireCameraImage() } catch (e: Exception) {
            runOnUiThread { toast("取图失败: ${e.message}") }; return
        }
        val yuv = try { store.copyImage(image) } finally { image.close() }

        val intr = frame.camera.imageIntrinsics
        val meta = JSONObject()
        meta.put("app", "RSSICam")
        meta.put("version", 1)
        meta.put("time_ms", System.currentTimeMillis())
        meta.put("time_iso", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(java.util.Date()))
        meta.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        val sessionName = this.sessionName
        meta.put("session", sessionName)
        meta.put("tracking", trackingState.toString())
        if (!tracking) meta.put("tracking_reason", trackingReason)
        meta.put("frame_ns", frame.timestamp)
        meta.put("pose", poseJson(pose))
        meta.put("display_pose", poseJson(displayPose))
        refPose?.let { r ->
            meta.put("ref_pose", poseJson(r))
            meta.put("pose_rel_ref", poseJson(r.inverse().compose(pose)))
        }
        meta.put("display_rotation_deg", displayRotation() * 90)
        meta.put("sensor_orientation_deg", sensorOrientation)
        meta.put("intrinsics", JSONObject().apply {
            put("fx", intr.focalLength[0]); put("fy", intr.focalLength[1])
            put("cx", intr.principalPoint[0]); put("cy", intr.principalPoint[1])
            put("width", intr.imageDimensions[0]); put("height", intr.imageDimensions[1])
        })
        meta.put("wifi", wifiScanner.snapshot())
        meta.put("ble", bleScanner.snapshot())

        val exifOrientation = exifOrientationFor(sensorOrientation, displayRotation() * 90)
        ioExecutor.execute {
            try {
                val r = store.save(sessionName, yuv, meta, exifOrientation)
                runOnUiThread {
                    captureCount++
                    toast("已保存 ${r.fileName}  (EXIF ${r.exifBytes} B, wifi ${meta.getJSONObject("wifi").optInt("count")}, ble ${meta.getJSONObject("ble").optInt("count")})" +
                            if (!tracking) "\n注意: 拍照时未在跟踪状态" else "")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("保存失败: ${e.message}") }
            }
        }
    }

    private fun poseJson(p: Pose): JSONObject = JSONObject().apply {
        put("tx", p.tx().toDouble()); put("ty", p.ty().toDouble()); put("tz", p.tz().toDouble())
        put("qx", p.qx().toDouble()); put("qy", p.qy().toDouble()); put("qz", p.qz().toDouble()); put("qw", p.qw().toDouble())
    }

    /** EXIF orientation so the JPEG (sensor-oriented) displays upright. */
    private fun exifOrientationFor(sensorOrientationDeg: Int, displayRotationDeg: Int): Int {
        val rot = (sensorOrientationDeg - displayRotationDeg + 360) % 360
        return when (rot) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {   // Surface.ROTATION_0..3
        return if (Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    private fun updateStatus() {
        val p = lastPose
        val sb = StringBuilder()
        sb.append("Tracking: ").append(trackingState)
        if (trackingReason.isNotEmpty()) sb.append(" (").append(trackingReason).append(')')
        sb.append('\n')
        if (p != null) {
            sb.append(String.format(Locale.US, "xyz = %.2f  %.2f  %.2f m\n", p.tx(), p.ty(), p.tz()))
            refPose?.let { r ->
                val rel = r.inverse().compose(p)
                sb.append(String.format(Locale.US, "rel. ref = %.2f  %.2f  %.2f m\n", rel.tx(), rel.ty(), rel.tz()))
            }
        }
        val wifi = wifiScanner.snapshot()
        val ble = bleScanner.snapshot()
        sb.append("WiFi APs: ").append(wifi.optInt("count"))
            .append("  (scan age ").append(wifi.optLong("last_scan_age_ms") / 1000).append(" s)\n")
        sb.append("BLE devs: ").append(ble.optInt("count"))
        if (!ble.optBoolean("scanning")) sb.append("  [BLE 未扫描/蓝牙关闭?]")
        sb.append("\nCaptured: ").append(captureCount)
        binding.statusText.text = sb.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1001
    }
}
