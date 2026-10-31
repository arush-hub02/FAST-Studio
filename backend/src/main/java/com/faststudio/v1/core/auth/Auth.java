package com.faststudio.v1.core.auth;

import com.faststudio.v1.core.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class Auth {
    private Auth() {}

    /** Current user id; the security principal's username is the user UUID. */
    public static UUID userId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in");
        return UUID.fromString(a.getName());
    }
}
