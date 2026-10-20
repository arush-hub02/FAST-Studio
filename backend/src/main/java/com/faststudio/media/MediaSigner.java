package com.faststudio.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Signed, expiring media URLs: /media/s/{expires}/{signature}/{mediaKey}/{file}.
 * The HMAC covers the expiry and the media folder (e.g. "aurora/{assetId}"), not the file name, so relative URIs
 * inside a signed playlist (0/index.m3u8, seg_00001.ts) inherit the signature. Without the server secret nobody
 * can mint a URL for another tenant's folder, and leaked links stop working when they expire.
 * The secret comes from faststudio.media-secret; if unset a random one is generated per run (links then expire on restart).
 */
@Component
public class MediaSigner {
    private final byte[] secret;

    public MediaSigner(String configured) {
        this(configured, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MediaSigner(@Value("${faststudio.media-secret:}") String configured, @Value("${faststudio.require-media-secret:false}") boolean required) {
        if (configured != null && configured.length() >= 32) secret = configured.getBytes(StandardCharsets.UTF_8);
        else if (required) throw new IllegalStateException("faststudio.media-secret (FASTSTUDIO_MEDIA_SECRET) must be set to a random value of at least 32 characters "
                + "so video links survive restarts. start.bat -Pilot generates one for you.");
        else {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        }
    }

    /** URL for media/{mediaKey}/{file}, valid until {@code expires}. */
    public String url(String mediaKey, String file, Instant expires) {
        long exp = expires.getEpochSecond();
        return "/media/s/" + exp + "/" + sign(exp, mediaKey) + "/" + mediaKey + "/" + file;
    }

    /** For pages and API responses: expiry rounded to the hour, so the same URL is reused (and cached) for an hour. */
    public String url(String mediaKey, String file) {
        long hour = Instant.now().getEpochSecond() / 3600 * 3600;
        return url(mediaKey, file, Instant.ofEpochSecond(hour + 2 * 3600));
    }

    public boolean valid(long exp, String signature, String mediaKey) {
        if (exp < Instant.now().getEpochSecond()) return false;
        return MessageDigest.isEqual(sign(exp, mediaKey).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }

    String sign(long exp, String mediaKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((exp + ":" + mediaKey).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 27);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
