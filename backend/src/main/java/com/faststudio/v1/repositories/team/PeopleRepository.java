package com.faststudio.v1.repositories.team;

import com.faststudio.v1.dto.team.PeopleDto.Membership;
import com.faststudio.v1.dto.team.PeopleDto.Person;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the platform's people list: every account with its memberships, in one query per page. Run as the system. */
@Repository
public class PeopleRepository {
    private final JdbcClient db;
    private final JsonMapper json;

    public PeopleRepository(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private record Row(UUID id, String email, String name, boolean platformAdmin, boolean disabled, OffsetDateTime createdAt,
                       String memberships, int total) {}

    private static final String SELECT = """
            select u.id, u.email, u.name, u.platform_admin, u.disabled_at is not null as disabled, u.created_at,
              coalesce((select json_agg(json_build_object('orgId', o.id, 'slug', o.slug, 'name', o.name, 'kind', o.kind,
                          'role', m.role, 'customRoleKey', m.custom_role_key) order by o.name)
                        from membership m join organization o on o.id = m.org_id where m.user_id = u.id), '[]')::text as memberships,
              count(*) over () as total
            from app_user u""";

    /** People whose name or email contains q (blank = everyone), by name; deleted (anonymized) accounts are left out. */
    public List<Person> page(String q, int limit, int offset, int[] total) {
        List<Row> rows = db.sql(SELECT + """

                where u.email not like 'deleted-%@deleted.invalid'
                  and (:q = '' or u.email ilike '%' || :q || '%' or u.name ilike '%' || :q || '%')
                order by lower(u.name), u.email limit :l offset :off""")
                .param("q", q).param("l", limit).param("off", offset).query(Row.class).list();
        total[0] = rows.isEmpty() ? 0 : rows.getFirst().total();
        return rows.stream().map(this::person).toList();
    }

    public Optional<Person> person(UUID id) {
        return db.sql(SELECT + " where u.id = :id").param("id", id).query(Row.class).optional().map(this::person);
    }

    private Person person(Row r) {
        return new Person(r.id(), r.email(), r.name(), r.platformAdmin(), r.disabled(), r.createdAt(),
                Arrays.asList(json.readValue(r.memberships(), Membership[].class)));
    }

    /** Kind of an organization a person can join (CUSTOMER or PARTNER); empty for the platform's own or unknown ones. */
    public Optional<String> joinableKind(UUID org) {
        return db.sql("select kind from organization where id = :o and kind in ('CUSTOMER','PARTNER')").param("o", org).query(String.class).optional();
    }

    public boolean setDisabled(UUID user, boolean disabled) {
        return db.sql("update app_user set disabled_at = case when :d then coalesce(disabled_at, now()) end where id = :u")
                .param("d", disabled).param("u", user).update() > 0;
    }

    /** Ends every sign-in of the person (refresh tokens), so a switched-off account can't come back by refreshing. */
    public void revokeSignIns(UUID user) {
        db.sql("update refresh_token set revoked_at = now() where user_id = :u and revoked_at is null").param("u", user).update();
    }
}
