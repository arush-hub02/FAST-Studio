package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.AccountRequest;
import com.faststudio.v1.dto.media.LibraryDto.AccountView;
import com.faststudio.v1.repositories.media.MediaConnectionRepository;
import com.faststudio.v1.repositories.media.MediaSettingsRepository;
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
import java.util.Objects;
import java.util.UUID;

/**
 * A project's connection to its video platform (media_connection; Mux today): credentials encrypted at rest and set only
 * by platform administrators, a health check, and the "is it usable" check every library call goes through.
 * Credentials never leave this service in an API answer.
 */
@Service
public class MediaConnectionService {
    private static final Logger log = LoggerFactory.getLogger(MediaConnectionService.class);
    public static final String NOT_CONNECTED = "The video library isn't connected for this organization yet. Ask your platform administrator.";

    private final MediaConnectionRepository connections;
    private final MediaSettingsRepository projects;
    private final TenantConfigurationServiceV1 configuration;
    private final MediaProviderResolverService providers;
    private final Secrets secrets;
    private final Audit audit;
    private final String appUrl;

    public MediaConnectionService(MediaConnectionRepository connections, MediaSettingsRepository projects, TenantConfigurationServiceV1 configuration,
                                  MediaProviderResolverService providers, Secrets secrets, Audit audit, @Value("${faststudio.app-url}") String appUrl) {
        this.connections = connections;
        this.projects = projects;
        this.configuration = configuration;
        this.providers = providers;
        this.secrets = secrets;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** A usable connection: which provider, and its decrypted credentials. */
    public record Connection(String provider, Credentials credentials, OffsetDateTime checkedAt, String checkError) {}

    /** The project's connection, decrypted, or null when none is set up. */
    public Connection connection(UUID org) {
        return TenantScope.as(org, () -> connections.find(org)
                .map(r -> new Connection(r.provider(), new Credentials(secrets.open(r.tokenId()), secrets.open(r.tokenSecret()), r.passthroughTag()),
                        r.checkedAt(), r.checkError()))
                .orElse(null));
    }

    /** The project's Mux account (webhooks, packaging from Mux), or null when it has none. */
    public Account account(UUID org) {
        return TenantScope.as(org, () -> connections.find(org).filter(r -> r.provider().equals(MuxProvider.KEY))
                .map(r -> new Account(secrets.open(r.tokenId()), secrets.open(r.tokenSecret()), secrets.open(r.webhookSecret()), r.environment(), r.passthroughTag()))
                .orElse(null));
    }

    /** The project's connection, if its connector is enabled for the project and credentials are configured. */
    public Connection connected(UUID org) {
        Connection c = connection(org);
        if (c == null) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", NOT_CONNECTED);
        if (!configuration.connectorEnabled(org, c.provider()))
            throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_DISABLED", "The video library connector is disabled for this project.");
        return c;
    }

    // ------------------------------------------------------------------ platform administration

    public AccountView view(UUID org) {
        requireCustomer(org);
        Connection c = connection(org);
        Account a = account(org);
        String hook = appUrl + "/hooks/mux/" + org;
        if (c == null) return new AccountView(false, null, null, null, false, hook, null, null, null);
        return new AccountView(true, null, a == null ? "" : a.environment(), c.credentials().scope(), a != null && a.webhookSecret() != null, hook,
                c.checkedAt(), c.checkError(), c.checkedAt() == null ? null : c.checkError() == null);
    }

    /** Stores the account encrypted; a blank token id or secret keeps the stored one. */
    public AccountView save(UUID org, AccountRequest r) {
        requireCustomer(org);
        String token = blankToNull(r.tokenId()), secret = blankToNull(r.tokenSecret());
        String env = Objects.toString(r.environment(), "").trim(), tag = Objects.toString(r.passthroughTag(), "").trim();
        if ((token == null || secret == null) && connection(org) == null)
            throw ApiException.bad("The access token ID and secret are required to connect a library.");
        TenantScope.as(org, () -> { connections.save(org, MuxProvider.KEY, secrets.seal(token), secrets.seal(secret), secrets.seal(blankToNull(r.webhookSecret())), env, tag); return null; });
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
        TenantScope.as(org, () -> { connections.checked(org, result); return null; });
        audit.platform(Auth.userId(), "platform.mux.check", "organization", org, error == null ? "connection works" : error);
        return view(org);
    }

    /** Library accounts belong to customer organizations only (not partners or the platform). */
    public void requireCustomer(UUID org) {
        if (!projects.isCustomerOrganization(org)) throw ApiException.notFound("Organization");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
