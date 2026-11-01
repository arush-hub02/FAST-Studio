package com.faststudio.v1.config;

import com.faststudio.v1.core.common.Secrets;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Keeps the two database logins the app works as (after every Flyway run, with the owner connection):
 * faststudio_app_login → member of faststudio_app (requests, row-level security per organization) and
 * faststudio_system_login → member of faststudio_system (cross-tenant system work). Passwords are derived from the
 * secrets key, so every server sharing that key logs in with the same ones and nothing extra has to be configured.
 */
public class DatabaseLogins implements Callback {
    public static final String APP = "faststudio_app_login", SYSTEM = "faststudio_system_login";

    private final Secrets secrets;

    public DatabaseLogins(Secrets secrets) { this.secrets = secrets; }

    static String password(Secrets secrets, String login) { return secrets.derive("database-login:" + login); }

    /** The same database, logged in as one of the two roles. */
    public static DatabaseConfig.Target login(DatabaseConfig.Target owner, String login, Secrets secrets) {
        // drop credentials carried in the URL (the embedded database adds ?user=...), which would override the login
        String url = owner.url().replaceAll("([?&])(user|password)=[^&]*&?", "$1").replaceAll("[?&]$", "");
        return new DatabaseConfig.Target(url, login, password(secrets, login));
    }

    @Override
    public boolean supports(Event event, Context context) { return event == Event.AFTER_MIGRATE; }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) { return true; }

    @Override
    public void handle(Event event, Context context) {
        try (Statement s = context.getConnection().createStatement()) {
            ensure(s, APP, "faststudio_app");
            ensure(s, SYSTEM, "faststudio_system");
            s.execute("revoke faststudio_system from " + APP);   // never, even if someone granted it by hand
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't set up the database logins " + APP + "/" + SYSTEM
                    + ". The configured database user must be allowed to create roles (CREATEROLE).", e);
        }
    }

    private void ensure(Statement s, String login, String member) throws SQLException {
        // names are constants and the password is base64url, so building the statements is safe
        s.execute("do $$ begin if not exists (select from pg_roles where rolname = '" + login + "') then create role " + login + "; end if; end $$");
        s.execute("alter role " + login + " login nosuperuser nocreatedb nocreaterole nobypassrls inherit password '" + password(secrets, login) + "'");
        s.execute("grant " + member + " to " + login);
    }

    @Override
    public String getCallbackName() { return "faststudio-database-logins"; }
}
