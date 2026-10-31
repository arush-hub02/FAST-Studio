package com.faststudio.v1.dto.connectors;

import com.faststudio.v1.services.ads.sources.IAdSource;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Platform administrator's per-project Connectors page. */
public final class ConnectorsDto {
    private ConnectorsDto() {}

    public record FeatureOption(String key, String label) {}

    /** Media origin status: how many ready videos are already on the edge (R2). */
    public record Storage(String provider, boolean configured, String publicUrl, long videosReady, long videosOnEdge) {}

    /** {@code features} = switched on (within the plan); {@code featureCatalog} = what the plan allows. */
    public record Overview(UUID orgId, String orgName, List<String> features, List<FeatureOption> featureCatalog,
                           List<IAdSource.Config> adSources, Storage storage) {}

    public record FeaturesRequest(@NotNull List<String> features) {}
}
