package com.omariskandarani.livelatexapp

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local promo codes: `base64url(payloadJson).hexHmac` where HMAC-SHA256 uses [promoSecret].
 * Payload supports: `{"lt":true}` lifetime Pro, or `{"h":24}` hours of Pro from redeem time,
 * or `{"u":1735689600000}` Pro-until epoch ms (absolute).
 */
object PromoCodeValidator {

    fun tryRedeem(code: String, promoSecret: String): PromoRedeemResult {
        val trimmed = code.trim().replace(" ", "")
        if (trimmed.isEmpty() || promoSecret.isBlank()) return PromoRedeemResult.Invalid
        val parts = trimmed.split('.')
        if (parts.size != 2) return PromoRedeemResult.Invalid
        val payloadB64 = parts[0]
        val sigHex = parts[1]
        val payloadJson = try {
            String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return PromoRedeemResult.Invalid
        }
        val expectedSig = hmacHex(promoSecret, payloadB64)
        if (!constantTimeEquals(expectedSig, sigHex.lowercase())) return PromoRedeemResult.Invalid

        val o = try {
            JSONObject(payloadJson)
        } catch (_: Exception) {
            return PromoRedeemResult.Invalid
        }
        val now = System.currentTimeMillis()
        if (o.has("exp") && o.optLong("exp", Long.MAX_VALUE) * 1000L < now) {
            return PromoRedeemResult.Expired
        }
        return when {
            o.optBoolean("lt", false) -> PromoRedeemResult.Lifetime
            o.has("u") -> PromoRedeemResult.Until(o.optLong("u", 0L))
            o.has("h") -> {
                val hours = o.optLong("h", 24).coerceIn(1, 24 * 365)
                PromoRedeemResult.Until(now + hours * 3600_000L)
            }
            else -> PromoRedeemResult.Invalid
        }
    }

    private fun hmacHex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { b -> "%02x".format(b) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

sealed class PromoRedeemResult {
    data object Invalid : PromoRedeemResult()
    data object Expired : PromoRedeemResult()
    data object Lifetime : PromoRedeemResult()
    data class Until(val epochMs: Long) : PromoRedeemResult()
}
