package com.faststudio.v1.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Home dashboard, alerts, audit log, analytics and the SCTE-35 learning tool. */
public final class OpsDto {
    private OpsDto() {}

    /** One getting-started step and whether the project has done it. */
    public record Step(String key, String title, String description, boolean done, String link) {}

    public record Dashboard(Map<String, Long> counts, List<Step> checklist, List<AuditRow> activity, List<Alert> alerts) {}

    public record Alert(UUID id, String severity, String title, String detail, String suggestion, String link, String status,
                        String resolution, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record ResolveRequest(@NotBlank @Size(max = 500) String resolution) {}

    /** {@code actor} is the person (with partner org), the partner's API client, or "System". */
    public record AuditRow(long id, String actor, String action, String resourceType, String resourceId, String detail, OffsetDateTime createdAt) {}

    // ------------------------------------------------------------------ analytics (prototype-observed, not money)

    public record ChannelStats(UUID channelId, String channel, long sessions, long impressions, long completes, long adSeconds,
                               long slateSeconds, BigDecimal fillRate, BigDecimal completionRate, BigDecimal estimatedRevenue) {}

    public record CampaignStats(String campaign, String advertiser, long impressions, long completes, BigDecimal cpm, BigDecimal estimatedRevenue) {}

    public record DayStat(LocalDate day, long impressions, long sessions) {}

    /** {@code provenance} and {@code formula} say exactly where the numbers come from. */
    public record Analytics(String provenance, String formula, List<ChannelStats> channels, List<CampaignStats> campaigns, List<DayStat> days) {}

    public record DecodeRequest(@NotBlank String value) {}
}
