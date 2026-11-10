package com.faststudio.v1.util.mediaProvider;

/** A provider with {@link Capability#DIRECT_UPLOAD}: the browser sends the file straight to the platform, never through us. */
public interface IDirectUpload {
    record Upload(String id, String url) {}

    /**
     * A one-time https URL; the video it becomes carries the connection's scope, so sync and webhooks find it.
     * maxHeight: the project's highest quality level, so the platform keeps enough resolution (e.g. 2160 for 4K).
     */
    Upload upload(IMediaProvider.Credentials c, String corsOrigin, String title, int maxHeight) throws InterruptedException;

    /**
     * The video an upload became, or null while the file hasn't arrived. Status ERRORED when the upload failed or expired.
     * Lets uploads finish without webhooks.
     */
    IMediaProvider.RemoteAsset uploaded(IMediaProvider.Credentials c, String uploadId) throws InterruptedException;
}
