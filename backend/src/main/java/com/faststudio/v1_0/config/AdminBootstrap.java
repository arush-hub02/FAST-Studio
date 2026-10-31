package com.faststudio.v1_0.config;

import com.faststudio.v1_0.core.auth.Accounts;
import com.faststudio.v1_0.core.common.Audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * First start with real data: if no platform administrator exists, create one from
 * FASTSTUDIO_ADMIN_EMAIL / FASTSTUDIO_ADMIN_PASSWORD (faststudio.admin.*). The password must be changed at first sign-in.
 * Runs only while no administrator exists, so later starts never touch existing accounts. The password is never logged.
 */
@Component
public class AdminBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final JdbcClient db;
    private final Accounts accounts;
    private final Audit audit;
    private final String email, password;
    private final boolean demo;

    public AdminBootstrap(JdbcClient db, Accounts accounts, Audit audit, @Value("${faststudio.admin.email:}") String email,
                          @Value("${faststudio.admin.password:}") String password, @Value("${faststudio.demo}") boolean demo) {
        this.db = db;
        this.accounts = accounts;
        this.audit = audit;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void run() {
        if (demo) return;   // demo mode seeds its own fictional platform admin
        boolean exists = db.sql("select count(*) from app_user where platform_admin").query(Integer.class).single() > 0;
        if (exists) return;
        if (email.isBlank() || password.isBlank()) {
            log.warn("No platform administrator exists yet. Set FASTSTUDIO_ADMIN_EMAIL and FASTSTUDIO_ADMIN_PASSWORD and restart "
                    + "(start.bat -Pilot does this for you).");
            return;
        }
        if (!email.matches("[^@\\s]+@[^@\\s]+")) { log.error("FASTSTUDIO_ADMIN_EMAIL is not a valid email address; no administrator created."); return; }
        String weak = Accounts.weakness(password, email);
        if (weak != null) { log.error("FASTSTUDIO_ADMIN_PASSWORD rejected: {} No administrator created.", weak); return; }
        UUID id = db.sql("""
                insert into app_user(email, name, password_hash, platform_admin, must_change_password)
                values (:e, 'Platform administrator', :p, true, true)
                on conflict (email) do update set platform_admin = true, token_version = app_user.token_version + 1 returning id""")
                .param("e", email).param("p", accounts.hash(password)).query(UUID.class).single();
        audit.platform(id, "platform.admin_bootstrap", "user", id, email);
        log.info("Created the first platform administrator: {} (password must be changed at first sign-in).", email);
    }
}
