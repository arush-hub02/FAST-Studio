package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The asset rules every import path shares (the database-backed episode rule is covered through the API tests). */
class AssetRulesTest {
    @Test
    void importedTitlesAreTrimmedCutAndNeverBlank() {
        assertEquals("Untitled video", AssetRules.importedTitle(null));
        assertEquals("Untitled video", AssetRules.importedTitle("   "));
        assertEquals("Ocean Tales", AssetRules.importedTitle("  Ocean Tales "));
        assertEquals(AssetRules.TITLE_MAX, AssetRules.importedTitle("x".repeat(500)).length());
    }

    @Test
    void cuesSnapToSegmentsInsideTheVideoWithoutDuplicates() {
        assertEquals("5000,60000", AssetRules.cues(List.of(60_001L, 4_999L, 5_000L, 0L, 120_000L), 120_000));
        assertEquals("", AssetRules.defaultCues("AD", 600_000));
        assertEquals("60000", AssetRules.defaultCues("MOVIE", 120_000));
    }

    @Test
    void rightsNeedAValidWindowAndCountryCodesButBlankStaysBlank() {
        OffsetDateTime now = OffsetDateTime.now();
        assertDoesNotThrow(() -> AssetRules.rights(List.of(), null, null));
        assertDoesNotThrow(() -> AssetRules.rights(List.of("IN", "WW"), now, now.plusDays(1)));
        assertThrows(ApiException.class, () -> AssetRules.rights(List.of("India"), null, null));
        assertThrows(ApiException.class, () -> AssetRules.rights(List.of("IN"), now, now));
    }
}
