package com.faststudio.v1.util.mux;

import com.faststudio.v1.core.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Signed-playback tokens: valid RS256 over the claims Mux expects, deterministic, and every key form Mux hands out. */
class MuxSignerTest {
    static final KeyPair KEYS;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KEYS = g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    /** The RSAPrivateKey inside Java's PKCS#8 encoding (what Mux's PEM holds). */
    static byte[] pkcs1() {
        byte[] p8 = KEYS.getPrivate().getEncoded();
        return Arrays.copyOfRange(p8, 26, p8.length);   // 30 82 LL LL | 02 01 00 | 15-byte algorithm | 04 82 LL LL
    }

    @Test
    void tokenVerifiesAndCarriesTheClaims() throws Exception {
        String muxForm = Base64.getEncoder().encodeToString(pem("RSA PRIVATE KEY", pkcs1()).getBytes(StandardCharsets.US_ASCII));
        String t = MuxSigner.token("keyId01", muxForm, "pB7Hh01ocEanTales", "v", 1_900_000_000L);
        String[] parts = t.split("\\.");
        assertEquals(3, parts.length);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"keyId01\"}", header);
        assertEquals("{\"sub\":\"pB7Hh01ocEanTales\",\"aud\":\"v\",\"exp\":1900000000}", claims);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(KEYS.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertEquals(t, MuxSigner.token("keyId01", muxForm, "pB7Hh01ocEanTales", "v", 1_900_000_000L), "same claims, same token");
    }

    @Test
    void acceptsPemAndPkcs8AndRefusesGarbage() {
        String a = MuxSigner.token("k", pem("RSA PRIVATE KEY", pkcs1()), "p1", "t", 1L);
        String b = MuxSigner.token("k", pem("PRIVATE KEY", KEYS.getPrivate().getEncoded()), "p1", "t", 1L);
        String c = MuxSigner.token("k", Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()), "p1", "t", 1L);
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals("SIGNING_KEY_INVALID", assertThrows(ApiException.class, () -> MuxSigner.token("k", "bm90IGEga2V5", "p1", "v", 1L)).code);
        assertEquals("SIGNING_KEY_MISSING", assertThrows(ApiException.class, () -> MuxSigner.token(null, null, "p1", "v", 1L)).code);
    }
}
