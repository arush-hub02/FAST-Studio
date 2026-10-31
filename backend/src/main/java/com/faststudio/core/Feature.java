package com.faststudio.core;

import java.util.Arrays;
import java.util.List;

/**
 * Per-company feature switches, set by the platform administrator (organization.features, comma-separated).
 * Connectors themselves are always platform-managed; features decide what a company sees of them.
 */
public enum Feature {
    AD_WATERFALL_VIEW("Company sees its ad demand waterfall (names and order only, no tag URLs)"),
    AD_OWN_TAGS("Company may add its own VAST ad tags (appended after the platform's sources)"),
    LIBRARY_SYNC("Company may sync its connected video library itself (otherwise only the platform syncs it)");

    public final String label;

    Feature(String label) { this.label = label; }

    public static List<String> parse(String csv) {
        return Times.codes(csv).stream().filter(f -> Arrays.stream(values()).anyMatch(v -> v.name().equals(f))).toList();
    }
}
