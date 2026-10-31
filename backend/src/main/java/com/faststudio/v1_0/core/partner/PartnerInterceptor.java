package com.faststudio.v1_0.core.partner;

import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.auth.Tokens;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.core.tenant.TenantScope;
import com.faststudio.v1_0.services.tenancy.AccessRoleServiceV1_0;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single access check for /api/partners/{partner}/** (docs/PARTNER_ACCESS_AND_TOKENS.md §3):
 * 1. the caller is a member of that partner organization, or one of its API clients (service token);
 * 2. for /sources/{owner}/…: an ACTIVE, unexpired sharing from that customer exists, it includes the endpoint's scope
 *    (and so does the API client), and it includes the channel in the path. Anything else is 404, so a partner
 *    can't discover what isn't shared;
 * 3. the read then runs as the owner organization (row-level security) in a read-only transaction, and is recorded
 *    in the owner's Activity log with both organizations.
 * Every partner organization is rate limited.
 */
@Component
public class PartnerInterceptor implements HandlerInterceptor {
    public static final String ATTR = "fs.partner";
    private final JdbcClient db;
    private final Audit audit;
    private final int perMinute;
    private final Map<UUID, Deque<Long>> calls = new ConcurrentHashMap<>();

    public PartnerInterceptor(JdbcClient db, Audit audit, @Value("${faststudio.partners.rate-limit-per-minute:120}") int perMinute) {
        this.db = db;
        this.audit = audit;
        this.perMinute = perMinute;
    }

    /** Who is calling, for which partner, and (on /sources/{owner}/…) what was shared. */
    public record Ctx(UUID partnerId, String partnerSlug, String partnerName, UUID userId, Tenant.Role role, UUID clientId, String clientName,
                      Set<String> callerScopes, Link link, boolean canManage) {
        public boolean admin() { return canManage; }

        public String caller() { return clientName != null ? "API client '" + clientName + "'" : "a person"; }

        public Link requireLink() {
            if (link == null) throw new IllegalStateException("not a /sources route");
            return link;
        }
    }

    public record Link(UUID id, UUID ownerId, String ownerSlug, String ownerName, String scopes, boolean allChannels, UUID destinationId) {
        public Set<String> scopeSet() { return PartnerScopes.parse(scopes); }
    }

    record Org(UUID id, String slug, String name, String status) {}

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        var vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || !vars.containsKey("partner")) return true;
        Org partner = org(vars.get("partner"), "PARTNER");
        Jwt jwt = Tokens.current();
        UUID userId = null, clientId = null;
        String clientName = null;
        Tenant.Role role = null;
        Set<String> callerScopes;
        if (Tokens.isService()) {
            @SuppressWarnings("unchecked") Map<String, Object> claimOrg = jwt.getClaim("org");
            if (claimOrg == null || !partner.id().toString().equals(claimOrg.get("id"))) throw ApiException.notFound("Partner");
            clientId = UUID.fromString(jwt.getSubject());
            clientName = jwt.getClaimAsString("client_name");
            callerScopes = PartnerScopes.parse(jwt.getClaimAsString("scope"));
        } else {
            userId = Auth.userId();
            String r = db.sql("select role from membership where org_id = :o and user_id = :u").param("o", partner.id()).param("u", userId)
                    .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Partner"));
            if (jwt != null && !tokenLists(jwt, partner.id())) throw ApiException.notFound("Partner");
            role = Tenant.Role.valueOf(r);
            callerScopes = PartnerScopes.ALL.keySet();             // people see everything that is shared with their org
        }
        if (!"ACTIVE".equals(partner.status())) throw new ApiException(HttpStatus.FORBIDDEN, "ORG_SUSPENDED",
                "This partner organization is suspended by the platform administrator.");
        limit(partner.id());
        MDC.put("orgId", partner.id().toString());
        TenantScope.set(partner.id());                 // acting as the partner org (its own audit rows); sources switch to the owner below

        Link link = null;
        if (vars.containsKey("owner")) {
            Org owner = org(vars.get("owner"), "CUSTOMER");
            link = db.sql("""
                    select l.id, l.owner_org_id as owner_id, cast(:slug as text) as owner_slug, cast(:name as text) as owner_name, l.scopes, l.all_channels, l.destination_id
                    from partner_link l where l.owner_org_id = :o and l.partner_org_id = :p and l.status = 'ACTIVE'
                      and (l.expires_at is null or l.expires_at > now())""")
                    .param("slug", owner.slug()).param("name", owner.name()).param("o", owner.id()).param("p", partner.id())
                    .query(Link.class).optional().orElseThrow(() -> ApiException.notFound("Shared source"));
            PartnerScope need = handler instanceof HandlerMethod hm ? hm.getMethodAnnotation(PartnerScope.class) : null;
            if (need == null || !link.scopeSet().contains(need.value()) || !callerScopes.contains(need.value())) throw ApiException.notFound("Shared data");
            String ch = vars.get("channelId");
            if (ch != null && !channelShared(link, ch)) throw ApiException.notFound("Channel");
            TenantScope.set(owner.id());              // row-level security: only the owner's rows, and only what we then read
            MDC.put("orgId", owner.id().toString());
            audit.partner(owner.id(), partner.id(), userId, "partner.read", ch != null ? "channel" : "organization", ch != null ? ch : owner.id(),
                    partner.name() + " (" + (clientName != null ? "API client '" + clientName + "'" : "portal") + ") read " + need.value()
                            + " — " + req.getRequestURI().replaceAll(".*/sources/[^/]+", ""));
        }
        boolean canManage=userId!=null&&AccessRoleServiceV1_0.permissions(db,partner.id(),userId,role).contains(Tenant.Perm.PARTNER_MANAGE);
        req.setAttribute(ATTR, new Ctx(partner.id(), partner.slug(), partner.name(), userId, role, clientId, clientName, callerScopes, link,canManage));
        return true;
    }

    private Org org(String key, String kind) {
        UUID id = null;
        try { if (key.length() == 36) id = UUID.fromString(key); } catch (IllegalArgumentException ignored) { }
        return db.sql("select id, slug, name, status from organization where kind = :k and " + (id != null ? "id = :key" : "slug = :key"))
                .param("k", kind).param("key", id != null ? id : key).query(Org.class).optional()
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private boolean channelShared(Link link, String channel) {
        UUID ch;
        try { ch = UUID.fromString(channel); } catch (IllegalArgumentException e) { return false; }
        return link.allChannels()
                ? TenantScope.bypass(() -> db.sql("select count(*) from channel where org_id = :o and id = :c and status <> 'ARCHIVED'")
                        .param("o", link.ownerId()).param("c", ch).query(Integer.class).single()) > 0
                : db.sql("select count(*) from partner_link_channel where link_id = :l and channel_id = :c").param("l", link.id()).param("c", ch)
                        .query(Integer.class).single() > 0;
    }

    @SuppressWarnings("unchecked")
    private static boolean tokenLists(Jwt jwt, UUID org) {
        if (Boolean.TRUE.equals(jwt.getClaim("orgs_truncated"))) return true;
        List<Map<String, Object>> orgs = jwt.getClaim("orgs");
        return orgs != null && orgs.stream().anyMatch(o -> org.toString().equals(o.get("id")));
    }

    // ponytail: per-instance sliding window; with several servers use a shared store (or the API gateway's limits)
    private void limit(UUID partner) {
        long now = System.currentTimeMillis();
        Deque<Long> q = calls.computeIfAbsent(partner, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < now - 60_000) q.pollFirst();
            if (q.size() >= perMinute) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests from this partner (" + perMinute + " per minute). Slow down and try again shortly.");
            q.addLast(now);
        }
    }
}
