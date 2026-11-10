package app.upiscan

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.hypot

enum class LensMode(val label: String, val key: String) {
    MAIN("1×", "main"), ULTRA_WIDE("0.5×", "uw"), MACRO("Macro", "macro")
}

/** One openable back-facing lens: either a standalone camera ID or a physical camera inside a logical one. */
data class CamEntry(
    val openId: String, val physicalId: String?, val isLogical: Boolean,
    val focal: Float, val equiv35: Float, val pixW: Int, val pixH: Int,
    val minFocus: Float, val hasAf: Boolean, val usable: Boolean, val physicalIds: List<String>
) {
    val charsId get() = physicalId ?: openId
    val key get() = if (physicalId != null) "$openId|$physicalId" else openId
    val title get() = if (physicalId != null) "ID $openId → physical $physicalId" else "ID $openId"
    val mp get() = pixW.toLong() * pixH / 1_000_000.0
}

object CameraCatalog {

    fun scan(ctx: Context): List<CamEntry> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList
        val out = ArrayList<CamEntry>()
        for (id in ids) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            if (c.get(CC.LENS_FACING) != CC.LENS_FACING_BACK) continue
            val phys = c.physicalCameraIds.toList()
            out += describe(id, null, c, phys.isNotEmpty(), phys)
            for (p in phys) {
                if (p in ids) continue // already listed as its own standalone camera
                val pc = try { mgr.getCameraCharacteristics(p) } catch (e: Exception) { continue }
                out += describe(id, p, pc, false, emptyList())
            }
        }
        return out
    }

    private fun describe(id: String, phys: String?, c: CC, logical: Boolean, physIds: List<String>): CamEntry {
        val f = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val size = c.get(CC.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (size != null) hypot(size.width.toDouble(), size.height.toDouble()) else 0.0
        val eq = if (diag > 0 && f > 0) (f * 43.27 / diag).toFloat() else 0f
        val pa = c.get(CC.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val compat = CC.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps
        val yuv = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.isNotEmpty() == true
        val af = c.get(CC.CONTROL_AF_AVAILABLE_MODES)?.contains(CC.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        return CamEntry(
            id, phys, logical, f, eq, pa?.width ?: 0, pa?.height ?: 0,
            c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f, af, compat && yuv, physIds
        )
    }

    /**
     * Heuristic classification from real characteristics (never from ID order):
     *  - ultra-wide: shortest 35mm-equivalent focal length, < 20 mm
     *  - main: autofocus camera with the most pixels among the rest
     *  - macro: the single remaining colour camera, preferring fixed-focus; ambiguous => unassigned
     * Anything unassigned must be set manually in the diagnostics screen.
     */
    fun autoMap(list: List<CamEntry>): Map<LensMode, CamEntry> {
        val standalone = list.filter { it.physicalId == null }.map { it.openId }.toSet()
        fun hasLensEntries(l: CamEntry) = l.physicalIds.any { p ->
            p in standalone || list.any { it.openId == l.openId && it.physicalId == p }
        }
        val cands = list.filter { it.usable && !(it.isLogical && hasLensEntries(it)) }
        val m = LinkedHashMap<LensMode, CamEntry>()
        val uw = cands.filter { it.equiv35 in 1f..20f }.minByOrNull { it.equiv35 }
        if (uw != null) m[LensMode.ULTRA_WIDE] = uw
        val rest = cands - listOfNotNull(uw).toSet()
        val main = rest.filter { it.hasAf }.maxByOrNull { it.pixW.toLong() * it.pixH }
            ?: rest.maxByOrNull { it.pixW.toLong() * it.pixH }
        if (main != null) m[LensMode.MAIN] = main
        val others = rest - listOfNotNull(main).toSet()
        val fixed = others.filter { !it.hasAf }
        val macro = when {
            others.isEmpty() -> null
            others.size == 1 -> others[0]
            fixed.size == 1 -> fixed[0]
            fixed.size > 1 -> fixed.minByOrNull { it.pixW.toLong() * it.pixH }
            else -> null
        }
        if (macro != null) m[LensMode.MACRO] = macro
        return m
    }

    /** Manual assignment wins over auto-detection. Returns null if nothing trustworthy is mapped. */
    fun resolve(list: List<CamEntry>, mode: LensMode, prefs: SharedPreferences): CamEntry? {
        val ov = prefs.getString("map_${mode.key}", null)
        if (ov != null) return list.firstOrNull { it.key == ov && it.usable }
        return autoMap(list)[mode]
    }

    fun report(ctx: Context, list: List<CamEntry>, prefs: SharedPreferences): String {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("All camera IDs exposed to apps: ${mgr.cameraIdList.joinToString()}\n\n")
        for (e in list) {
            sb.append("• ${e.title}")
            if (e.isLogical) sb.append("  [logical multi-camera, physical IDs: ${e.physicalIds.joinToString()}]")
            sb.append("\n  focal ${"%.2f".format(e.focal)} mm (≈${"%.1f".format(e.equiv35)} mm 35mm-eq), ")
            sb.append("${e.pixW}x${e.pixH} (${"%.1f".format(e.mp)} MP)\n")
            sb.append("  min focus ${"%.2f".format(e.minFocus)} dpt, continuous AF=${e.hasAf}, usable colour stream=${e.usable}\n")
        }
        if (list.isEmpty()) sb.append("No back cameras found.\n")
        sb.append("\nMapping:\n")
        val auto = autoMap(list)
        for (m in LensMode.values()) {
            val ov = prefs.getString("map_${m.key}", null)
            val r = resolve(list, m, prefs)
            sb.append("  ${m.label}: ${r?.title ?: "NOT IDENTIFIED"}" +
                (if (ov != null) "  (manual)" else if (auto[m] != null) "  (auto)" else "") + "\n")
        }
        return sb.toString()
    }
}
