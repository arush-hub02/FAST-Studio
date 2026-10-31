package com.faststudio.v1_0.services.media;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MediaSignerTest {
    final MediaSigner signer = new MediaSigner("test-secret-that-is-at-least-32-characters");

    static String[] parts(String url) {           // /media/s/{exp}/{sig}/{scope}/{folder}/{file...}
        return url.split("/");
    }

    @Test
    void signedUrlValidatesForItsOwnFolderOnly() {
        String url = signer.url("aurora/abc", "0/seg_00001.ts", Instant.now().plusSeconds(600));
        String[] p = parts(url);
        long exp = Long.parseLong(p[3]);
        assertTrue(signer.valid(exp, p[4], "aurora/abc"));
        assertFalse(signer.valid(exp, p[4], "northwind/abc"), "signature can't be replayed against another tenant's folder");
        assertFalse(signer.valid(exp + 1, p[4], "aurora/abc"), "expiry can't be extended");
    }

    @Test
    void expiredAndForeignSignaturesFail() {
        String[] p = parts(signer.url("aurora/abc", "thumb.jpg", Instant.now().minusSeconds(1)));
        assertFalse(signer.valid(Long.parseLong(p[3]), p[4], "aurora/abc"));
        String[] other = parts(new MediaSigner("another-secret-also-at-least-32-characters").url("aurora/abc", "thumb.jpg", Instant.now().plusSeconds(600)));
        assertFalse(signer.valid(Long.parseLong(other[3]), other[4], "aurora/abc"));
    }

    @Test
    void pilotRefusesToStartWithoutAStableSecret() {
        assertThrows(IllegalStateException.class, () -> new MediaSigner("", true));
        assertThrows(IllegalStateException.class, () -> new MediaSigner("too-short", true));
        assertDoesNotThrow(() -> new MediaSigner("", false));
    }

    @Test
    void pageUrlsAreStableWithinTheHour() {
        assertEquals(signer.url("aurora/abc", "thumb.jpg"), signer.url("aurora/abc", "thumb.jpg"));
    }
}
