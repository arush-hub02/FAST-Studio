package com.faststudio.v1.services.media;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Storage connector for packaged media that viewers download (Cloudflare R2 today; S3 or Bunny plug in here).
 * Keys look like {org slug}/{asset id}.{revision}/... and are never overwritten, so the CDN may cache them forever.
 */
public interface MediaStore {
    /** True when the media folder is completely stored (its master.m3u8, written last, exists). */
    boolean has(String mediaKey) throws IOException, InterruptedException;

    void put(String path, Path file, String contentType) throws IOException, InterruptedException;
}
