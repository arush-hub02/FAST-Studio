package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The quality levels FAST Studio can encode and play, and which ones each tenant uses (property media.quality_levels,
 * set by platform administrators on the tenant page). A level's position in {@link #ALL} is its folder name in every
 * media folder ("0" = 360p … "5" = 2160p; fMP4 copies in "f0" … "f5"), so media packaged before this existed (folders
 * 0, 1, 2) still lines up. Media lacking a level the tenant later turns on plays its nearest lower level there.
 */
@Service
public class QualityLevels {
    public static final String PROPERTY = "media.quality_levels";
    public static final String DEFAULT = "360p,540p,720p";

    /** One level: folder index, picture size, H.264 video bitrate and level, codecs string, declared bandwidth. */
    public record Level(int index, String name, int width, int height, int videoKbps, String h264Level, String codecs, int bandwidth) {}

    public static final List<Level> ALL = List.of(
            new Level(0, "360p", 640, 360, 600, "3.0", "avc1.4d401e,mp4a.40.2", 800_000),
            new Level(1, "540p", 960, 540, 1400, "3.1", "avc1.4d401f,mp4a.40.2", 1_700_000),
            new Level(2, "720p", 1280, 720, 2500, "3.1", "avc1.4d401f,mp4a.40.2", 2_900_000),
            new Level(3, "1080p", 1920, 1080, 5000, "4.0", "avc1.4d4028,mp4a.40.2", 5_800_000),
            new Level(4, "1440p", 2560, 1440, 9000, "5.0", "avc1.4d4032,mp4a.40.2", 10_400_000),
            new Level(5, "2160p", 3840, 2160, 16000, "5.1", "avc1.4d4033,mp4a.40.2", 18_500_000));

    private final TenantConfigurationServiceV1 configuration;
    private final Map<UUID, Map.Entry<Long, List<Level>>> cache = new ConcurrentHashMap<>();

    public QualityLevels(@Lazy TenantConfigurationServiceV1 configuration) { this.configuration = configuration; }

    /** "360p,720p,1080p" → those levels, lowest first. Throws a 422 for anything else (used to validate the property). */
    public static List<Level> parse(String csv) {
        Set<String> names = new LinkedHashSet<>();
        for (String s : Objects.toString(csv, "").split(",")) if (!s.isBlank()) names.add(s.trim().toLowerCase(Locale.ROOT));
        List<Level> out = ALL.stream().filter(l -> names.contains(l.name())).toList();
        if (out.isEmpty() || out.size() != names.size())
            throw ApiException.bad("Quality levels: pick one or more of " + String.join(", ", ALL.stream().map(Level::name).toList()) + ".");
        return out;
    }

    public static void validate(JsonNode v) { parse(v.asString()); }

    /** The levels a project uses (its tenant's setting, else the default); cached for 30 s, as snapshots ask every 5 s. */
    public List<Level> of(UUID project) {
        long now = System.currentTimeMillis();
        var hit = cache.get(project);
        if (hit != null && hit.getKey() > now) return hit.getValue();
        List<Level> levels;
        try {
            JsonNode v = configuration.effective(project, PROPERTY);
            levels = parse(v == null || v.isNull() ? DEFAULT : v.asString());
        } catch (RuntimeException e) {
            levels = parse(DEFAULT);   // a broken value never stops encoding or playback
        }
        cache.put(project, Map.entry(now + 30_000, levels));
        return levels;
    }

    /** Highest picture height a project uses (for asking the video library for the matching resolution tier). */
    public int maxHeight(UUID project) { return of(project).getLast().height(); }
}
