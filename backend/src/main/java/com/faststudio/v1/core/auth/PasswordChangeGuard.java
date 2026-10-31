package com.faststudio.v1.core.auth;

import com.faststudio.v1.core.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Server-side enforcement of "change your temporary password first": until the user has set their own password,
 * every API call except sign-in/out, /me and the password change itself is refused. The UI shows the change form,
 * but the API does not rely on it.
 */
@Component
public class PasswordChangeGuard implements HandlerInterceptor {
    private final JdbcClient db;

    public PasswordChangeGuard(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        boolean must = db.sql("select must_change_password from app_user where id = :id").param("id", Auth.userId())
                .query(Boolean.class).optional().orElse(false);
        if (must) throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                "Please choose your own password before continuing.");
        return true;
    }
}
