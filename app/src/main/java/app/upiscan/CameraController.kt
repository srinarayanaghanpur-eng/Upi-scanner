package app.upiscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 controller. Opens exactly the lens it is told to open. On any failure it reports the error
 * and stays closed — it never falls back to a different camera.
 */
class CameraController(
    private val ctx: Context,
    private val tv: CoverTextureView,
    private val l: Listener
) {
    interface Listener {
        fun onFrame(img: Image, rotation: Int)   // listener MUST close img
        fun onState(msg: String, error: Boolean)
        fun onTorchChanged(on: Boolean, available: Boolean) {}
    }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var h: Handler? = null

    private var wanted: CamEntry? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var opening = false
    private var torch = false
    private var hasAf = false
    private var hasFlash = false
    private var rotation = 90

    private fun handler(): Handler {
        if (thread == null) {
            thread = HandlerThread("cam").also { it.start() }
            h = Handler(thread!!.looper)
        }
        return h!!
    }

    fun show(e: CamEntry) = handler().post { wanted = e; if (device != null) closeDevice() else openWanted() }
    fun closeCamera() { h?.post { wanted = null; closeDevice() } }
    fun surfaceReady() { h?.post { openWanted() } }

    /**
     * Torch is controlled through CameraManager.setTorchMode, which is far more reliable across
     * OEMs than FLASH_MODE_TORCH in the repeating request. The FLASH_MODE is still kept in sync as
     * a fallback.
     */
    fun setTorch(on: Boolean) {
        h?.post {
            val e = wanted
            if (e == null || !hasFlash) {
                torch = false
                ui.post { l.onTorchChanged(false, false) }
                return@post
            }
            torch = on
            try { mgr.setTorchMode(e.openId, on) } catch (_: Exception) { }
            startRepeating()
            ui.post { l.onTorchChanged(on, true) }
        }
    }

    /** Called from onPause: close the camera but keep the handler thread alive so callbacks land. */
    fun stop() {
        h?.post { wanted = null; closeDevice() }
    }

    /** Called from onDestroy: tear everything down. */
    fun shutdown() {
        h?.post { wanted = null; closeDevice() }
        thread?.quitSafely()
        thread = null
        h = null
    }

    private fun err(msg: String) { ui.post { l.onState(msg, true) } }

    private fun releaseOutputs() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    private fun closeDevice() {
        releaseOutputs()
        try { device?.close() } catch (_: Exception) {}
    }

    private fun openWanted() {
        val e = wanted ?: return
        if (device != null || opening) return
        if (!tv.isAvailable) return    // surfaceReady() will call us again
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            err("Camera permission not granted"); return
        }
        opening = true
        try {
            mgr.openCamera(e.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    opening = false
                    device = c
                    if (wanted?.key != e.key) { c.close(); return }   // selection changed while opening
                    createSession(c, e)
                }
                override fun onDisconnected(c: CameraDevice) {
                    wanted = null; releaseOutputs(); c.close()
                    err("${e.title} disconnected (in use by another app?)")
                }
                override fun onError(c: CameraDevice, code: Int) {
                    wanted = null; releaseOutputs(); c.close()
                    err("${e.title} failed to open (Camera2 error $code)")
                }
                override fun onClosed(c: CameraDevice) {
                    if (device === c) device = null
                    releaseOutputs()
                    openWanted()
                }
            }, h)
        } catch (ex: Exception) {
            opening = false; wanted = null
            err("Cannot open ${e.title}: ${ex.message}")
        }
    }

    private fun createSession(c: CameraDevice, e: CamEntry) {
        try {
            val chars = try { mgr.getCameraCharacteristics(e.charsId) } catch (_: Exception) { mgr.getCameraCharacteristics(e.openId) }
            val openChars = try { mgr.getCameraCharacteristics(e.openId) } catch (_: Exception) { chars }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            hasAf = e.hasAf
            hasFlash = openChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

            val ps = pickPreview(map.getOutputSizes(SurfaceTexture::class.java))
            val rs = pickReader(map.getOutputSizes(ImageFormat.YUV_420_888), ps)
            val ratio = if (rotation % 180 == 90) ps.height.toFloat() / ps.width else ps.width.toFloat() / ps.height
            ui.post { tv.setImageRatio(ratio) }

            val st = tv.surfaceTexture ?: throw IllegalStateException("no preview surface")
            st.setDefaultBufferSize(ps.width, ps.height)
            surface = Surface(st)
            val r = ImageReader.newInstance(rs.width, rs.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) l.onFrame(img, rotation)
            }, h)
            reader = r

            val outs = listOf(surface!!, r.surface).map { s ->
                OutputConfiguration(s).also { oc -> e.physicalId?.let { oc.setPhysicalCameraId(it) } }
            }
            val ex = Executor { cmd -> h?.post(cmd) }
            c.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, ex,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (device !== c) return
                        session = s
                        startRepeating()
                        ui.post { l.onState("Scanning", false) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        wanted = null
                        err("${e.title} could not be configured (stream combination rejected by the device)")
                        closeDevice()
                    }
                }))
        } catch (ex: Exception) {
            wanted = null
            err("Cannot start ${e.title}: ${ex.message}")
            closeDevice()
        }
    }

    private fun startRepeating() {
        val c = device ?: return
        val s = session ?: return
        val sf = surface ?: return
        val rd = reader ?: return
        try {
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(sf); b.addTarget(rd.surface)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE,
                if (hasAf) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            s.setRepeatingRequest(b.build(), null, h)
        } catch (ex: Exception) { err("Preview request failed: ${ex.message}") }
    }

    private fun area(s: Size) = s.width * s.height

    private fun pickPreview(sizes: Array<Size>): Size =
        sizes.filter { it.width <= 1920 && it.width * 3 == it.height * 4 }.maxByOrNull(::area)
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull(::area) ?: sizes.first()

    // Larger than typical because close-range / wide-angle QR codes occupy fewer pixels.
    private fun pickReader(sizes: Array<Size>, ps: Size): Size {
        val r = ps.width.toFloat() / ps.height
        return sizes.filter { abs(it.width.toFloat() / it.height - r) < 0.02 && it.width <= 1600 }.maxByOrNull(::area)
            ?: sizes.filter { it.width <= 1600 }.maxByOrNull(::area) ?: sizes.first()
    }
}
