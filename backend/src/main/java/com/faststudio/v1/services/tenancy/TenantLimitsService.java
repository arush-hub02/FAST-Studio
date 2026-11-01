package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.media.AssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-project limits, so one busy project can't slow the others down: API requests per minute, stored media hours and
 * videos converting at once. Values are dynamic properties (global default → tenant → project override, V19), read
 * at most once a minute per project and server.
 */
@Service
public class TenantLimitsService {
    public static final String API_PER_MINUTE = "limits.api_requests_per_minute", MEDIA_HOURS = "limits.media_hours",
            PROCESSING = "limits.processing_videos";
    static final long CACHE_MS = 60_000;

    private final TenantConfigurationServiceV1 configuration;
    private final AssetRepository assets;
    private record Cached(long value, long at) {}
    private final Map<String, Cached> limits = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> windows = new ConcurrentHashMap<>();   // project → {minute, requests}

    public TenantLimitsService(TenantConfigurationServiceV1 configuration, AssetRepository assets) {
        this.configuration = configuration;
        this.assets = assets;
    }

    /** The project's limit for a key; 0 or less means unlimited. */
    long limit(UUID org, String key) {
        long now = System.currentTimeMillis();
        Cached c = limits.get(org + key);
        if (c != null && now - c.at() < CACHE_MS) return c.value();
        var v = configuration.effective(org, key);
        long value = v == null ? 0 : v.asLong(0);
        limits.put(org + key, new Cached(value, now));
        return value;
    }

    /**
     * Counts one API request of the project; 429 when it is over its per-minute allowance.
     * ponytail: counted per server in memory (N servers allow N × the limit); move to Cloudflare rate limiting or a
     * shared counter if exact global limits matter.
     */
    public void countRequest(UUID org) {
        long max = limit(org, API_PER_MINUTE);
        if (max <= 0) return;
        long minute = System.currentTimeMillis() / 60_000;
        long[] w = windows.compute(org, (k, old) -> old == null || old[0] != minute ? new long[]{minute, 1} : new long[]{minute, old[1] + 1});
        if (w[1] > max)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "This project made more than " + max + " requests in a minute. Wait a moment and try again.");
    }

    /** Before adding media: refuses when the project already stores its allowance of media hours. */
    public void requireMediaRoom(UUID org) {
        long hours = limit(org, MEDIA_HOURS);
        if (hours <= 0) return;
        long stored = TenantScope.as(org, () -> assets.usage(org)).storedMs();
        if (stored >= hours * 3_600_000)
            throw new ApiException(HttpStatus.CONFLICT, "MEDIA_LIMIT", "This project already stores its limit of " + hours
                    + " hours of video. Archive videos you no longer need, or ask your platform administrator for a higher limit.");
    }

    /** Before starting a conversion: refuses when the project already has its allowance of videos converting. */
    public void requireProcessingSlot(UUID org) {
        long max = limit(org, PROCESSING);
        if (max <= 0) return;
        if (TenantScope.as(org, () -> assets.usage(org)).processing() >= max)
            throw new ApiException(HttpStatus.CONFLICT, "PROCESSING_LIMIT", max
                    + " videos of this project are already converting. Wait for some to finish, then try again.");
    }
}
