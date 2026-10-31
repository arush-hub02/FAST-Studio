package com.faststudio.v1.services.rights;

import com.faststudio.v1.core.common.Times;


import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Effective-rights evaluator (pure, no I/O). Rules, in order:
 * default deny when no policy or no licensed territories; explicit block wins;
 * country must be licensed (or WW) AND offered by the channel; the whole slot [start,end) must fit the licence window
 * (ending exactly at the licence end is allowed).
 */
public final class Rights {
    private Rights() {}

    static final DateTimeFormatter F = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static String f(Instant t) { return F.format(t); }

    public record Policy(UUID id, UUID assetId, String allowedCountries, String blockedCountries,
                         OffsetDateTime validFrom, OffsetDateTime validUntil, boolean adsAllowed, String notes) {}

    public record Check(String label, boolean passed, String detail) {}

    public record Decision(boolean allowed, String summary, List<Check> checks) {}

    public static Decision evaluate(Policy p, List<String> channelTerritories, String country, Instant start, Instant end) {
        String c = country == null ? "" : country.trim().toUpperCase();
        List<Check> checks = new ArrayList<>();
        if (p == null) {
            checks.add(new Check("A licence (rights policy) exists", false, "No rights policy has been entered for this video, so it is blocked everywhere (default deny)."));
            return done(checks);
        }
        checks.add(new Check("A licence (rights policy) exists", true, "Policy found"));
        List<String> allowed = Times.codes(p.allowedCountries()), blocked = Times.codes(p.blockedCountries());
        if (allowed.isEmpty()) {
            checks.add(new Check("Licence lists where it may play", false, "The policy is incomplete: no licensed countries are listed. Missing rights are never treated as permission."));
            return done(checks);
        }
        checks.add(new Check("Licence lists where it may play", true, String.join(", ", allowed)));
        boolean isBlocked = blocked.contains(c);
        checks.add(new Check("Not blocked in " + c, !isBlocked, isBlocked ? c + " is explicitly blocked. Blocks always win over permissions." : "No block for " + c));
        boolean licensed = allowed.contains(c) || allowed.contains("WW");
        checks.add(new Check("Licensed for " + c, licensed, licensed ? (allowed.contains("WW") ? "Worldwide licence" : c + " is licensed") : c + " is not in the licensed list (" + String.join(", ", allowed) + ")"));
        if (channelTerritories != null) {
            boolean offered = channelTerritories.contains(c);
            checks.add(new Check("Channel is offered in " + c, offered, offered ? "Channel territories include " + c : "The channel is only offered in " + String.join(", ", channelTerritories)));
        }
        Instant from = p.validFrom() == null ? null : p.validFrom().toInstant();
        Instant until = p.validUntil() == null ? null : p.validUntil().toInstant();
        if (start != null) {
            Instant e = end == null ? start : end;
            if (from != null && start.isBefore(from))
                checks.add(new Check("Licence has started", false, "Licence starts " + f(from) + "; the slot begins earlier."));
            else if (until != null && e.isAfter(until))
                checks.add(new Check("Licence still valid for the whole slot", false, start.isBefore(until)
                        ? "Licence expires " + f(until) + ", before this slot ends (" + f(e) + "). The whole programme must fit."
                        : "Licence expired " + f(until) + "."));
            else
                checks.add(new Check("Licence valid at this time", true, until == null ? "No end date" : "Valid until " + f(until)));
        }
        return done(checks);
    }

    private static Decision done(List<Check> checks) {
        Check firstFail = checks.stream().filter(k -> !k.passed()).findFirst().orElse(null);
        return firstFail == null
                ? new Decision(true, "Allowed: every rule passed.", checks)
                : new Decision(false, "Blocked: " + firstFail.detail(), checks);
    }
}
