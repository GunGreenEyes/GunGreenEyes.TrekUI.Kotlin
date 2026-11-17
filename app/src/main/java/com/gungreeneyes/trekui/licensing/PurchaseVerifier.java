package com.gungreeneyes.trekui.licensing;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Optional on-device check of a Play purchase signature.
 *
 * <p>Google Play signs each purchase's JSON with the app's licensing key (Play Console:
 * Monetization setup, Licensing, "Base64-encoded RSA public key"). Paste that key into
 * {@link #BASE64_PUBLIC_KEY} to have unsigned or altered purchase data rejected. The key is
 * public, so it is safe to commit. While it is empty the check is skipped and purchases returned
 * by the Billing Library are trusted. The strongest option, server-side verification with the
 * Play Developer API, is out of scope for a server-less $0.99 unlock (see docs/MONETIZATION.md).
 */
final class PurchaseVerifier {
    /** Play Console licensing key (Base64 X.509). Empty = signature check disabled. */
    static final String BASE64_PUBLIC_KEY = "";

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";

    private PurchaseVerifier() {
    }

    /** True if no key is configured, or if {@code signature} is valid for {@code signedData}. */
    static boolean verify(String signedData, String signature) {
        if (BASE64_PUBLIC_KEY.isEmpty()) return true;
        if (signedData == null || signature == null || signature.isEmpty()) return false;
        try {
            byte[] keyBytes = Base64.decode(BASE64_PUBLIC_KEY, Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signedData.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.decode(signature, Base64.DEFAULT));
        } catch (Exception e) {
            return false;
        }
    }
}
