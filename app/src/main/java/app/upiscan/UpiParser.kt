package app.upiscan

import android.net.Uri

data class UpiRequest(
    val raw: String, val pa: String, val pn: String?,
    val am: String?, val cu: String, val tn: String?
)

sealed class ParseResult {
    data class Ok(val req: UpiRequest) : ParseResult()
    data class Bad(val reason: String) : ParseResult()
}

object UpiParser {
    private val VPA = Regex("^[A-Za-z0-9._\\-]{2,256}@[A-Za-z][A-Za-z0-9.\\-]{1,63}$")
    private val AMOUNT = Regex("^\\d{1,9}(\\.\\d{1,2})?$")

    fun parse(rawIn: String): ParseResult {
        val raw = rawIn.trim()
        if (!raw.startsWith("upi://", ignoreCase = true)) return ParseResult.Bad("Not a UPI QR code")
        val uri = try { Uri.parse(raw) } catch (e: Exception) { return ParseResult.Bad("Malformed QR") }
        if (!"pay".equals(uri.host, ignoreCase = true)) return ParseResult.Bad("Unsupported UPI QR type")

        // A QR with a repeated parameter could show one value here and a different one in the payment app.
        for (k in listOf("pa", "pn", "am", "cu", "tn")) {
            if (uri.getQueryParameters(k).size > 1) return ParseResult.Bad("Suspicious QR (duplicate '$k')")
        }
        val pa = uri.getQueryParameter("pa")?.trim().orEmpty()
        if (!VPA.matches(pa)) return ParseResult.Bad("Invalid UPI ID in QR")

        val am = uri.getQueryParameter("am")?.trim()?.takeIf { it.isNotEmpty() }
        if (am != null && (!AMOUNT.matches(am) || am.toDouble() <= 0.0)) return ParseResult.Bad("Invalid amount in QR")

        val cu = (uri.getQueryParameter("cu")?.trim()?.takeIf { it.isNotEmpty() } ?: "INR").uppercase()
        if (cu != "INR") return ParseResult.Bad("Unsupported currency: $cu")

        return ParseResult.Ok(
            UpiRequest(raw, pa, uri.getQueryParameter("pn")?.trim()?.takeIf { it.isNotEmpty() },
                am, cu, uri.getQueryParameter("tn")?.trim()?.takeIf { it.isNotEmpty() })
        )
    }
}
