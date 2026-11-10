package app.upiscan

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** Design tokens — "Modern Dark / cinema" glass palette. */
private object Ink {
    val BG = 0xFF050506.toInt()
    val TEXT = 0xFFEDEDEF.toInt()
    val TEXT_DIM = 0xFFC6C9D0.toInt()
    val MUTED = 0xFF8A8F98.toInt()
    val ACCENT = 0xFF5E6AD2.toInt()
    val ACCENT_LIGHT = 0xFF9AA3FF.toInt()
    val DANGER = 0xFFEF4444.toInt()
    val AMBER = 0xFFFFC107.toInt()
    val BORDER = 0x33FFFFFF
    val BORDER_SOFT = 0x1FFFFFFF
    val TOP_GLASS = 0x26FFFFFF
    val BOTTOM_GLASS = 0x0DFFFFFF
}

/** A payment app we can hand the UPI link to. */
private class UpiApp(val label: String, val icon: Drawable?, val launch: () -> Unit)

/** Known UPI handlers, probed as a fallback when the intent query finds nothing. */
private val KNOWN_UPI_PACKAGES = listOf(
    "com.phonepe.app",                          // PhonePe
    "in.org.npci.upiapp",                       // BHIM
    "com.fampay.in",                            // FamPay
    "com.google.android.apps.nbu.paisa.user",   // Google Pay
    "net.one97.paytm",                          // Paytm
    "com.dreamplug.androidapp",                 // CRED
    "com.whatsapp",                             // WhatsApp Pay
    "com.mobikwik_new",                         // MobiKwik
    "com.freecharge.android",                   // Freecharge
    "com.amazon.mShop.android.shopping"         // Amazon Pay
)

class MainActivity : AppCompatActivity(), CameraController.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var tv: CoverTextureView
    private lateinit var cam: CameraController
    private lateinit var status: TextView
    private lateinit var errorView: TextView
    private lateinit var flashBtn: GlassIconButton
    private lateinit var segment: SegmentBar

    private var catalog: List<CamEntry> = emptyList()
    private var mode = LensMode.MAIN
    private var testing: CamEntry? = null
    private var current: CamEntry? = null
    private var torch = false
    private var locked = false
    private var lastRaw = ""
    private var lastRawAt = 0L
    private var lastAnalysis = 0L
    private val analyzing = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private var statusText = "Starting…"
    private val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    private val scanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startSelected() else showError("Camera permission denied. Grant it in system settings to scan.")
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanGallery(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("upiscan", MODE_PRIVATE)
        mode = LensMode.values().firstOrNull { it.key == prefs.getString("mode", "main") } ?: LensMode.MAIN
        buildUi()
        cam = CameraController(this, tv, this)
    }

    override fun onDestroy() {
        cam.shutdown()
        super.onDestroy()
    }

    // ---- glass helpers -------------------------------------------------------
    private fun glass(radius: Int, top: Int = Ink.TOP_GLASS, bottom: Int = Ink.BOTTOM_GLASS, border: Int = Ink.BORDER) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = dpF(radius.toFloat())
            setStroke(dp(1), border)
        }

    private fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(ease).start()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(ease).start()
            }
            false
        }
    }

    private fun staggerIn(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay((minOf(i, 8) * 38).toLong())
                .setDuration(300).setInterpolator(ease).start()
        }
    }

    // ---- UI ------------------------------------------------------------------
    private fun buildUi() {
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Ink.BG) }

        tv = CoverTextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { cam.surfaceReady() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addView(tv, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(AmbientGlowView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(ScanFrameView(this), android.widget.FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
        }
        status = TextView(this).apply {
            setTextColor(Ink.TEXT); textSize = 13f; text = statusText
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = glass(22)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })

        flashBtn = GlassIconButton(this, GlassIconButton.Kind.TORCH).apply {
            setOnClickListener { cam.setTorch(!torch) }
        }
        val galleryBtn = GlassIconButton(this, GlassIconButton.Kind.GALLERY).apply {
            setOnClickListener { if (!locked) pickImage.launch("image/*") }
        }
        val infoBtn = GlassIconButton(this, GlassIconButton.Kind.INFO).apply {
            setOnClickListener { showDiagnostics() }
        }
        for (b in listOf(flashBtn, galleryBtn, infoBtn)) {
            b.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            press(b)
            top.addView(b)
        }
        root.addView(top, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        errorView = TextView(this).apply {
            setTextColor(Ink.TEXT); textSize = 12.5f; visibility = View.GONE
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x40EF4444, 0x26EF4444)
            ).apply { cornerRadius = dpF(18f); setStroke(dp(1), 0x66EF4444) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxLines = 4
        }
        bottom.addView(errorView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        segment = SegmentBar(this).apply {
            labels = LensMode.values().map { it.label }
            onSelect = { i -> selectMode(LensMode.values()[i]) }
        }
        bottom.addView(segment, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(bottom, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)

        segment.select(LensMode.values().indexOf(mode), animate = false)
        paintFlash()
    }

    private fun paintFlash() { flashBtn.active = torch }

    private fun selectMode(m: LensMode) {
        if (testing == null && m == mode) return
        mode = m; testing = null
        prefs.edit().putString("mode", m.key).apply()
        segment.select(LensMode.values().indexOf(m), animate = true)
        startSelected()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startSelected()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() { cam.stop(); super.onPause() }

    private fun startSelected() {
        torch = false; paintFlash()
        catalog = CameraCatalog.scan(this)
        val e = testing ?: CameraCatalog.resolve(catalog, mode, prefs)
        current = e
        if (e == null) {
            cam.closeCamera()
            tv.visibility = View.INVISIBLE
            showError("${mode.label} isn't available: this phone doesn't expose a matching lens to apps, " +
                "or it couldn't be identified. Tap ⓘ to inspect cameras and assign one manually.")
            return
        }
        hideError(); tv.visibility = View.VISIBLE
        setStatus("Opening ${label()}…")
        cam.show(e)
    }

    private fun label() = testing?.let { "test ${it.title}" } ?: "${mode.label} (${current?.title})"

    // ---- CameraController.Listener ----
    override fun onState(msg: String, error: Boolean) {
        if (error) { tv.visibility = View.INVISIBLE; showError(msg) }
        else { hideError(); tv.visibility = View.VISIBLE; setStatus("Scanning · ${label()}") }
    }

    override fun onTorchChanged(on: Boolean, available: Boolean) {
        torch = if (available) on else false
        paintFlash()
        if (!available) Toast.makeText(this, "This lens has no flash", Toast.LENGTH_SHORT).show()
    }

    override fun onFrame(img: Image, rotation: Int) {
        val now = SystemClock.elapsedRealtime()
        if (locked || now - lastAnalysis < 100 || !analyzing.compareAndSet(false, true)) { img.close(); return }
        lastAnalysis = now
        try {
            scanner.process(InputImage.fromMediaImage(img, rotation))
                .addOnSuccessListener { list -> for (b in list) { b.rawValue?.let { onQr(it, false) }; if (locked) break } }
                .addOnCompleteListener { img.close(); analyzing.set(false) }
        } catch (e: Exception) { img.close(); analyzing.set(false) }
    }

    // ---- QR handling ----
    private fun onQr(raw: String, force: Boolean) {
        if (locked) return
        val now = SystemClock.elapsedRealtime()
        if (!force && raw == lastRaw && now - lastRawAt < 2500) return
        lastRaw = raw; lastRawAt = now
        when (val r = UpiParser.parse(raw)) {
            is ParseResult.Ok -> { locked = true; showPayment(r.req) }
            is ParseResult.Bad -> flash(r.reason)
        }
    }

    private fun scanGallery(uri: Uri) {
        try {
            scanner.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { l ->
                    val raw = l.firstOrNull { it.rawValue != null }?.rawValue
                    if (raw == null) flash("No QR code found in image") else onQr(raw, true)
                }
                .addOnFailureListener { flash("Could not read image") }
        } catch (e: Exception) { flash("Could not read image") }
    }

    private fun setStatus(t: String) { statusText = t; status.text = t }

    private fun flash(msg: String) {
        status.text = msg
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ status.text = statusText }, 2000)
    }

    private fun showError(m: String) {
        errorView.text = m
        if (errorView.visibility != View.VISIBLE) {
            errorView.visibility = View.VISIBLE
            errorView.alpha = 0f
            errorView.translationY = dp(10).toFloat()
            errorView.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(ease).start()
        }
        setStatus("Camera unavailable")
    }

    private fun hideError() {
        if (errorView.visibility == View.VISIBLE) {
            errorView.animate().alpha(0f).setDuration(150).withEndAction { errorView.visibility = View.GONE }.start()
        }
    }

    // ---- UPI app discovery ---------------------------------------------------
    private fun resolveUpiApps(raw: String): List<UpiApp> {
        val pm = packageManager
        val uri = Uri.parse(raw)
        val seen = LinkedHashMap<String, UpiApp>()
        val variants = listOf(
            Intent(Intent.ACTION_VIEW, uri),
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        )
        for (base in variants) {
            for (flags in intArrayOf(PackageManager.MATCH_DEFAULT_ONLY, 0)) {
                val ris: List<ResolveInfo> = try { pm.queryIntentActivities(base, flags) } catch (_: Exception) { emptyList() }
                for (ri in ris) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == packageName || pkg in seen) continue
                    val cls = ri.activityInfo.name
                    seen[pkg] = UpiApp(ri.loadLabel(pm).toString(), try { ri.loadIcon(pm) } catch (_: Exception) { null }) {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).setClassName(pkg, cls))
                    }
                }
            }
        }
        for (pkg in KNOWN_UPI_PACKAGES) {
            if (pkg in seen) continue
            val installed = try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            if (!installed) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            seen[pkg] = UpiApp(label, icon) { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)) }
        }
        return seen.values.sortedWith(
            compareBy<UpiApp> { !it.label.contains("fam", true) }.thenBy { it.label.lowercase() }
        )
    }

    // ---- Payment sheet -------------------------------------------------------
    private fun showPayment(req: UpiRequest) {
        val d = BottomSheetDialog(this)
        val sheetBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF20A0A0C.toInt(), 0xFA050506.toInt())
        ).apply {
            cornerRadii = floatArrayOf(dpF(28f), dpF(28f), dpF(28f), dpF(28f), 0f, 0f, 0f, 0f)
            setStroke(dp(1), Ink.BORDER)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(30))
        }
        val animate = ArrayList<View>()

        fun add(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) { col.addView(v, lp); animate += v }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 11f; setTextColor(Ink.MUTED)
            letterSpacing = 0.09f; setPadding(0, dp(10), 0, dp(2))
        }
        fun value(t: String, size: Float, color: Int) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        }

        // grabber
        add(View(this).apply { background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dpF(3f) } },
            LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(10) })

        // verify chip
        add(TextView(this).apply {
            text = "Verify before you pay"
            textSize = 12f; setTextColor(Ink.ACCENT_LIGHT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = glass(14, 0x335E6AD2, 0x1A5E6AD2, Ink.BORDER_SOFT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })

        add(label("RECIPIENT"))
        add(value(req.pn ?: "(name not provided)", 24f, Ink.TEXT))
        add(label("UPI ID"))
        add(value(req.pa, 16f, Ink.TEXT_DIM))
        add(label("AMOUNT"))
        add(value(req.am?.let { "\u20B9 $it" } ?: "Not set — you'll enter it in the payment app", if (req.am != null) 24f else 15f,
            if (req.am != null) Ink.ACCENT_LIGHT else Ink.MUTED))
        req.tn?.let { add(label("NOTE")); add(value(it, 16f, Ink.TEXT_DIM)) }

        add(label("PAY WITH"))

        val apps = resolveUpiApps(req.raw)
        for (app in apps) {
            add(payRow(app) {
                d.dismiss()
                try { app.launch() } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (apps.isEmpty()) {
            add(TextView(this).apply {
                text = "No installed UPI app was detected. Use \"Other apps…\" below."
                textSize = 13f; setTextColor(Ink.MUTED); setPadding(0, dp(4), 0, dp(10))
            })
        }
        add(payRow(UpiApp("Other apps…", null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(req.raw)), "Pay with"))
        }) {
            d.dismiss()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        add(TextView(this).apply {
            text = "Nothing is paid until you confirm inside the payment app. This scanner never sees your UPI PIN or payment result."
            textSize = 11f; setTextColor(Ink.MUTED); setPadding(0, dp(14), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            background = sheetBg
            isFillViewport = true
            addView(col)
        }
        d.setContentView(scroll)
        d.setOnShowListener {
            d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            d.window?.setDimAmount(0.55f)
            staggerIn(animate)
        }
        d.setOnDismissListener { locked = false; lastRawAt = SystemClock.elapsedRealtime() }
        d.show()
    }

    private fun payRow(app: UpiApp, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(16, 0x1FFFFFFF, 0x0FFFFFFF, Ink.BORDER_SOFT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            val d = app.icon ?: GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ink.ACCENT) }
            setImageDrawable(d)
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(TextView(this).apply {
            text = app.label; textSize = 16f; setTextColor(Ink.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "\u203A"; textSize = 22f; setTextColor(Ink.MUTED)
        })
        press(row)
        return row
    }

    // ---- Diagnostics ---------------------------------------------------------
    private fun showDiagnostics() {
        catalog = CameraCatalog.scan(this)
        val reportText = CameraCatalog.report(this, catalog, prefs)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        col.addView(TextView(this).apply {
            text = reportText; typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Ink.TEXT_DIM)
            setTextIsSelectable(true)
        })
        for (e in catalog.filter { it.usable }) {
            col.addView(TextView(this).apply { text = e.title; textSize = 13f; setTextColor(Ink.TEXT); setPadding(0, dp(14), 0, dp(6)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(t: String, a: () -> Unit) = TextView(this).apply {
                text = t; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Ink.TEXT); background = glass(12); setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true; setOnClickListener { a() }
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            }
            row.addView(btn("Test") { testing = e; segment.select(-1, true); startSelected() })
            for (m in LensMode.values()) row.addView(btn("\u2192 ${m.label}") {
                prefs.edit().putString("map_${m.key}", e.key).apply()
                Toast.makeText(this, "${m.label} = ${e.title}", Toast.LENGTH_SHORT).show()
                if (testing == null && mode == m) startSelected()
            })
            col.addView(row)
        }
        AlertDialog.Builder(this).setTitle("Camera diagnostics")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy report") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("camera report", reportText))
            }
            .setNegativeButton("Reset mapping") { _, _ ->
                prefs.edit().apply { LensMode.values().forEach { remove("map_${it.key}") } }.apply()
                testing = null; segment.select(LensMode.values().indexOf(mode), true); startSelected()
            }.show()
    }
}



/** Fills the parent (centre-crop) while keeping the camera aspect ratio. */
class CoverTextureView(ctx: Context) : TextureView(ctx) {
    private var ratio = 0f
    fun setImageRatio(r: Float) { if (r != ratio) { ratio = r; requestLayout() } }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val pw = MeasureSpec.getSize(wSpec); val ph = MeasureSpec.getSize(hSpec)
        if (ratio <= 0f || pw == 0 || ph == 0) { setMeasuredDimension(pw, ph); return }
        if (pw.toFloat() / ph > ratio) setMeasuredDimension(pw, (pw / ratio).toInt())
        else setMeasuredDimension((ph * ratio).toInt(), ph)
    }
}

/** Soft animated ambient light blobs for depth behind the glass controls. */
class AmbientGlowView(ctx: Context) : View(ctx) {
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14000L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private val accent = 0xFF5E6AD2.toInt()
    private val teal = 0xFF22D3EE.toInt()

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        blob(c, w * (0.16f + 0.10f * t), h * (0.20f + 0.05f * t), w * 0.55f, accent, 24)
        blob(c, w * (0.86f - 0.14f * t), h * (0.80f - 0.06f * t), w * 0.50f, teal, 16)
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Int) {
        val sh = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sh }
        c.drawCircle(cx, cy, r, p)
    }
}

/** Corner brackets + an animated accent scan line. Visual guide only — the whole frame is analysed. */
class ScanFrameView(ctx: Context) : View(ctx) {
    private val density = resources.displayMetrics.density
    private var phase = 0f
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt(); strokeWidth = 3.5f * density; strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun box(): RectF {
        val s = minOf(width, height) * 0.62f
        val l = (width - s) / 2f
        val t = (height - s) / 2f - height * 0.04f
        return RectF(l, t, l + s, t + s)
    }

    override fun onDraw(c: Canvas) {
        val b = box()
        val k = b.width() * 0.14f
        bracket.color = 0xE6FFFFFF.toInt()
        bracket.strokeWidth = 3.5f * density
        c.drawLines(floatArrayOf(
            b.left, b.top, b.left + k, b.top, b.left, b.top, b.left, b.top + k,
            b.right, b.top, b.right - k, b.top, b.right, b.top, b.right, b.top + k,
            b.left, b.bottom, b.left + k, b.bottom, b.left, b.bottom, b.left, b.bottom - k,
            b.right, b.bottom, b.right - k, b.bottom, b.right, b.bottom, b.right, b.bottom - k
        ), bracket)

        val y = b.top + b.height() * phase
        val gh = 22f * density
        line.shader = LinearGradient(
            0f, y - gh, 0f, y + gh,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, 94, 106, 210), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(b.left, y - gh, b.right, y + gh, line)
        line.shader = null
        line.color = Color.argb(230, 154, 163, 255)
        c.drawRect(b.left + 6f * density, y - 1f * density, b.right - 6f * density, y + 1f * density, line)
    }
}

/** A round glass icon button that draws its own vector icon. */
class GlassIconButton(ctx: Context, private val kind: Kind) : View(ctx) {
    enum class Kind { TORCH, GALLERY, INFO }

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var active = false
        set(v) { field = v; refreshBg(); invalidate() }

    init {
        isClickable = true; isFocusable = true
        contentDescription = when (kind) { Kind.TORCH -> "Torch"; Kind.GALLERY -> "Scan from gallery"; Kind.INFO -> "Camera info" }
        refreshBg()
    }

    private fun refreshBg() {
        val g = if (active) GradientDrawable().apply { setColor(Ink.ACCENT) }
                else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ink.TOP_GLASS, Ink.BOTTOM_GLASS))
        g.cornerRadius = 23f * density
        g.setStroke((1f * density).toInt().coerceAtLeast(1), if (active) 0x88FFFFFF.toInt() else Ink.BORDER)
        background = g
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f; val oy = (height - s) / 2f
        val col = if (active) Ink.BG else Ink.TEXT
        stroke.color = col; fill.color = col
        when (kind) {
            Kind.TORCH -> {
                val p = Path()
                p.moveTo(ox + .58f * s, oy + .16f * s)
                p.lineTo(ox + .32f * s, oy + .55f * s)
                p.lineTo(ox + .48f * s, oy + .55f * s)
                p.lineTo(ox + .42f * s, oy + .84f * s)
                p.lineTo(ox + .70f * s, oy + .45f * s)
                p.lineTo(ox + .52f * s, oy + .45f * s)
                p.close()
                c.drawPath(p, fill)
            }
            Kind.GALLERY -> {
                c.drawRoundRect(RectF(ox + .20f * s, oy + .26f * s, ox + .80f * s, oy + .74f * s), .10f * s, .10f * s, stroke)
                c.drawCircle(ox + .37f * s, oy + .39f * s, .045f * s, stroke)
                val m = Path()
                m.moveTo(ox + .25f * s, oy + .68f * s)
                m.lineTo(ox + .43f * s, oy + .50f * s)
                m.lineTo(ox + .55f * s, oy + .62f * s)
                m.lineTo(ox + .63f * s, oy + .55f * s)
                m.lineTo(ox + .76f * s, oy + .68f * s)
                c.drawPath(m, stroke)
            }
            Kind.INFO -> {
                c.drawCircle(ox + .5f * s, oy + .5f * s, .30f * s, stroke)
                c.drawCircle(ox + .5f * s, oy + .37f * s, .035f * s, fill)
                c.drawLine(ox + .5f * s, oy + .46f * s, ox + .5f * s, oy + .64f * s, stroke)
            }
        }
    }
}

/** Segmented lens control with an animated glass highlight pill. */
class SegmentBar(ctx: Context) : View(ctx) {
    var labels: List<String> = emptyList()
    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        private set

    private val density = resources.displayMetrics.density
    private val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ink.TOP_GLASS, Ink.BOTTOM_GLASS)).apply {
        cornerRadius = 29f * density
        setStroke((1f * density).toInt().coerceAtLeast(1), Ink.BORDER)
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private var animIndex = 0f
    private var animator: ValueAnimator? = null

    init { isClickable = true; contentDescription = "Lens selector" }

    fun select(index: Int, animate: Boolean = true) {
        val target = index.toFloat()
        if (!animate) { animator?.cancel(); animIndex = target; selected = index; updateDesc(); invalidate(); return }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animIndex, target).apply {
            duration = 260L
            interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
            addUpdateListener { animIndex = it.animatedValue as Float; invalidate() }
            start()
        }
        selected = index
        updateDesc()
    }

    private fun updateDesc() { contentDescription = "Lens: " + (labels.getOrNull(selected) ?: "none") }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && labels.isNotEmpty()) {
            val pad = 6f * density
            val cellW = (width - pad * 2) / labels.size
            val i = ((event.x - pad) / cellW).toInt().coerceIn(0, labels.size - 1)
            if (i != selected) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onSelect?.invoke(i) }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        bg.setBounds(0, 0, width, height); bg.draw(c)
        if (labels.isEmpty()) return
        val pad = 6f * density
        val top = pad; val bottom = height - pad
        val cellW = (width - pad * 2) / labels.size
        if (selected >= 0) {
            val x = pad + animIndex * cellW
            c.drawRoundRect(RectF(x + 2 * density, top, x + cellW - 2 * density, bottom), (bottom - top) / 2f, (bottom - top) / 2f, pill)
        }
        val fm = text.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        labels.forEachIndexed { i, s ->
            text.color = if (i == selected) Ink.BG else Ink.TEXT_DIM
            c.drawText(s, pad + cellW * (i + 0.5f), baseline, text)
        }
    }
}
