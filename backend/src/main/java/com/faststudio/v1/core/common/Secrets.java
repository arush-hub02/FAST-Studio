package com.faststudio.v1.core.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encryption at rest for third-party credentials kept in the database (AES-256-GCM, random IV per value).
 * The key is faststudio.secrets-key when set (any string, hashed to 256 bits), else 32 random bytes generated once
 * into {@code <data-dir>/keys/secrets.key}. Treat that file like a password; losing it means re-entering credentials.
 */
@Component
public class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public Secrets(@Value("${faststudio.secrets-key:}") String configured, @Value("${faststudio.data-dir}") String dataDir) throws Exception {
        byte[] k;
        if (!configured.isBlank()) k = MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        else {
            Path file = Path.of(dataDir, "keys", "secrets.key");
            if (!Files.exists(file)) {
                byte[] fresh = new byte[32];
                RANDOM.nextBytes(fresh);
                Files.createDirectories(file.getParent());
                Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
            }
            k = Base64.getDecoder().decode(Files.readString(file).trim());
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    /** A stable secret for one purpose (e.g. a database login password): the same on every server sharing this key. */
    public String derive(String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("key derivation failed", e);
        }
    }

    /** base64(iv || ciphertext+tag); null stays null. */
    public String seal(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String open(String sealed) {
        if (sealed == null) return null;
        try {
            byte[] b = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, b, 0, 12));
            return new String(c.doFinal(b, 12, b.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("stored credential can't be decrypted (was the secrets key changed?)", e);
        }
    }
}
