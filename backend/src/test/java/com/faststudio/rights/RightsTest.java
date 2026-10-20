package com.faststudio.rights;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RightsTest {
    static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

    static Rights.Policy policy(String allowed, String blocked, Instant until) {
        return new Rights.Policy(UUID.randomUUID(), UUID.randomUUID(), allowed, blocked, null,
                until == null ? null : until.atOffset(ZoneOffset.UTC), true, "");
    }

    @Test
    void missingOrIncompletePolicyDenies() {
        assertFalse(Rights.evaluate(null, null, "IN", T, T.plusSeconds(60)).allowed());
        assertFalse(Rights.evaluate(policy("", "", null), null, "IN", T, T.plusSeconds(60)).allowed());
    }

    @Test
    void blockWinsOverWorldwide() {
        var p = policy("WW", "US,CA", null);
        assertTrue(Rights.evaluate(p, null, "IN", T, T).allowed());
        assertFalse(Rights.evaluate(p, null, "us", T, T).allowed());
    }

    @Test
    void channelTerritoryAlsoRequired() {
        var p = policy("IN,GB", "", null);
        assertTrue(Rights.evaluate(p, List.of("IN"), "IN", T, T).allowed());
        assertFalse(Rights.evaluate(p, List.of("IN"), "GB", T, T).allowed());
    }

    @Test
    void wholeSlotMustFitLicenceHalfOpen() {
        var p = policy("IN", "", T.plusSeconds(600));
        assertTrue(Rights.evaluate(p, null, "IN", T, T.plusSeconds(600)).allowed(), "ending exactly at expiry is allowed");
        assertFalse(Rights.evaluate(p, null, "IN", T, T.plusSeconds(601)).allowed(), "running past expiry is rejected");
        assertFalse(Rights.evaluate(p, null, "IN", T.plusSeconds(700), T.plusSeconds(800)).allowed());
    }
}
