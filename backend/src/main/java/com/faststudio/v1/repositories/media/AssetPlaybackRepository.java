package com.faststudio.v1.repositories.media;

import com.faststudio.v1.util.mediaProvider.IPlayback.Playback;
import com.faststudio.v1.util.mediaProvider.IPlayback.Rendition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** SQL for asset_playback: how each library video streams from its platform (read when it is prepared). */
@Repository
public class AssetPlaybackRepository {
    private static final TypeReference<List<Rendition>> RENDITIONS = new TypeReference<>() {};
    private final JdbcClient db;
    private final JsonMapper json;

    public AssetPlaybackRepository(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Row(UUID assetId, String provider, Playback playback) {}

    public void save(UUID org, UUID asset, String provider, Playback p) {
        db.sql("""
                insert into asset_playback(asset_id, org_id, provider, playback_id, signed, format, renditions, segments_ms, duration_ms)
                values (:a, :o, :p, :id, :s, :f, cast(:r as jsonb), :seg, :ms)
                on conflict (asset_id) do update set provider = excluded.provider, playback_id = excluded.playback_id, signed = excluded.signed,
                  format = excluded.format, renditions = excluded.renditions, segments_ms = excluded.segments_ms,
                  duration_ms = excluded.duration_ms, checked_at = now()
                where asset_playback.org_id = :o""")
                .param("a", asset).param("o", org).param("p", provider).param("id", p.playbackId()).param("s", p.signed())
                .param("f", p.format()).param("r", json.writeValueAsString(p.renditions())).param("seg", p.segmentsMs()).param("ms", p.durationMs())
                .update();
    }

    /** The playback of these assets of the project (assets that don't stream from a platform are absent). */
    public Map<UUID, Row> of(UUID org, Collection<UUID> assets) {
        if (assets.isEmpty()) return Map.of();
        Map<UUID, Row> out = new HashMap<>();
        db.sql("select asset_id, provider, playback_id, signed, format, renditions, segments_ms from asset_playback where org_id = :o and asset_id in (:ids)")
                .param("o", org).param("ids", assets).query((rs, n) -> row(rs)).list().forEach(r -> out.put(r.assetId(), r));
        return out;
    }

    public Optional<Row> get(UUID org, UUID asset) { return Optional.ofNullable(of(org, List.of(asset)).get(asset)); }

    private Row row(ResultSet rs) throws SQLException {
        Array a = rs.getArray("segments_ms");
        Integer[] boxed = (Integer[]) a.getArray();
        int[] seg = new int[boxed.length];
        for (int i = 0; i < seg.length; i++) seg[i] = boxed[i];
        return new Row(rs.getObject("asset_id", UUID.class), rs.getString("provider"), new Playback(rs.getString("playback_id"), rs.getBoolean("signed"),
                rs.getString("format"), json.readValue(rs.getString("renditions"), RENDITIONS), seg));
    }
}
