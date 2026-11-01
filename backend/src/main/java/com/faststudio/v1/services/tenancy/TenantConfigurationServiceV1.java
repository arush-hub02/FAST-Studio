package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.dto.tenancy.TenantConfiguration.*;
import com.faststudio.v1.repositories.tenancy.TenantConfigurationRepository.Definition;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.tenancy.TenantConfigurationRepository;
import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Business rules for customer tenants: subscriptions, projects, connector grants and dynamic properties.
 *
 * <p>Who may do what:
 * <ul>
 *   <li>Platform administrators: everything, including plans, global definitions, secrets and connector grants to tenants.</li>
 *   <li>Tenant OWNER (owner of any project in the tenant): tenant-wide default values, because they affect every project.</li>
 *   <li>Project OWNER/ADMIN: overrides and connector opt-in/out for their own project.</li>
 *   <li>Any project member: read the resolved configuration of their project and tenant (secrets are never returned).</li>
 * </ul>
 */
@Service
public class TenantConfigurationServiceV1 {

    /** Built-in properties read by product code (seeded by V15). They can be relabelled and re-defaulted but not disabled. */
    public static final String PREMIERE_TIME = "programming.premiere_time";
    public static final String WEEKDAYS = "programming.weekdays";
    public static final String PERSONALIZED_ADS = "ads.personalized";

    /** Extra domain validation for built-in properties, on top of the type check. */
    private static final Map<String, Consumer<JsonNode>> SYSTEM = Map.of(
            PREMIERE_TIME, v -> MediaSettingsServiceV1.time(v.asString()),
            WEEKDAYS, v -> MediaSettingsServiceV1.days(v.asString()),
            PERSONALIZED_ADS, v -> {},
            TenantLimitsService.API_PER_MINUTE, TenantConfigurationServiceV1::wholeNumber,
            TenantLimitsService.MEDIA_HOURS, TenantConfigurationServiceV1::wholeNumber,
            TenantLimitsService.PROCESSING, TenantConfigurationServiceV1::wholeNumber);

    /** Limits are whole numbers; 0 means unlimited. */
    private static void wholeNumber(JsonNode v) {
        if (!v.isIntegralNumber() || v.asLong() < 0) throw ApiException.bad("Use a whole number (0 = unlimited).");
    }

    private final TenantConfigurationRepository repository;
    private final CapabilityRepository capabilities;
    private final JsonMapper json;
    private final Secrets secrets;
    private final Audit audit;
    private final Tokens tokens;

    // ponytail: per-node 30 s cache for the playback-path connector check; a connector switched off on another
    // app node keeps serving for up to 30 s there. Move to a shared cache if that delay matters.
    private static final long DRIVER_CACHE_MS = 30_000;
    private record CachedFlag(boolean value, long at) {}
    private final Map<String, CachedFlag> driverCache = new ConcurrentHashMap<>();

    public TenantConfigurationServiceV1(TenantConfigurationRepository repository, CapabilityRepository capabilities, JsonMapper json, Secrets secrets,
                                          Audit audit, Tokens tokens) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.json = json;
        this.secrets = secrets;
        this.audit = audit;
        this.tokens = tokens;
    }

    // ------------------------------------------------------------------ access checks

    public boolean admin() {
        return repository.platformAdmin(Auth.userId());
    }

    private void requirePlatform() {
        if (!admin()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
    }

    /**
     * Checks the caller may read (or write) a tenant/project scope and returns a project ID to attribute audit entries to.
     * Writes to the tenant scope need OWNER, because tenant defaults change every project of the tenant.
     */
    private UUID access(UUID tenant, UUID project, boolean write) {
        if (project != null && !repository.tenant(project).equals(tenant)) throw ApiException.notFound("Project");
        if (admin()) return project;
        // Not a member at all: report "not found" so other tenants' IDs are not confirmed.
        UUID readable = memberProject(tenant, project, List.of()).orElseThrow(() -> ApiException.notFound("Tenant or project"));
        if (!write) return readable;
        List<String> roles = project == null ? List.of("OWNER") : List.of("OWNER", "ADMIN");
        return memberProject(tenant, project, roles).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                project == null ? "Only a tenant owner can change tenant-wide defaults." : "Only project owners and admins can change project settings."));
    }

    /**
     * An active project of the tenant (or the given project) where the caller is a member with the given role filter.
     * Prefers the project of the current request, so audit rows land in the project RLS is scoped to.
     */
    private Optional<UUID> memberProject(UUID tenant, UUID project, List<String> roles) {
        return repository.memberProject(tenant, project, Auth.userId(), TenantScope.org(), roles);
    }

    // ------------------------------------------------------------------ plans and tenants (platform only)

    public List<Account> accounts() {
        requirePlatform();
        return repository.accounts();
    }

    public List<Plan> plans() {
        return repository.plans().stream().map(p -> new Plan(p.key(), p.name(), p.enabled(), p.maxProjects(), p.maxChannels(),
                p.monthlyPrice(), p.currency(), Times.codes(p.features()))).toList();
    }

    private Plan plan(String key) {
        return plans().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow(() -> ApiException.notFound("Plan"));
    }

    @Transactional
    public void savePlan(Plan p) {
        requirePlatform();
        if (p.features().stream().anyMatch(f -> !f.matches("[A-Z][A-Z0-9_]{0,79}")))
            throw ApiException.bad("Feature keys must use uppercase letters, digits and underscores.");
        // Lock every tenant on this plan so a concurrent project/channel creation cannot slip past the new limits.
        repository.lockTenantsOnPlan(p.key());
        for (Account t : repository.accounts())
            if (t.planKey().equals(p.key()) && (t.projects() > p.maxProjects() || t.channels() > p.maxChannels()))
                throw ApiException.conflict("PLAN_IN_USE", "This plan would fall below an existing tenant's usage.");
        repository.savePlan(p);
        audit.platform(Auth.userId(), "plan.save", "plan", null, p.key());
    }

    @Transactional
    public void subscription(UUID tenant, Subscription request) {
        requirePlatform();
        repository.lockTenant(tenant);
        Account t = repository.account(tenant);
        Plan p = plan(request.planKey());
        if (!p.enabled()) throw ApiException.bad("This plan is not available for assignment.");
        if (t.projects() > p.maxProjects() || t.channels() > p.maxChannels())
            throw ApiException.conflict("PLAN_LIMIT", "Current usage exceeds the selected plan.");
        if (!repository.setSubscription(tenant, p.key(), request.status(), request.expectedRevision())) throw ApiException.conflict("STALE_CONFIGURATION", "Reload the tenant before saving.");
        audit.platform(Auth.userId(), "tenant.subscription.update", "tenant", tenant, p.key() + " / " + request.status());
    }

    /** Suspending a tenant blocks management in all its projects (playback keeps running); tokens are refreshed at once. */
    @Transactional
    public void status(UUID tenant, String status) {
        requirePlatform();
        if (status == null || !Set.of("ACTIVE", "SUSPENDED").contains(status)) throw ApiException.bad("Invalid status.");
        if (!repository.setStatus(tenant, status)) throw ApiException.notFound("Tenant");
        for (Project p : repository.projects(tenant)) tokens.bumpOrg(p.id());
        audit.platform(Auth.userId(), "tenant.status.update", "tenant", tenant, status);
    }

    /** Adds a project under a tenant, owned by an existing owner/admin of that tenant, within the plan's project limit. */
    @Transactional
    public Project createProject(UUID tenant, CreateProject request) {
        requirePlatform();
        repository.lockTenant(tenant);
        Account t = repository.account(tenant);
        Plan p = plan(t.planKey());
        if (!t.status().equals("ACTIVE")) throw ApiException.bad("Activate the tenant before adding projects.");
        if (t.projects() >= p.maxProjects()) throw ApiException.conflict("PLAN_LIMIT", "The tenant has reached its project limit.");
        try { ZoneId.of(request.timezone()); } catch (Exception e) { throw ApiException.bad("Unknown timezone."); }
        if (repository.slugTaken(request.slug()))
            throw ApiException.conflict("SLUG_TAKEN", "Project slug is already in use.");
        if (repository.owners(tenant).stream().noneMatch(o -> o.id().equals(request.ownerUserId())))
            throw ApiException.bad("Choose an existing owner/admin of this tenant as the new project owner.");
        // The tenant plan's channel limit is enforced in reserveChannel; every project starts with the owned web player.
        UUID id = repository.insertProject(tenant, request, p.maxChannels());
        tokens.bump(List.of(request.ownerUserId()));
        audit.platform(Auth.userId(), "project.create", "project", id, request.slug());
        return repository.projects(tenant).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Owner> owners(UUID tenant) {
        requirePlatform();
        repository.account(tenant);
        return repository.owners(tenant);
    }

    // ------------------------------------------------------------------ connector definitions and grants

    public List<Connector> connectors() { return repository.connectors(); }

    @Transactional
    public void saveConnector(Connector c) {
        requirePlatform();
        Connector old = connectors().stream().filter(x -> x.key().equals(c.key())).findFirst().orElse(null);
        if (old != null && !old.driver().equals(c.driver())) throw ApiException.bad("A connector's driver cannot change. Create a new definition.");
        repository.saveConnector(c, Objects.toString(c.description(), ""));
        driverCache.clear();
        audit.platform(Auth.userId(), "connector.definition.save", "connector", null, c.key());
    }

    /** project == null grants/revokes a connector for the whole tenant (platform only); otherwise a project opts in or out. */
    @Transactional
    public void setConnector(UUID tenant, UUID project, String key, Enable request) {
        UUID scope = access(tenant, project, true);
        if (project == null) requirePlatform();
        TenantScope.bypass(() -> {
            repository.account(tenant);
            Connector c = connectors().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow(() -> ApiException.notFound("Connector"));
            boolean reset = Boolean.TRUE.equals(request.reset());
            if (!reset && request.enabled() == null) throw ApiException.bad("Choose whether to enable the connector.");
            boolean grantedToTenant = Boolean.TRUE.equals(repository.enabled(tenant, null, key));
            if (Boolean.TRUE.equals(request.enabled()) && (!c.enabled() || (project != null && !grantedToTenant)))
                throw ApiException.bad("The connector is not available to this project.");
            repository.enable(tenant, project, key, reset ? null : request.enabled());
            audit.log(scope, Auth.userId(), "configuration.connector.update", "connector", null, key);
            return null;
        });
        driverCache.clear();
    }

    /** Whether a project may use a connector driver right now (MUX, VAST, R2). Called on hot paths, so cached briefly. */
    public boolean driverEnabled(UUID project, String driver) {
        String cacheKey = project + ":" + driver;
        long now = System.currentTimeMillis();
        CachedFlag hit = driverCache.get(cacheKey);
        if (hit != null && now - hit.at() < DRIVER_CACHE_MS) return hit.value();
        boolean value = TenantScope.bypass(() -> repository.driverEnabled(project, driver));
        driverCache.put(cacheKey, new CachedFlag(value, now));
        return value;
    }

    /** Whether a project may use one connector, by its key (e.g. "mux"; a media connection's provider key). Cached like drivers. */
    public boolean connectorEnabled(UUID project, String key) {
        String cacheKey = project + ":key:" + key;
        long now = System.currentTimeMillis();
        CachedFlag hit = driverCache.get(cacheKey);
        if (hit != null && now - hit.at() < DRIVER_CACHE_MS) return hit.value();
        boolean value = TenantScope.bypass(() -> repository.connectorEnabled(project, key));
        driverCache.put(cacheKey, new CachedFlag(value, now));
        return value;
    }

    // ------------------------------------------------------------------ plan features and limits

    /**
     * The features on for a project: the switched-on keys the platform may grant it (built-in ones only when the
     * tenant's plan includes them; enabled global custom ones always), plus the project's own enabled custom features.
     */
    public List<String> effectiveFeatures(UUID project, String requested) {
        List<String> on = new ArrayList<>(grantableFeatures(project, requested));
        TenantScope.as(project, () -> capabilities.enabledCustom("FEATURE", project, false)).stream().filter(f -> !on.contains(f)).forEach(on::add);
        return on;
    }

    /** The project's switched-on feature keys as stored (organization.features). */
    public String projectFeatures(UUID project) {
        return TenantScope.bypass(() -> repository.projectFeatures(project));
    }

    /** Requested keys the platform may switch on for a project: built-in ones in the tenant's plan, or enabled global custom ones. */
    public List<String> grantableFeatures(UUID project, String requested) {
        Plan p = plan(repository.account(repository.tenant(project)).planKey());
        List<String> global = TenantScope.bypass(() -> capabilities.enabledCustom("FEATURE", null, true));
        return Times.codes(requested).stream().filter(f -> p.features().contains(f) || global.contains(f)).toList();
    }

    /** Like {@link #effectiveFeatures} but rejects keys outside the plan instead of silently dropping them. */
    public List<String> requirePlanFeatures(UUID project, List<String> requested) {
        List<String> allowed = grantableFeatures(project, String.join(",", requested));
        List<String> rejected = requested.stream().filter(f -> !allowed.contains(f)).distinct().toList();
        if (!rejected.isEmpty())
            throw ApiException.bad("Not included in this tenant's plan: " + String.join(", ", rejected) + ". Change the plan first.");
        return allowed;
    }

    /**
     * Checks the tenant may add one more channel. The plan's channel allowance is shared by all projects of the tenant.
     * Must run inside the caller's transaction: the tenant row lock is held until the channel insert commits.
     */
    @Transactional
    public void reserveChannel(UUID project) {
        TenantScope.bypass(() -> {
            UUID tenant = repository.tenant(project);
            repository.lockTenant(tenant);
            Account t = repository.account(tenant);
            Plan p = plan(t.planKey());
            if (!t.status().equals("ACTIVE") || Set.of("PAUSED", "CANCELLED").contains(t.subscriptionStatus()))
                throw ApiException.conflict("SUBSCRIPTION_INACTIVE", "The tenant subscription does not allow new channels.");
            if (t.channels() >= p.maxChannels())
                throw ApiException.conflict("PLAN_LIMIT", "Your plan allows " + p.maxChannels()
                        + " channels across all projects. Archive one or ask the platform admin to change the plan.");
            return null;
        });
    }

    // ------------------------------------------------------------------ property definitions

    public List<PropertyView> definitions() { return repository.definitions().stream().map(this::view).toList(); }

    private PropertyView view(Definition d) {
        List<String> options = new ArrayList<>();
        json.readTree(d.options()).forEach(n -> options.add(n.asString()));
        JsonNode defaultValue = d.secret() || d.defaultValue() == null ? null : json.readTree(d.defaultValue());
        return new PropertyView(d.key(), d.label(), d.valueType(), d.connectorKey(), d.secret(), d.projectOverride(), d.enabled(),
                options, defaultValue, d.defaultValue() != null, d.revision(), SYSTEM.containsKey(d.key()));
    }

    /** Creates or updates a global property definition and its optional global default. */
    @Transactional
    public void saveProperty(Property p) {
        requirePlatform();
        if (p.secret() && !p.valueType().equals("STRING")) throw ApiException.bad("Secret properties must use STRING values.");
        Definition old = repository.definitions().stream().filter(d -> d.key().equals(p.key())).findFirst().orElse(null);
        if (old != null && (!old.valueType().equals(p.valueType()) || old.secret() != p.secret()
                || !Objects.equals(old.connectorKey(), blank(p.connectorKey()))))
            throw ApiException.bad("Type, secrecy and connector binding are immutable. Create a new property key.");
        if (SYSTEM.containsKey(p.key()) && (!p.enabled() || !p.projectOverride()))
            throw ApiException.bad("Built-in properties stay enabled and overridable per project; the product reads them.");
        if (blank(p.connectorKey()) != null && connectors().stream().noneMatch(c -> c.key().equals(p.connectorKey())))
            throw ApiException.bad("Unknown connector.");
        if (p.valueType().equals("SELECT") && (p.options().isEmpty() || new HashSet<>(p.options()).size() != p.options().size()))
            throw ApiException.bad("Select options must be nonempty and unique.");

        Definition candidate = new Definition(p.key(), p.label(), p.valueType(), blank(p.connectorKey()), p.secret(), p.projectOverride(),
                p.enabled(), json.writeValueAsString(p.options()), null, 0);
        boolean noDefault = p.defaultValue() == null || p.defaultValue().isNull()
                || (p.secret() && p.defaultValue().isString() && p.defaultValue().asString().isEmpty());
        String value = noDefault ? null : encode(candidate, p.defaultValue());

        // Changing SELECT options must not invalidate values tenants/projects already stored.
        if (old != null)
            for (String stored : repository.allValues(p.key()))
                validate(candidate, json.readTree(old.secret() ? secrets.open(stored) : stored));

        if (old == null) {
            repository.insertDefinition(candidate, value);
        } else {
            // A blank secret means "keep the stored default"; clearing uses the explicit reset endpoint.
            if (p.secret() && value == null) value = old.defaultValue();
            if (!repository.updateDefinition(candidate, value, p.expectedRevision() == null ? -1 : p.expectedRevision())) throw ApiException.conflict("STALE_CONFIGURATION", "Reload the property before saving.");
        }
        audit.platform(Auth.userId(), "property.definition.save", "property", null, p.key());
    }

    @Transactional
    public void clearDefault(String key) {
        requirePlatform();
        repository.definition(key);
        if (SYSTEM.containsKey(key)) throw ApiException.bad("Built-in properties need a global default.");
        repository.clearDefault(key);
        audit.platform(Auth.userId(), "property.default.clear", "property", null, key);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    /** Type check plus any built-in domain rule; throws 400 with a readable message. */
    private void validate(Definition d, JsonNode value) {
        boolean valid = value != null && !value.isNull() && switch (d.valueType()) {
            case "BOOLEAN" -> value.isBoolean();
            case "NUMBER" -> value.isNumber() && Double.isFinite(value.asDouble());
            case "SELECT" -> value.isString() && json.readTree(d.options()).valueStream().anyMatch(n -> n.asString().equals(value.asString()));
            default -> value.isString() && value.asString().length() <= 4000;
        };
        if (!valid) throw ApiException.bad("Invalid value for property " + d.key() + "; expected " + d.valueType() + ".");
        Consumer<JsonNode> rule = SYSTEM.get(d.key());
        if (rule != null) rule.accept(value);
    }

    /** Validates and serializes a value as JSON text; secrets are sealed before storage. */
    private String encode(Definition d, JsonNode value) {
        validate(d, value);
        String encoded = json.writeValueAsString(value);
        return d.secret() ? secrets.seal(encoded) : encoded;
    }

    // ------------------------------------------------------------------ resolved configuration

    /** The resolved configuration for a tenant (project == null) or one of its projects, as shown on settings screens. */
    public Settings settings(UUID tenant, UUID project) {
        UUID scope = access(tenant, project, false);
        boolean platform = admin();
        return TenantScope.bypass(() -> {
            Account account = repository.account(tenant);
            // Non-admins only see the projects they are a member of.
            List<Project> projects = repository.projects(tenant).stream()
                    .filter(p -> platform || isMember(p.id(), List.of())).toList();
            boolean editable = platform || (project == null ? isTenantOwner(tenant) : isMember(scope, List.of("OWNER", "ADMIN")));

            List<EffectiveProperty> properties = new ArrayList<>();
            for (Definition d : repository.definitions()) {
                if (!d.enabled() && !platform) continue;
                String tenantValue = repository.value(tenant, null, d.key());
                String projectValue = project == null || !d.projectOverride() ? null : repository.value(tenant, project, d.key());
                String value = projectValue != null ? projectValue : tenantValue != null ? tenantValue : d.defaultValue();
                String source = projectValue != null ? "PROJECT" : tenantValue != null ? "TENANT" : value != null ? "GLOBAL" : "UNSET";
                boolean overridden = project == null ? tenantValue != null : projectValue != null;
                properties.add(new EffectiveProperty(view(d), d.secret() || value == null ? null : json.readTree(value),
                        value != null, source, overridden));
            }

            List<ConnectorState> connectors = new ArrayList<>();
            for (Connector c : connectors()) {
                boolean allowed = c.enabled() && Boolean.TRUE.equals(repository.enabled(tenant, null, c.key()));
                Boolean override = project == null ? null : repository.enabled(tenant, project, c.key());
                connectors.add(new ConnectorState(c, allowed, allowed && !Boolean.FALSE.equals(override), override != null));
            }

            Project current = project == null ? null : projects.stream().filter(p -> p.id().equals(project)).findFirst().orElseThrow();
            return new Settings(account, current, plan(account.planKey()), projects, properties, connectors, editable);
        });
    }

    private boolean isMember(UUID project, List<String> roles) {
        return repository.isMember(project, Auth.userId(), roles);
    }

    private boolean isTenantOwner(UUID tenant) {
        return repository.isTenantOwner(tenant, Auth.userId());
    }

    /** Sets (or with reset, removes) a property value in the tenant scope (project == null) or a project scope. */
    @Transactional
    public void setValue(UUID tenant, UUID project, String key, Value request) {
        UUID scope = access(tenant, project, true);
        boolean platform = admin();
        TenantScope.bypass(() -> {
            repository.account(tenant);
            Definition d = repository.definition(key);
            if (!d.enabled()) throw ApiException.bad("This property is disabled.");
            if (project != null && !d.projectOverride()) throw ApiException.bad("This property does not allow project overrides.");
            if (d.secret() && !platform)
                throw new ApiException(HttpStatus.FORBIDDEN, "SECRET_ADMIN_ONLY", "Only the platform administrator may provision connector secrets.");
            repository.value(tenant, project, key, Boolean.TRUE.equals(request.reset()) ? null : encode(d, request.value()));
            audit.log(scope, Auth.userId(), "configuration.property.update", "property", null, key);
            return null;
        });
    }

    /**
     * The value product code should use for a project: project override → tenant value → global default.
     * Returns null when nothing is set. Secret values are opened for server-side use only; never return them to clients.
     */
    public JsonNode effective(UUID project, String key) {
        return TenantScope.bypass(() -> {
            UUID tenant = repository.tenant(project);
            Definition d = repository.definition(key);
            String value = d.projectOverride() ? repository.value(tenant, project, key) : null;
            if (value == null) value = repository.value(tenant, null, key);
            if (value == null) value = d.defaultValue();
            if (value == null) return null;
            return json.readTree(d.secret() ? secrets.open(value) : value);
        });
    }

    /**
     * Stores a project override written by a domain screen (e.g. programming settings), but only when it differs from
     * what the project already inherits, so unchanged fields keep following tenant/global defaults.
     * The caller has already checked the project permission.
     */
    public void writeProjectValueIfChanged(UUID project, String key, JsonNode value) {
        if (value.equals(effective(project, key))) return;
        TenantScope.bypass(() -> {
            UUID tenant = repository.tenant(project);
            repository.value(tenant, project, key, encode(repository.definition(key), value));
            return null;
        });
    }
}
