package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.repositories.tenancy.TenantDataRepository;
import com.faststudio.v1.repositories.tenancy.TenantDataRepository.ForeignKey;
import com.faststudio.v1.repositories.tenancy.TenantDataRepository.Table;
import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * A customer tenant's data, all of it: export (for the customer, or before deletion) and permanent deletion when a
 * customer leaves. Platform administrators only (the /api/platform routes run as the system). Deletion requires the
 * tenant to be suspended first and its exact name typed as confirmation; people who belonged only to this tenant are
 * anonymized rather than deleted, because audit records elsewhere may still name them.
 */
@Service
public class TenantDataService {
    private static final Logger log = LoggerFactory.getLogger(TenantDataService.class);

    private final TenantDataRepository repository;
    private final MediaServiceV1 media;
    private final EdgePublisher edge;
    private final Audit audit;

    public TenantDataService(TenantDataRepository repository, MediaServiceV1 media, EdgePublisher edge, Audit audit) {
        this.repository = repository;
        this.media = media;
        this.edge = edge;
        this.audit = audit;
    }

    private Map<String, Object> tenant(UUID tenant) {
        return repository.tenant(tenant).orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    /** Every row the tenant owns, table by table, without credentials, hashes or unredacted mail. Media files are not included. */
    @Transactional   // one snapshot of every table; writes only the audit record
    public Map<String, Object> export(UUID tenant) {
        Map<String, Object> t = tenant(tenant);
        List<UUID> orgs = repository.organizations(tenant);
        Map<String, Object> tables = new TreeMap<>();
        for (Table table : repository.tables()) {
            var rows = repository.rows(table, tenant, orgs);
            if (!rows.isEmpty()) tables.put(table.name(), rows);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now().toString());
        out.put("tenant", t);
        out.put("organizations", repository.organizationRows(tenant));
        out.put("members", orgs.isEmpty() ? List.of() : repository.members(orgs));
        out.put("tables", tables);
        out.put("notIncluded", List.of("video files (download them from the library or the edge storage)",
                "credentials and secret settings", "platform-wide settings and other tenants' data"));
        audit.platform(Auth.userId(), "tenant.export", "tenant", tenant, tables.size() + " tables");
        return out;
    }

    /** Permanently deletes the tenant, its projects and everything they own. Returns rows deleted per table. */
    @Transactional
    public Map<String, Integer> delete(UUID tenant, String confirmation) {
        Map<String, Object> t = tenant(tenant);
        if (!"SUSPENDED".equals(t.get("status")))
            throw ApiException.conflict("NOT_SUSPENDED", "Suspend the tenant first, so nobody is working in it while it is deleted.");
        if (!Objects.equals(t.get("name"), confirmation))
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION", "Type the tenant's exact name to confirm the deletion.");
        List<UUID> orgs = repository.organizations(tenant);
        List<String> slugs = repository.organizationRows(tenant).stream().map(o -> String.valueOf(o.get("slug"))).toList();
        List<Table> tables = repository.tables();
        Map<String, Table> byName = new HashMap<>();
        tables.forEach(x -> byName.put(x.name(), x));

        edge.takeOffAir(orgs);                                     // viewers see the standby slate, not a stale schedule
        List<UUID> people = orgs.isEmpty() ? List.of() : repository.exclusiveUsers(orgs);

        // References between the tenant's own tables: clear the nullable ones (this breaks cycles), then delete
        // children before parents.
        List<ForeignKey> fks = repository.foreignKeys().stream()
                .filter(k -> byName.containsKey(k.child()) && byName.containsKey(k.parent()) && !k.child().equals(k.parent())).toList();
        for (ForeignKey k : fks) if (k.nullable()) repository.clear(byName.get(k.child()), k.column(), tenant, orgs);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (Table table : order(tables, fks.stream().filter(k -> !k.nullable()).toList()))
            deleted.put(table.name(), repository.delete(table, tenant, orgs));
        repository.anonymize(people);
        repository.deleteOrganizationsAndTenant(tenant);
        deleted.put("people anonymized", people.size());

        deleteFiles(orgs, slugs);
        audit.platform(Auth.userId(), "tenant.delete", "tenant", tenant, t.get("name") + ": " + deleted);
        return deleted;
    }

    /** Children first: a table is deleted only once every table that references it (non-nullably) is done. */
    static List<Table> order(List<Table> tables, List<ForeignKey> required) {
        List<Table> out = new ArrayList<>(), left = new ArrayList<>(tables);
        while (!left.isEmpty()) {
            Set<String> pending = new HashSet<>();
            left.forEach(x -> pending.add(x.name()));
            List<Table> ready = left.stream().filter(x -> required.stream()
                    .noneMatch(k -> k.parent().equals(x.name()) && pending.contains(k.child()))).toList();
            if (ready.isEmpty()) throw new IllegalStateException("Circular required references between " + pending);
            out.addAll(ready);
            left.removeAll(ready);
        }
        return out;
    }

    /**
     * Local media ({slug}/…) and upload ({org id}/…) folders of the deleted projects. Runs before the transaction commits;
     * if the commit then failed, the projects would remain without their files (re-upload or re-sync restores them).
     * ponytail: copies already on the edge storage (R2) are left there; add an R2 delete by prefix when that storage matters.
     */
    private void deleteFiles(List<UUID> orgs, List<String> slugs) {
        List<java.nio.file.Path> dirs = new ArrayList<>();
        for (UUID org : orgs) dirs.add(media.uploadDir(org, org).getParent());
        for (String slug : slugs) if (slug.matches("[a-z0-9-]+")) dirs.add(media.mediaDir().resolve(slug));
        for (var dir : dirs) {
            try {
                MediaServiceV1.deleteDir(dir);
            } catch (IOException e) {
                log.warn("Couldn't delete {}: {}", dir, e.getMessage());
            }
        }
    }
}
