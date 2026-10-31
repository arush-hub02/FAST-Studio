package com.faststudio.v1.util.mux;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Verifies Mux webhook signatures. */
public final class MuxWebhookVerifier {
    private MuxWebhookVerifier() {}

    /** Mux-Signature: "t={unix seconds},v1={hex HMAC-SHA256 of t + '.' + raw body}", accepted within 5 minutes. */
    public static boolean signed(String secret, String header, byte[] body, Instant now) throws GeneralSecurityException {
        if (header == null) return false;
        String t = null, v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) t = kv[1];
            if (kv.length == 2 && kv[0].equals("v1")) v1 = kv[1];
        }
        if (t == null || v1 == null || !t.matches("\\d{1,12}") || !v1.matches("[0-9a-fA-F]{64}") || Math.abs(now.getEpochSecond() - Long.parseLong(t)) > 300)
            return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(v1));
    }
}
