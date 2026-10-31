package com.faststudio.v1.repositories.team;

import com.faststudio.v1.dto.team.TeamDto.Member;
import com.faststudio.v1.dto.team.TeamDto.OrgSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for people, memberships and organizations.
 * {@code organization}, {@code membership} and {@code app_user} are outside row-level security (they establish the tenant),
 * so every query here filters by organization explicitly.
 */
@Repository
public class MembershipRepository {
    private final JdbcClient db;

    public MembershipRepository(JdbcClient db) { this.db = db; }

    // ------------------------------------------------------------------ people

    public String userName(UUID userId) {
        return db.sql("select name from app_user where id = :u").param("u", userId).query(String.class).single();
    }

    public String userEmail(UUID userId) {
        return db.sql("select email from app_user where id = :u").param("u", userId).query(String.class).single();
    }

    public Optional<UUID> userByEmail(String email) {
        return db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).optional();
    }

    public boolean isPlatformAdmin(UUID userId) {
        return db.sql("select platform_admin from app_user where id = :u").param("u", userId).query(Boolean.class).single();
    }

    /** New account that must choose its own password at first sign-in. */
    public UUID createUser(String email, String name, String passwordHash) {
        return db.sql("insert into app_user(email, name, password_hash, must_change_password) values (:e, :n, :p, true) returning id")
                .param("e", email).param("n", name).param("p", passwordHash).query(UUID.class).single();
    }

    // ------------------------------------------------------------------ memberships

    public List<Member> members(UUID org) {
        return db.sql("""
                select u.id as user_id, u.name, u.email, m.role, m.created_at, m.custom_role_key
                from membership m join app_user u on u.id = m.user_id where m.org_id = :o order by m.role, u.name""")
                .param("o", org).query(Member.class).list();
    }

    public boolean isMember(UUID org, UUID userId) {
        return db.sql("select count(*) from membership where org_id = :o and user_id = :u")
                .param("o", org).param("u", userId).query(Integer.class).single() > 0;
    }

    /** Built-in role name of a member, if they belong to the organization. */
    public Optional<String> role(UUID org, UUID userId) {
        return db.sql("select role from membership where org_id = :o and user_id = :u")
                .param("o", org).param("u", userId).query(String.class).optional();
    }

    /** How many other organizations the person belongs to (their password unlocks those too). */
    public int membershipsElsewhere(UUID org, UUID userId) {
        return db.sql("select count(*) from membership where user_id = :u and org_id <> :o")
                .param("u", userId).param("o", org).query(Integer.class).single();
    }

    public void addMember(UUID org, UUID userId, String role, String customRoleKey) {
        db.sql("insert into membership(org_id, user_id, role, custom_role_key) values (:o, :u, :r, :custom)")
                .param("o", org).param("u", userId).param("r", role).param("custom", customRoleKey).update();
    }

    public void changeRole(UUID org, UUID userId, String role, String customRoleKey) {
        db.sql("update membership set role = :r, custom_role_key = :custom where org_id = :o and user_id = :u")
                .param("r", role).param("custom", customRoleKey).param("o", org).param("u", userId).update();
    }

    public void removeMember(UUID org, UUID userId) {
        db.sql("delete from membership where org_id = :o and user_id = :u").param("o", org).param("u", userId).update();
    }

    /** Locks the organization row so concurrent "last owner" checks cannot both pass. */
    public void lockOrganization(UUID org) {
        db.sql("select id from organization where id = :o for update").param("o", org).query(UUID.class).single();
    }

    /** Owners with the plain built-in role (a custom-role owner may have reduced management permissions). */
    public int plainMembersWithRole(UUID org, String role) {
        return db.sql("select count(*) from membership where org_id = :o and role = :r and custom_role_key is null")
                .param("o", org).param("r", role).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ organizations

    public String organizationName(UUID org) {
        return db.sql("select name from organization where id = :o").param("o", org).query(String.class).single();
    }

    public boolean slugTaken(String slug) {
        return db.sql("select count(*) from organization where slug = :s").param("s", slug).query(Integer.class).single() > 0;
    }

    public List<OrgSummary> organizationSummaries() {
        return db.sql("""
                select o.id, o.slug, o.name, o.status, o.country, o.timezone, o.plan, o.max_channels,
                  (select count(*) from membership m where m.org_id = o.id) as members,
                  (select count(*) from channel c where c.org_id = o.id and c.status <> 'ARCHIVED') as channels,
                  (select count(*) from channel c where c.org_id = o.id and c.status = 'LIVE') as live_channels,
                  (select count(*) from asset a where a.org_id = o.id and a.status <> 'ARCHIVED') as videos,
                  (select count(*) from playback_session s where s.org_id = o.id) as sessions, o.created_at, o.kind,
                  (select count(*) from partner_link l where (l.owner_org_id = o.id or l.partner_org_id = o.id) and l.status = 'ACTIVE') as partner_links
                from organization o where o.kind <> 'PLATFORM' order by o.kind, o.name""")
                .query(OrgSummary.class).list();
    }

    /** Inserts an organization; for CUSTOMER the database trigger also creates its customer tenant. */
    public UUID createOrganization(String slug, String name, String country, String timezone, String kind, int maxChannels) {
        return db.sql("insert into organization(slug, name, country, timezone, kind, max_channels) values (:s, :n, :c, :tz, :k, :max) returning id")
                .param("s", slug).param("n", name).param("c", country).param("tz", timezone).param("k", kind).param("max", maxChannels)
                .query(UUID.class).single();
    }

    /** Every customer project starts with its own web player destination so it can watch its channels at once. */
    public void createWebPlayerDestination(UUID org) {
        db.sql("""
                insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour)
                values (:o, 'Web player', 'OWNED_WEB', 'SSAI', 'DATERANGE', 720)""").param("o", org).update();
    }

    /** Returns false when no such (non-platform) organization exists. */
    public boolean setOrganizationStatus(UUID org, String status) {
        return db.sql("update organization set status = :s where id = :id and kind <> 'PLATFORM'")
                .param("s", status).param("id", org).update() > 0;
    }
}
