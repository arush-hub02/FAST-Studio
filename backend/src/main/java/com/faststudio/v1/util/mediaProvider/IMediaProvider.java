package com.faststudio.v1.util.mediaProvider;

import java.util.List;
import java.util.Set;

/**
 * A video platform a project can connect (Mux today). An adapter translates the platform's API into these neutral
 * records; credentials, raw responses and the platform's name never travel further than the adapter, so sync, imports
 * and the screens don't change when another platform is added. Errors are tenant-safe ApiExceptions.
 */
public interface IMediaProvider {
    /** Stable key stored on the connection and as the source_ref prefix ("mux"). */
    String key();

    Set<Capability> capabilities();

    /** Stored credentials, decrypted. scope: the platform-side filter for this project's videos (blank = all). */
    record Credentials(String id, String secret, String scope) {}

    /**
     * A remote video. status: READY, PREPARING, ERRORED or DELETED (what the platform says, not our readiness).
     * uploadId is set when it came from one of our direct uploads. durationMs is whole 5-second segments.
     */
    record RemoteAsset(String externalId, String status, String title, long durationMs, String uploadId) {
        public RemoteAsset withStatus(String s) { return new RemoteAsset(externalId, s, title, durationMs, uploadId); }
    }

    /** One page of the catalogue within the scope. next: opaque checkpoint for the following page, null after the last. */
    record Page(List<RemoteAsset> assets, String next) {}

    /** Proves the credentials work by reading the catalogue; throws when they don't. */
    void check(Credentials c) throws InterruptedException;

    /** The page that starts at the checkpoint (null = the first page). Must be safe to repeat. */
    Page page(Credentials c, String checkpoint) throws InterruptedException;
}
