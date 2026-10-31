package com.faststudio.v1.repositories.programming;

import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.services.programming.Planner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQL for schedule versions and entries (RLS-scoped to the project; queries also filter by org). */
@Repository
public class ScheduleRepository {
    private final JdbcClient db;

    public ScheduleRepository(JdbcClient db) { this.db = db; }

    // ------------------------------------------------------------------ versions

    /** Every new channel gets an empty draft (version 0). */
    public void createDraft(UUID org, UUID channel, UUID createdBy) {
        db.sql("insert into schedule_version(org_id, channel_id, number, status, created_by) values (:o, :c, 0, 'DRAFT', :u)")
                .param("o", org).param("c", channel).param("u", createdBy).update();
    }

    /** Optimistic bump of a draft's revision; false when it no longer has the expected revision. */
    public boolean bumpRevision(UUID org, UUID version, int expected) {
        return db.sql("update schedule_version set revision = revision + 1 where org_id = :o and id = :v and revision = :r")
                .param("o", org).param("v", version).param("r", expected).update() > 0;
    }

    public int versionNumber(UUID version) {
        return db.sql("select number from schedule_version where id = :v").param("v", version).query(Integer.class).single();
    }

    /** Serializes publications per channel. */
    public void lockChannel(UUID org, UUID channel) {
        db.sql("select id from channel where org_id = :o and id = :id for update").param("o", org).param("id", channel).query(UUID.class).single();
    }

    public int nextVersionNumber(UUID channel) {
        return db.sql("select coalesce(max(number), 0) + 1 from schedule_version where channel_id = :c").param("c", channel).query(Integer.class).single();
    }

    public UUID insertPublishedVersion(UUID org, UUID channel, int number, String note, UUID createdBy) {
        return db.sql("""
                insert into schedule_version(org_id, channel_id, number, status, note, created_by, published_at)
                values (:o, :c, :n, 'PUBLISHED', :note, :u, now()) returning id""")
                .param("o", org).param("c", channel).param("n", number).param("note", note).param("u", createdBy).query(UUID.class).single();
    }

    /** Marks every other published version of the channel superseded and points the channel at the new one. */
    public void makeCurrent(UUID org, UUID channel, UUID version) {
        db.sql("update schedule_version set status = 'SUPERSEDED' where org_id = :o and channel_id = :c and status = 'PUBLISHED' and id <> :v")
                .param("o", org).param("c", channel).param("v", version).update();
        db.sql("update channel set published_version_id = :v, updated_at = now() where org_id = :o and id = :c")
                .param("v", version).param("o", org).param("c", channel).update();
    }

    // ------------------------------------------------------------------ entries

    public void insertEntries(UUID org, UUID version, List<Planner.Item> items) {
        for (Planner.Item it : items)
            db.sql("""
                    insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership)
                    values (:o, :v, :k, :a, :off, :s, :e, :own)""")
                    .param("o", org).param("v", version).param("k", it.kind()).param("a", it.assetId()).param("off", it.offsetMs())
                    .param("s", Times.utc(it.start())).param("e", Times.utc(it.end())).param("own", it.ownership()).update();
    }

    /** Deletes entries starting at or after {@code from}; returns how many. */
    public int deleteStartingFrom(UUID org, UUID version, Instant from, boolean inclusive) {
        return db.sql("delete from schedule_entry where org_id = :o and version_id = :v and start_at " + (inclusive ? ">=" : ">") + " :n")
                .param("o", org).param("v", version).param("n", Times.utc(from)).update();
    }

    public void deleteEntries(UUID org, UUID version, Collection<UUID> ids) {
        db.sql("delete from schedule_entry where org_id = :o and version_id = :v and id in (:ids)")
                .param("o", org).param("v", version).param("ids", ids).update();
    }

    public void deleteAll(UUID org, UUID version) {
        db.sql("delete from schedule_entry where org_id = :o and version_id = :v").param("o", org).param("v", version).update();
    }

    public void moveEntry(UUID org, UUID entry, Instant start, Instant end) {
        db.sql("update schedule_entry set start_at = :s, end_at = :e where id = :id and org_id = :o")
                .param("s", Times.utc(start)).param("e", Times.utc(end)).param("id", entry).param("o", org).update();
    }

    /** Copies every entry of one version into another (publish, restore). */
    public void copyEntries(UUID org, UUID fromVersion, UUID toVersion) {
        db.sql("""
                insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership, rule_id, rule_date)
                select org_id, :to, kind, asset_id, asset_offset_ms, start_at, end_at, ownership, rule_id, rule_date from schedule_entry
                where org_id = :o and version_id = :from""").param("to", toVersion).param("o", org).param("from", fromVersion).update();
    }
}
