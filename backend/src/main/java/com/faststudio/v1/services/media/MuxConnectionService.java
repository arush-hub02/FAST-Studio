package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.AccountRequest;
import com.faststudio.v1.dto.media.LibraryDto.AccountView;
import com.faststudio.v1.repositories.media.MediaSettingsRepository;
import com.faststudio.v1.repositories.media.MuxAccountRepository;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.mux.MuxClientService.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * A project's connection to its video library (Mux): credentials encrypted at rest, set only by platform administrators,
 * and the "is it usable" check every library call goes through. Credentials never leave this service in an API answer.
 */
@Service
public class MuxConnectionService {
    public static final String NOT_CONNECTED = "The video library isn't connected for this organization yet. Ask your platform administrator.";

    private final MuxAccountRepository accounts;
    private final MediaSettingsRepository projects;
    private final TenantConfigurationServiceV1 configuration;
    private final Secrets secrets;
    private final Audit audit;
    private final String appUrl;

    public MuxConnectionService(MuxAccountRepository accounts, MediaSettingsRepository projects, TenantConfigurationServiceV1 configuration,
                                  Secrets secrets, Audit audit, @Value("${faststudio.app-url}") String appUrl) {
        this.accounts = accounts;
        this.projects = projects;
        this.configuration = configuration;
        this.secrets = secrets;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** The project's account, decrypted, or null when none is set up. */
    public Account account(UUID org) {
        return TenantScope.as(org, () -> accounts.find(org)
                .map(r -> new Account(secrets.open(r.tokenId()), secrets.open(r.tokenSecret()), secrets.open(r.webhookSecret()), r.environment(), r.passthroughTag()))
                .orElse(null));
    }

    /** The project's account, if the connector is enabled for the project and an account is configured. */
    public Account connected(UUID org) {
        if (!configuration.driverEnabled(org, "MUX"))
            throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_DISABLED", "The video library connector is disabled for this project.");
        Account a = account(org);
        if (a == null) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", NOT_CONNECTED);
        return a;
    }

    // ------------------------------------------------------------------ platform administration

    public AccountView view(UUID org) {
        requireCustomer(org);
        Account a = account(org);
        String hook = appUrl + "/hooks/mux/" + org;
        return a == null ? new AccountView(false, null, null, null, false, hook)
                : new AccountView(true, null, a.environment(), a.tag(), a.webhookSecret() != null, hook);
    }

    /** Stores the account encrypted; a blank token id or secret keeps the stored one. */
    public AccountView save(UUID org, AccountRequest r) {
        requireCustomer(org);
        String token = blankToNull(r.tokenId()), secret = blankToNull(r.tokenSecret());
        String env = Objects.toString(r.environment(), "").trim(), tag = Objects.toString(r.passthroughTag(), "").trim();
        if ((token == null || secret == null) && account(org) == null)
            throw ApiException.bad("The access token ID and secret are required to connect a library.");
        accounts.save(org, secrets.seal(token), secrets.seal(secret), secrets.seal(blankToNull(r.webhookSecret())), env, tag);
        audit.platform(Auth.userId(), "platform.mux.update", "organization", org, "environment '" + env + "', passthrough tag '" + tag + "'");
        return view(org);
    }

    /** Library accounts belong to customer organizations only (not partners or the platform). */
    public void requireCustomer(UUID org) {
        if (!projects.isCustomerOrganization(org)) throw ApiException.notFound("Organization");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
