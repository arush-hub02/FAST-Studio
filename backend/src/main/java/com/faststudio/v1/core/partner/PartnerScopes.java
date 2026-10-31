package com.faststudio.v1.core.partner;

import com.faststudio.v1.core.common.ApiException;


import java.util.*;

/** What a customer can share with a partner. Everything is read-only; there are no write scopes. */
public final class PartnerScopes {
    private PartnerScopes() {}

    public static final String CHANNELS = "channels:read", EPG = "epg:read", STREAM = "stream:read", MARKERS = "markers:read", DELIVERY = "delivery:read";

    /** Scope -> plain-language meaning (shown on the Partners page). */
    public static final Map<String, String> ALL;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CHANNELS, "Channel details: name, description, genre, countries");
        m.put(EPG, "Programme guide (EPG and XMLTV)");
        m.put(STREAM, "How to start the stream for their viewers, with their ad mode and markers");
        m.put(MARKERS, "Ad-break times with SCTE-35 markers");
        m.put(DELIVERY, "Viewing and ad-delivery numbers for their platform only");
        ALL = Collections.unmodifiableMap(m);
    }

    public static Set<String> parse(String scopes) {
        return scopes == null || scopes.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(scopes.trim().split("\s+")));
    }

    /** Validates and orders scopes; at least one is required. */
    public static String normalize(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) throw ApiException.bad("Choose at least one thing to share.");
        for (String s : scopes) if (!ALL.containsKey(s)) throw ApiException.bad("Unknown scope " + s + ". Use: " + String.join(", ", ALL.keySet()) + ".");
        return String.join(" ", ALL.keySet().stream().filter(scopes::contains).toList());
    }
}
