package com.faststudio.v1.repositories.media;

import com.faststudio.v1.util.mediaProvider.IMediaProvider.RemoteAsset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * SQL for asset_source: which platform video each imported asset came from and what the platform last said about it.
 * Never changes the asset itself, so titles, episodes and rights edited in FAST Studio survive every re-sync.
 */
@Repository
public class AssetSourceRepository {
    private final JdbcClient db;

    public AssetSourceRepository(JdbcClient db) { this.db = db; }

    /** Records that the platform reported this video, for the asset bound to it by source_ref (no asset = nothing to record). */
    public void seen(UUID org, String provider, String ref, RemoteAsset x) {
        db.sql("""
                insert into asset_source(org_id, asset_id, provider, external_id, provider_status, title, duration_ms)
                select org_id, id, :p, :x, :s, :t, :ms from asset where org_id = :o and source_ref = :r
                on conflict (org_id, provider, external_id) do update set provider_status = excluded.provider_status,
                  title = excluded.title, duration_ms = excluded.duration_ms, last_seen_at = now()""")
                .param("p", provider).param("x", x.externalId()).param("s", x.status()).param("t", x.title()).param("ms", x.durationMs())
                .param("o", org).param("r", ref).update();
    }

    /** A webhook said the video is gone from the platform; the local asset and its media stay. */
    public void deleted(UUID org, String provider, String externalId) {
        db.sql("update asset_source set provider_status = 'DELETED', last_seen_at = now() where org_id = :o and provider = :p and external_id = :x")
                .param("o", org).param("p", provider).param("x", externalId).update();
    }

    /** What "prepare selected" needs to know per requested asset of the project; unknown ids are simply absent. */
    public record Candidate(UUID id, String status, String provider, String providerStatus) {}

    public List<Candidate> candidates(UUID org, Collection<UUID> ids) {
        return db.sql("""
                select a.id, a.status, s.provider, s.provider_status from asset a left join asset_source s on s.org_id = a.org_id and s.asset_id = a.id
                where a.org_id = :o and a.id in (:ids)""")
                .param("o", org).param("ids", ids).query(Candidate.class).list();
    }
}
