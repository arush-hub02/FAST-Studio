package com.faststudio.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Every request starts with no tenant and ends with the scope cleared (pooled threads must not inherit it). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantScopeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        TenantScope.clear();
        org.slf4j.MDC.remove("orgId");
        try {
            chain.doFilter(req, res);
        } finally {
            TenantScope.clear();
            org.slf4j.MDC.remove("orgId");
        }
    }
}
