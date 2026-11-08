package com.faststudio.v1.util.mux;

import com.faststudio.v1.core.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Tokens for Mux signed playback: an RS256 JWT {sub: playback id, aud: "v" (video) or "t" (thumbnail), exp} with the
 * signing key's id as kid. RSA PKCS#1 v1.5 signatures are deterministic, so the same claims give the same token
 * (snapshots don't change on every publish). Accepts the key as Mux shows it (base64 of a PEM), a PEM, or bare DER.
 */
public final class MuxSigner {
    private MuxSigner() {}

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    public static String token(String keyId, String key, String playbackId, String audience, long expEpochS) {
        if (keyId == null || keyId.isBlank() || key == null || key.isBlank())
            throw ApiException.conflict("SIGNING_KEY_MISSING", "This video uses signed playback. Add the signing key ID and private key to the library connection.");
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + keyId.trim().replaceAll("[^A-Za-z0-9_-]", "") + "\"}";
        String claims = "{\"sub\":\"" + playbackId + "\",\"aud\":\"" + audience + "\",\"exp\":" + expEpochS + "}";
        String body = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "." + URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(privateKey(key));
            s.update(body.getBytes(StandardCharsets.US_ASCII));
            return body + "." + URL.encodeToString(s.sign());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw badKey();
        }
    }

    static PrivateKey privateKey(String key) throws Exception {
        String text = key.trim();
        if (!text.contains("-----BEGIN")) {
            byte[] raw = Base64.getMimeDecoder().decode(text);
            String decoded = new String(raw, StandardCharsets.US_ASCII);
            if (!decoded.contains("-----BEGIN")) return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(raw));
            text = decoded;
        }
        boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        byte[] der = Base64.getMimeDecoder().decode(text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? pkcs8(der) : der));
    }

    /** Wraps a PKCS#1 RSAPrivateKey into PKCS#8 PrivateKeyInfo (the only form Java reads). */
    static byte[] pkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(new byte[]{0x02, 0x01, 0x00});
        inner.writeBytes(algorithm);
        inner.write(0x04);
        inner.writeBytes(length(pkcs1.length));
        inner.writeBytes(pkcs1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.writeBytes(length(inner.size()));
        out.writeBytes(inner.toByteArray());
        return out.toByteArray();
    }

    private static byte[] length(int n) {
        if (n < 128) return new byte[]{(byte) n};
        if (n < 256) return new byte[]{(byte) 0x81, (byte) n};
        if (n < 65536) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static ApiException badKey() {
        return ApiException.conflict("SIGNING_KEY_INVALID", "The library connection's signing private key couldn't be read. Paste it again as the platform shows it.");
    }
}
