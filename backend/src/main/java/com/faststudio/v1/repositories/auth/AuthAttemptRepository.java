package com.faststudio.v1.repositories.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;

/** SQL for rate-limit attempts (auth_attempt): one row per counted attempt, shared by all server instances. */
@Repository
public class AuthAttemptRepository {
    private final JdbcClient db;

    public AuthAttemptRepository(JdbcClient db) { this.db = db; }

    public int count(String bucket, Duration window) {
        return db.sql("select count(*) from auth_attempt where bucket = :b and created_at > :since")
                .param("b", bucket).param("since", OffsetDateTime.now().minus(window)).query(Integer.class).single();
    }

    public void add(String bucket) {
        db.sql("insert into auth_attempt(bucket) values (:b)").param("b", bucket).update();
    }

    public void clear(String bucket) {
        db.sql("delete from auth_attempt where bucket = :b").param("b", bucket).update();
    }

    /** Housekeeping: windows are minutes to an hour, so older rows are never read again. */
    public int deleteOlderThan(Duration age) {
        return db.sql("delete from auth_attempt where created_at < :t").param("t", OffsetDateTime.now().minus(age)).update();
    }
}
