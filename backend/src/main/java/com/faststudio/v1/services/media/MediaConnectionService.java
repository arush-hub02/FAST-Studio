package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.AccountRequest;
import com.faststudio.v1.dto.media.LibraryDto.AccountView;
import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.InstanceRequest;
import com.faststudio.v1.repositories.media.MediaConnectionRepository;
import com.faststudio.v1.repositories.media.MediaSettingsRepository;
import com.faststudio.v1.services.connectors.ConnectorCatalogueServiceV1;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.mediaProvider.IMediaProvider.Credentials;
import com.faststudio.v1.util.mediaProvider.MediaProviderResolverService;
import com.faststudio.v1.util.mux.MuxClientService.Account;
import com.faststudio.v1.util.mux.MuxProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A project's connection to its video platform (Mux today). Since V27 the credentials and health check are the project's
 * connector connection (connector marketplace, values by field key: token_id, token_secret, webhook_secret, environment,
 * passthrough_tag); media_connection keeps the provider and the library-sync checkpoint. Set by platform administrators,
 * here or on the tenant page. Credentials never leave this service in an API answer.
 */
@Service
public class MediaConnectionService {
    private static final Logger log = LoggerFactory.getLogger(MediaConnectionService.class);
    public static final String NOT_CONNECTED = "The video library isn't connected for this organization yet. Ask your platform administrator.";

    private final MediaConnectionRepository connections;
    private final MediaSettingsRepository projects;
    private final TenantConfigurationServiceV1 configuration;
    private final MediaProviderResolverService providers;
    private final ConnectorCatalogueServiceV1 connectors;
    private final Audit audit;
    private final String appUrl;

    public MediaConnectionService(MediaConnectionRepository connections, MediaSettingsRepository projects, TenantConfigurationServiceV1 configuration,
                                  MediaProviderResolverService providers, ConnectorCatalogueServiceV1 connectors, Audit audit, @Value("${faststudio.app-url}") String appUrl) {
        this.connections = connections;
        this.projects = projects;
        this.configuration = configuration;
        this.providers = providers;
        this.connectors = connectors;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** A usable connection: which provider, its decrypted credentials, its health check and the connector connection's id. */
    public record Connection(String provider, Credentials credentials, OffsetDateTime checkedAt, String checkError, UUID id, String name) {}

    /** One connector connection of the project's library (a project may have several, e.g. two Mux accounts). */
    private record Found(String provider, ConnectorCatalogueServiceV1.Connection c) {}

    private List<Found> find(UUID org) {
        List<Found> out = new ArrayList<>();
        for (String key : providers.keys())
            for (var c : connectors.connections(org, key))
                if (c.values().get("token_id") != null && c.values().get("token_secret") != null) {
                    TenantScope.as(org, () -> { connections.ensure(org, key, c.id()); return null; });   // sync-state row for this connection
                    out.add(new Found(key, c));
                }
        return out;
    }

    private static Connection of(Found f) {
        Map<String, String> v = f.c().values();
        return new Connection(f.provider(), new Credentials(v.get("token_id"), v.get("token_secret"), Objects.toString(v.get("passthrough_tag"), ""),
                v.get("signing_key_id"), v.get("signing_private_key")), f.c().checkedAt(), f.c().checkError(), f.c().id(), f.c().name());
    }

    /** Every library connection of the project, decrypted, in list order (empty when none is set up). */
    public List<Connection> connections(UUID org) { return find(org).stream().map(MediaConnectionService::of).toList(); }

    /** The project's first connection, decrypted, or null when none is set up (platform administration's single view). */
    public Connection connection(UUID org) { return connections(org).stream().findFirst().orElse(null); }

    /** One connection of the project by its id, or null when the project has no such connection. */
    public Connection connection(UUID org, UUID id) {
        if (id == null) return null;
        return connections(org).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    /** The Mux accounts of the project's connections (webhooks verify against each one's secret). */
    public List<Map.Entry<UUID, Account>> accounts(UUID org) {
        return find(org).stream().filter(f -> f.provider().equals(MuxProvider.KEY)).map(f -> {
            Map<String, String> v = f.c().values();
            return Map.entry(f.c().id(), new Account(v.get("token_id"), v.get("token_secret"), v.get("webhook_secret"),
                    Objects.toString(v.get("environment"), ""), Objects.toString(v.get("passthrough_tag"), "")));
        }).toList();
    }

    /** The project's first Mux account, or null (platform administration's single view). */
    public Account account(UUID org) { return accounts(org).stream().findFirst().map(Map.Entry::getValue).orElse(null); }

    /** The connections the project may use now: credentials set and the connector enabled for the project. */
    public List<Connection> usable(UUID org) {
        return connections(org).stream().filter(c -> configuration.connectorEnabled(org, c.provider())).toList();
    }

    /** One usable connection (id null: the only one, or the first). Fails clearly when none or that one isn't usable. */
    public Connection connected(UUID org, UUID id) {
        List<Connection> all = connections(org);
        if (all.isEmpty()) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", NOT_CONNECTED);
        Connection c = id == null ? all.getFirst() : all.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Library connection"));
        if (!configuration.connectorEnabled(org, c.provider()))
            throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_DISABLED", "The video library connector is disabled for this project.");
        return c;
    }

    public Connection connected(UUID org) { return connected(org, null); }

    // ------------------------------------------------------------------ platform administration

    public AccountView view(UUID org) {
        requireCustomer(org);
        Connection c = connection(org);
        Account a = account(org);
        String hook = appUrl + "/hooks/mux/" + org;
        if (c == null) return new AccountView(false, null, null, null, false, hook, null, null, null, null, false);
        return new AccountView(true, null, a == null ? "" : a.environment(), c.credentials().scope(), a != null && a.webhookSecret() != null, hook,
                c.checkedAt(), c.checkError(), c.checkedAt() == null ? null : c.checkError() == null,
                Objects.toString(c.credentials().signingKeyId(), ""), c.credentials().signingKey() != null && !c.credentials().signingKey().isBlank());
    }

    /** Stores the account encrypted; a blank token id or secret keeps the stored one. */
    public AccountView save(UUID org, AccountRequest r) {
        requireCustomer(org);
        String token = blankToNull(r.tokenId()), secret = blankToNull(r.tokenSecret());
        String env = Objects.toString(r.environment(), "").trim(), tag = Objects.toString(r.passthroughTag(), "").trim();
        if ((token == null || secret == null) && connection(org) == null)
            throw ApiException.bad("The access token ID and secret are required to connect a library.");
        Map<String, String> values = new LinkedHashMap<>();   // blank secrets keep the stored ones (connector rule)
        values.put("token_id", Objects.toString(token, ""));
        values.put("token_secret", Objects.toString(secret, ""));
        values.put("webhook_secret", Objects.toString(blankToNull(r.webhookSecret()), ""));
        values.put("environment", env);
        values.put("passthrough_tag", tag);
        values.put("signing_key_id", Objects.toString(r.signingKeyId(), "").trim());   // signed playback (optional)
        values.put("signing_private_key", Objects.toString(blankToNull(r.signingPrivateKey()), ""));
        // this page edits the project's first Mux connection; more are added in the connector marketplace
        UUID first = connectors.connections(org, MuxProvider.KEY).stream().findFirst().map(ConnectorCatalogueServiceV1.Connection::id).orElse(null);
        connectors.saveInstance(null, org, MuxProvider.KEY, first, new InstanceRequest(null, null, values));
        audit.platform(Auth.userId(), "platform.mux.update", "organization", org, "environment '" + env + "', passthrough tag '" + tag + "'");
        return view(org);
    }

    /** Tests the stored credentials against the platform and records the result (saved credentials alone prove nothing). */
    public AccountView check(UUID org) throws InterruptedException {
        requireCustomer(org);
        Connection c = connection(org);
        if (c == null) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", NOT_CONNECTED);
        String error = null;
        try {
            providers.get(c.provider()).check(c.credentials());
        } catch (ApiException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.warn("Connection check for {} failed", org, e);
            error = "The video library couldn't be checked. Try again later.";
        }
        String result = error;
        connectors.checked(org, c.id(), result);
        audit.platform(Auth.userId(), "platform.mux.check", "organization", org, error == null ? "connection works" : error);
        return view(org);
    }

    /** Library accounts belong to customer organizations only (not partners or the platform). */
    public void requireCustomer(UUID org) {
        if (!projects.isCustomerOrganization(org)) throw ApiException.notFound("Organization");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
