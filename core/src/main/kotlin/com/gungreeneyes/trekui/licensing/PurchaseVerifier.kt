package com.gungreeneyes.trekui.licensing

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Optional on-device check of a Play purchase signature.
 *
 * Google Play signs each purchase's JSON with the app's licensing key (Play Console: Monetization
 * setup, Licensing, "Base64-encoded RSA public key"). Paste that key into [BASE64_PUBLIC_KEY] to have
 * unsigned or altered purchase data rejected. The key is public, so it is safe to commit. While it is
 * empty the check is skipped and purchases returned by the Billing Library are trusted. The strongest
 * option, server-side verification with the Play Developer API, is out of scope for a server-less
 * $0.99 unlock (see docs/MONETIZATION.md).
 */
internal object PurchaseVerifier {
    /** Play Console licensing key (Base64 X.509). Empty = signature check disabled. */
    const val BASE64_PUBLIC_KEY: String = ""

    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    /** True if no key is configured, or if [signature] is valid for [signedData]. */
    fun verify(signedData: String?, signature: String?): Boolean {
        if (BASE64_PUBLIC_KEY.isEmpty()) return true
        if (signedData == null || signature.isNullOrEmpty()) return false
        return try {
            val keyBytes = Base64.decode(BASE64_PUBLIC_KEY, Base64.DEFAULT)
            val key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(Base64.decode(signature, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            false
        }
    }
}
