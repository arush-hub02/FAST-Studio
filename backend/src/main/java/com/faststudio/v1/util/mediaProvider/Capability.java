package com.faststudio.v1.util.mediaProvider;

/** What a video platform can do for a project. Actions are offered only when the connection's provider supports them. */
public enum Capability {
    /** Lists the platform's videos page by page (library sync). */
    CATALOGUE,
    /** Gives the browser a one-time URL to upload a file straight to the platform ({@link IDirectUpload}). */
    DIRECT_UPLOAD,
    /** Hands over a video's original file, so we can prepare it for broadcast ("prepare selected"). */
    SOURCE_FILE,
    /** Sends signed events when a video changes. */
    WEBHOOKS
}
