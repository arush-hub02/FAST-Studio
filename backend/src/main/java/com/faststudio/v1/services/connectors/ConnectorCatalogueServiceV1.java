package com.faststudio.v1.services.connectors;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.*;
import com.faststudio.v1.repositories.connectors.ConnectorCatalogueRepository;
import com.faststudio.v1.repositories.connectors.ConnectorCatalogueRepository.ConnectorRow;
import com.faststudio.v1.repositories.connectors.ConnectorCatalogueRepository.InstanceRow;
import com.faststudio.v1.repositories.connectors.ConnectorCatalogueRepository.PropertyRow;
import com.faststudio.v1.repositories.connectors.ConnectorCatalogueRepository.ValueRow;
import com.faststudio.v1.services.tenancy.CatalogueServiceV1;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The connector marketplace (docs/PLATFORM_ADMIN_PLAN.md, P3): platform administrators keep categories, connector
 * details and each connector's fields (rows, not code); a project's connectors are instances whose values follow those
 * fields. Tenants see only published connectors their project may use and whose required permission they hold, and only
 * the external fields; they connect one themselves only when its rule is ALLOWED. Encrypted values are sealed at rest and
 * never returned. Adapters read values by property key ({@link #values}).
 */
@Service
public class ConnectorCatalogueServiceV1 {
    private final ConnectorCatalogueRepository repository;
    private final TenantConfigurationServiceV1 configuration;
    private final CatalogueServiceV1 catalogue;
    private final Secrets secrets;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Audit audit;

    public ConnectorCatalogueServiceV1(ConnectorCatalogueRepository repository, TenantConfigurationServiceV1 configuration, CatalogueServiceV1 catalogue,
                                       Secrets secrets, JsonMapper json, TransactionTemplate tx, Audit audit) {
        this.repository = repository;
        this.configuration = configuration;
        this.catalogue = catalogue;
        this.secrets = secrets;
        this.json = json;
        this.tx = tx;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ platform: categories, details, fields

    public Catalogue catalogue() {
        return TenantScope.bypass(() -> new Catalogue(repository.categories(), connectors(p -> true)));
    }

    public Category saveCategory(CategoryRequest r, boolean create) {
        if (r.key() == null || !r.key().matches("[A-Z][A-Z0-9_]{1,39}")) throw ApiException.bad("Category identifier: capitals, digits and _ (e.g. ADS).");
        system(() -> {
            if (create == repository.categoryExists(r.key())) throw create ? taken(r.key()) : ApiException.notFound("Connector category");
            repository.saveCategory(r, create);
        });
        log("category.save", r.key());
        return catalogue().categories().stream().filter(c -> c.key().equals(r.key())).findFirst().orElseThrow();
    }

    public void deleteCategory(String key) {
        system(() -> {
            if (repository.categoryInUse(key)) throw ApiException.conflict("IN_USE", "Move the category's connectors to another category first.");
            if (repository.deleteCategory(key) == 0) throw ApiException.notFound("Connector category");
        });
        log("category.delete", key);
    }

    public Connector saveDetails(String key, DetailsRequest r) {
        system(() -> {
            connector(key);
            repository.saveDetails(key, Objects.toString(r.longDescription(), "").trim(), blank(r.logoUrl()), blank(r.darkLogoUrl()), blank(r.referenceLink()),
                    r.tier(), r.status(), r.tenantUpdate(), blank(r.requiredPermission()), r.level(), r.multiInstance());
        });
        log("details.save", key);
        return catalogue().connectors().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    /** Adds or changes a field of a connector. Changing whether it is encrypted clears stored values (they can't be converted). */
    public Connector saveProperty(String connectorKey, PropertyRequest r) {
        if (r.key() == null || !r.key().matches("[a-z][a-z0-9_]{1,63}")) throw ApiException.bad("Field key: lower case, digits and _ (e.g. token_id).");
        if (r.valueType().equals("SELECT") && (r.options() == null || r.options().isEmpty())) throw ApiException.bad("A list field needs its choices.");
        String options = json.writeValueAsString(r.valueType().equals("SELECT") ? r.options() : List.of());
        system(() -> {
            connector(connectorKey);
            Optional<PropertyRow> old = repository.properties().stream().filter(p -> p.connectorKey().equals(connectorKey) && p.key().equals(r.key())).findFirst();
            repository.saveProperty(connectorKey, r, options);
            if (old.isPresent() && old.get().encrypted() != r.encrypted()) repository.deleteValuesOf(connectorKey, r.key());
        });
        log("property.save", connectorKey + "." + r.key());
        return catalogue().connectors().stream().filter(c -> c.key().equals(connectorKey)).findFirst().orElseThrow();
    }

    public Connector deleteProperty(String connectorKey, String key) {
        system(() -> {
            if (repository.deleteProperty(connectorKey, key) == 0) throw ApiException.notFound("Connector field");
            repository.deleteValuesOf(connectorKey, key);
        });
        log("property.delete", connectorKey + "." + key);
        return catalogue().connectors().stream().filter(c -> c.key().equals(connectorKey)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ marketplace views

    /** A project's connectors as a member sees them. */
    public Marketplace forProject(Tenant t) {
        t.require(Tenant.Perm.VIEW);
        Set<String> perms = new HashSet<>(catalogue.permissions(t));
        return marketplace(t.orgId(), t, c -> c.level().equals("PROJECT") && c.enabled() && !c.status().equals("UNPUBLISHED")
                && configuration.connectorEnabled(t.orgId(), c.key()) && (c.requiredPermission() == null || perms.contains(c.requiredPermission())));
    }

    /** A project's connectors as the platform sees them (tenant page): every project-level connector, internal fields included. */
    public Marketplace forPlatformProject(UUID org) { return marketplace(org, null, c -> c.level().equals("PROJECT")); }

    /** Platform-level connectors (e.g. R2): one set of values for the whole platform. */
    public Marketplace forPlatform() { return marketplace(null, null, c -> c.level().equals("PLATFORM")); }

    // ------------------------------------------------------------------ connecting

    /** Creates (id null) or updates an instance of a connector for a project (org) or the platform (org null). caller null = platform. */
    public ConnectorState saveInstance(Tenant caller, UUID org, String key, UUID id, InstanceRequest r) {
        Connector c = visible(caller, org, key);
        requireConnect(caller, c);
        Map<String, Property> fields = c.properties().stream().collect(Collectors.toMap(Property::key, p -> p));
        Map<String, String> sealed = new LinkedHashMap<>();
        for (var e : r.values().entrySet()) {
            Property p = fields.get(e.getKey());
            if (p == null) throw ApiException.bad("Unknown field " + e.getKey() + " for " + c.name() + ".");
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.isEmpty()) check(p, v);
            sealed.put(p.key(), v.isEmpty() ? (p.encrypted() ? null : "") : p.encrypted() ? secrets.seal(v) : v);
        }
        UUID saved = scoped(org, () -> tx.execute(s -> {
            UUID instance = id;
            Optional<InstanceRow> existing = repository.instances(org).stream().filter(i -> i.connectorKey().equals(key)
                    && (id == null ? !c.multiInstance() && i.position() == 0 : i.id().equals(id))).findFirst();
            if (id != null && existing.isEmpty()) throw ApiException.notFound("Connection");
            String name = r.name() == null || r.name().isBlank() ? existing.map(InstanceRow::name).filter(n -> !n.isBlank()).orElse(c.name()) : r.name().trim();
            boolean enabled = r.enabled() == null ? existing.map(InstanceRow::enabled).orElse(true) : r.enabled();
            if (existing.isPresent()) {
                instance = existing.get().id();
                repository.updateInstance(instance, org, name, enabled);
            } else {
                instance = repository.insertInstance(key, org, name, c.multiInstance() ? repository.nextPosition(key, org) : 0, enabled);
            }
            for (var e : sealed.entrySet()) {
                if (e.getValue() == null) continue;                       // blank encrypted value: keep the stored one
                if (e.getValue().isEmpty()) repository.removeValue(instance, org, e.getKey());
                else repository.putValue(instance, org, e.getKey(), e.getValue());
            }
            return instance;
        }));
        record(caller, org, "connector.connect", key, c.name() + " (" + saved + ")");
        return state(caller, org, key);
    }

    /** Removes an instance (disconnects the connector, or removes one ad server of a list). Its values go with it. */
    public ConnectorState deleteInstance(Tenant caller, UUID org, String key, UUID id) {
        Connector c = visible(caller, org, key);
        requireConnect(caller, c);
        if (scoped(org, () -> repository.deleteInstance(id, org)) == 0) throw ApiException.notFound("Connection");
        record(caller, org, "connector.disconnect", key, c.name());
        return state(caller, org, key);
    }

    /**
     * The values of a project's (or the platform's) enabled instance of a connector, decrypted, by property key, with
     * defaults filled in: what adapters read (Mux, R2, ad servers). Empty when not connected.
     */
    public List<Map<String, String>> values(UUID org, String key) {
        return scoped(org, () -> {
            Map<String, PropertyRow> fields = repository.properties().stream().filter(p -> p.connectorKey().equals(key))
                    .collect(Collectors.toMap(PropertyRow::key, p -> p, (a, b) -> a, LinkedHashMap::new));
            Map<UUID, List<ValueRow>> byInstance = repository.values(org).stream().collect(Collectors.groupingBy(ValueRow::instanceId));
            List<Map<String, String>> out = new ArrayList<>();
            for (InstanceRow i : repository.instances(org)) {
                if (!i.connectorKey().equals(key) || !i.enabled()) continue;
                Map<String, String> v = new LinkedHashMap<>();
                fields.values().stream().filter(p -> p.defaultValue() != null).forEach(p -> v.put(p.key(), p.defaultValue()));
                for (ValueRow row : byInstance.getOrDefault(i.id(), List.of())) {
                    PropertyRow p = fields.get(row.propertyKey());
                    if (p != null) v.put(p.key(), p.encrypted() ? secrets.open(row.value()) : row.value());
                }
                out.add(v);
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ building views

    private List<Connector> connectors(java.util.function.Predicate<ConnectorRow> keep) {
        Map<String, List<Property>> props = repository.properties().stream().map(this::property)
                .collect(Collectors.groupingBy(Property::connectorKey, LinkedHashMap::new, Collectors.toList()));
        return repository.connectors().stream().filter(keep).map(c -> connector(c, props.getOrDefault(c.key(), List.of()))).toList();
    }

    private Marketplace marketplace(UUID org, Tenant caller, java.util.function.Predicate<ConnectorRow> keep) {
        List<Connector> list = TenantScope.bypass(() -> connectors(keep));
        Map<String, List<InstanceRow>> instances = scoped(org, () -> repository.instances(org)).stream().collect(Collectors.groupingBy(InstanceRow::connectorKey));
        Map<UUID, Map<String, String>> values = new HashMap<>();
        scoped(org, () -> repository.values(org)).forEach(v -> values.computeIfAbsent(v.instanceId(), k -> new HashMap<>()).put(v.propertyKey(), v.value()));
        List<ConnectorState> states = new ArrayList<>();
        for (Connector c : list) {
            List<Instance> views = instances.getOrDefault(c.key(), List.of()).stream().map(i -> instance(c, i, values.getOrDefault(i.id(), Map.of()), caller)).toList();
            Connector shown = caller == null ? c : withProperties(c, c.properties().stream().filter(p -> p.scope().equals("EXTERNAL")).toList());
            String note = c.status().equals("UPCOMING") ? "Coming soon."
                    : caller == null ? null
                    : c.tenantUpdate().equals("CONTACT_ACCOUNT_MANAGER") ? "Contact your account manager to connect this."
                    : c.tenantUpdate().equals("NOT_ALLOWED") ? "Your platform administrator connects this." : null;
            states.add(new ConnectorState(shown, views, views.stream().anyMatch(Instance::connected), canConnect(caller, c), note));
        }
        return new Marketplace(TenantScope.bypass(repository::categories), states);
    }

    private ConnectorState state(Tenant caller, UUID org, String key) {
        Marketplace m = caller != null ? forProject(caller) : org == null ? forPlatform() : forPlatformProject(org);
        return m.connectors().stream().filter(s -> s.connector().key().equals(key)).findFirst().orElseThrow();
    }

    /** Values shown for one instance; missing = required fields (all scopes) with neither a value nor a default. */
    private Instance instance(Connector c, InstanceRow i, Map<String, String> stored, Tenant caller) {
        Map<String, Value> shown = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Property p : c.properties()) {
            String v = stored.get(p.key());
            if (p.required() && v == null && p.defaultValue() == null) missing.add(p.key());
            if (caller != null && !p.scope().equals("EXTERNAL")) continue;
            shown.put(p.key(), p.encrypted() ? new Value(null, v != null) : new Value(v != null ? v : p.defaultValue(), v != null));
        }
        return new Instance(i.id(), i.name(), i.position(), i.enabled(), i.enabled() && missing.isEmpty(), missing, shown, i.checkedAt(), i.checkError());
    }

    private boolean canConnect(Tenant caller, Connector c) {
        if (!c.status().equals("AVAILABLE")) return false;
        return caller == null || (c.tenantUpdate().equals("ALLOWED") && caller.permissions().contains(Tenant.Perm.TEAM_MANAGE));
    }

    /** The connector, if this caller can see it at this scope (404 otherwise, never revealing hidden connectors). */
    private Connector visible(Tenant caller, UUID org, String key) {
        Marketplace m = caller != null ? forProject(caller) : org == null ? forPlatform() : forPlatformProject(org);
        return m.connectors().stream().map(ConnectorState::connector).filter(c -> c.key().equals(key)).findFirst()
                .map(c -> caller == null ? c : withProperties(c, c.properties()))
                .orElseThrow(() -> ApiException.notFound("Connector"));
    }

    private void requireConnect(Tenant caller, Connector c) {
        if (caller != null) caller.require(Tenant.Perm.TEAM_MANAGE);
        if (canConnect(caller, c)) return;
        if (!c.status().equals("AVAILABLE")) throw ApiException.conflict("NOT_AVAILABLE", "This connector is coming soon and can't be connected yet.");
        throw new ApiException(HttpStatus.FORBIDDEN, "CONNECT_NOT_ALLOWED", c.tenantUpdate().equals("CONTACT_ACCOUNT_MANAGER")
                ? "Contact your account manager to connect this." : "Your platform administrator connects this.");
    }

    /** Type checks of a non-blank value. */
    private static void check(Property p, String v) {
        boolean ok = switch (p.valueType()) {
            case "NUMBER" -> v.matches("-?\\d+(\\.\\d+)?");
            case "BOOLEAN" -> v.equals("true") || v.equals("false");
            case "SELECT" -> p.options().contains(v);
            case "URL" -> {
                try { URI u = URI.create(v); yield u.getHost() != null && ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())); }
                catch (IllegalArgumentException e) { yield false; }
            }
            default -> true;
        };
        if (!ok) throw ApiException.bad(p.label() + ": " + switch (p.valueType()) {
            case "NUMBER" -> "enter a number.";
            case "BOOLEAN" -> "choose yes or no.";
            case "SELECT" -> "choose one of " + String.join(", ", p.options()) + ".";
            default -> "enter an http(s) address.";
        });
    }

    private Property property(PropertyRow r) {
        List<String> options = Arrays.asList(json.readValue(r.options(), String[].class));
        return new Property(r.connectorKey(), r.key(), r.label(), r.description(), r.valueType(), options, r.defaultValue(), r.position(),
                r.required(), r.encrypted(), r.scope());
    }

    private static Connector connector(ConnectorRow c, List<Property> props) {
        return new Connector(c.key(), c.name(), c.category(), c.driver(), c.enabled(), c.description(), c.longDescription(), c.logoUrl(), c.darkLogoUrl(),
                c.referenceLink(), c.tier(), c.status(), c.tenantUpdate(), c.requiredPermission(), c.level(), c.multiInstance(), props);
    }

    private Connector connector(String key) {
        return repository.connectors().stream().filter(c -> c.key().equals(key)).findFirst().map(c -> connector(c, List.of()))
                .orElseThrow(() -> ApiException.notFound("Connector"));
    }

    private Connector withProperties(Connector c, List<Property> p) {
        return new Connector(c.key(), c.name(), c.category(), c.driver(), c.enabled(), c.description(), c.longDescription(), c.logoUrl(), c.darkLogoUrl(),
                c.referenceLink(), c.tier(), c.status(), c.tenantUpdate(), c.requiredPermission(), c.level(), c.multiInstance(), p);
    }

    /** Project data runs as the project; platform-level data (org null) on the system login. */
    private static <T> T scoped(UUID org, Supplier<T> work) { return org == null ? TenantScope.bypass(work) : TenantScope.as(org, work); }

    private void system(Runnable work) { TenantScope.bypass(() -> tx.execute(s -> { work.run(); return null; })); }

    private void log(String action, String key) { audit.platform(Auth.userId(), "connector." + action, "connector", key, key); }

    private void record(Tenant caller, UUID org, String action, String key, String detail) {
        if (caller != null) audit.log(caller, action, "connector", key, detail);
        else if (org != null) audit.log(org, Auth.userId(), action, "connector", key, detail + " (platform)");
        else audit.platform(Auth.userId(), action, "connector", key, detail);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static ApiException taken(String key) { return ApiException.conflict("KEY_TAKEN", "The identifier " + key + " is already used. Choose another one."); }
}
