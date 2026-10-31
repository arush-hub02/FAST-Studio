package com.faststudio.v1.core.tenant;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single tenant-resolution point for /api/orgs/{org}/**. {org} is the organization's ID (UUID, stable) or its
 * slug (readable web name). The signed-in user's membership authorizes it, and a sign-in token must list it too.
 * Non-members get 404 (the org's existence is not revealed); suspended orgs get 403. Every answer carries X-Org-Id
 * and every log line of the request is tagged with it. Partner organizations own no content: of these routes they
 * only use /members (their team); what customers share with them is under /api/partners (PartnerInterceptor).
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    public static final String ATTR = "fs.tenant";
    private final JdbcClient db;

    public TenantInterceptor(JdbcClient db) {
        this.db = db;
    }

    record Row(UUID orgId, String slug, String name, String status, String role, String kind) {}

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        var vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || !vars.containsKey("org")) return true;
        UUID userId = Auth.userId();
        String key = vars.get("org");
        UUID id = asUuid(key);
        Row row = db.sql("""
                select o.id as org_id, o.slug, o.name,
                case when t.status='SUSPENDED' then 'SUSPENDED' else o.status end as status, m.role, o.kind
                from organization o join membership m on m.org_id = o.id
                left join customer_tenant t on t.id=o.tenant_id
                where""" + (id != null ? " o.id = :key" : " o.slug = :key") + " and m.user_id = :user and o.kind <> 'PLATFORM'")
                .param("key", id != null ? id : key).param("user", userId)
                .query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (!tokenLists(row.orgId())) throw ApiException.notFound("Organization");
        if ("PARTNER".equals(row.kind()) && !req.getRequestURI().matches(".*/api/orgs/[^/]+/members(/.*)?")) throw ApiException.notFound("Page");
        if (!"ACTIVE".equals(row.status())) throw new ApiException(HttpStatus.FORBIDDEN, "ORG_SUSPENDED",
                "This organization is suspended by the platform administrator. Your data is kept safe; contact support.");
        var role=Tenant.Role.valueOf(row.role());
        req.setAttribute(ATTR, new Tenant(row.orgId(), row.slug(), row.name(), userId, role, row.kind(),
                AccessRoleServiceV1.permissions(db,row.orgId(),userId,role)));
        res.setHeader("X-Org-Id", row.orgId().toString());
        MDC.put("orgId", row.orgId().toString());
        TenantScope.set(row.orgId());       // database row-level security now only shows this tenant's rows
        return true;
    }

    /** The path decides the organization and the sign-in token must agree (it lists every org the person had when minted). */
    @SuppressWarnings("unchecked")
    static boolean tokenLists(UUID orgId) {
        Jwt jwt = Tokens.current();
        if (jwt == null || Boolean.TRUE.equals(jwt.getClaim("orgs_truncated"))) return true;
        List<Map<String, Object>> orgs = jwt.getClaim("orgs");
        return orgs != null && orgs.stream().anyMatch(o -> orgId.toString().equals(o.get("id")));
    }

    static UUID asUuid(String s) {
        try { return s.length() == 36 ? UUID.fromString(s) : null; } catch (IllegalArgumentException e) { return null; }
    }
}
