package com.faststudio.v1.util.mediaProvider;

/** A provider with {@link Capability#DIRECT_UPLOAD}: the browser sends the file straight to the platform, never through us. */
public interface IDirectUpload {
    record Upload(String id, String url) {}

    /** A one-time https URL; the video it becomes carries the connection's scope, so sync and webhooks find it. */
    Upload upload(IMediaProvider.Credentials c, String corsOrigin, String title) throws InterruptedException;
}
