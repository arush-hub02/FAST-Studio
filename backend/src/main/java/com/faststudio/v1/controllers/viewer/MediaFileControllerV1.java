package com.faststudio.v1.controllers.viewer;

import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.services.media.MediaSigner;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Serves media files only for valid, unexpired signatures; nothing under data/media is publicly listable or guessable. */
@RestController
public class MediaFileControllerV1 {
    private final MediaSigner signer;
    private final MediaServiceV1 media;

    public MediaFileControllerV1(MediaSigner signer, MediaServiceV1 media) {
        this.signer = signer;
        this.media = media;
    }

    @GetMapping("/media/s/{exp}/{sig}/{scope}/{folder}/**")
    ResponseEntity<FileSystemResource> file(@PathVariable long exp, @PathVariable String sig, @PathVariable String scope,
                                            @PathVariable String folder, HttpServletRequest req) {
        String key = scope + "/" + folder;
        if (!signer.valid(exp, sig, key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String prefix = "/media/s/" + exp + "/" + sig + "/" + key + "/";
        String rest = req.getRequestURI().substring(req.getRequestURI().indexOf(prefix) + prefix.length());
        if (!rest.matches("[A-Za-z0-9_./-]+") || rest.contains("..")) return ResponseEntity.badRequest().build();
        Path base = media.mediaDir().resolve(key).normalize();
        Path file = base.resolve(rest).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        MediaType type = rest.endsWith(".m3u8") ? MediaType.parseMediaType("application/vnd.apple.mpegurl")
                : rest.endsWith(".ts") ? MediaType.parseMediaType("video/mp2t")
                : rest.endsWith(".jpg") ? MediaType.IMAGE_JPEG : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }
}
