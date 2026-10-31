package com.faststudio.v1.config;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.PasswordChangeGuard;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantInterceptor;
import com.faststudio.v1.core.tenant.TenantScope;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final TenantInterceptor tenantInterceptor;
    private final PasswordChangeGuard passwordGuard;
    private final JdbcClient db;
    private final Audit audit;
    private final PartnerInterceptor partnerInterceptor;

    public WebConfig(TenantInterceptor tenantInterceptor, PasswordChangeGuard passwordGuard, JdbcClient db, Audit audit,
                     PartnerInterceptor partnerInterceptor) {
        this.partnerInterceptor = partnerInterceptor;
        this.tenantInterceptor = tenantInterceptor;
        this.passwordGuard = passwordGuard;
        this.db = db;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // partner machine tokens (API clients) only ever reach the partner API, never people's pages
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (Tokens.isService()) throw new ApiException(HttpStatus.FORBIDDEN, "SERVICE_TOKEN", "API client tokens can only be used for /api/partners/… .");
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/partners/**");
        registry.addInterceptor(passwordGuard).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
        registry.addInterceptor(tenantInterceptor).addPathPatterns("/api/orgs/**");
        registry.addInterceptor(partnerInterceptor).addPathPatterns("/api/partners/**");
        // platform administration reads across tenants: verified admins only, then row-level security bypass
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                boolean admin = db.sql("select platform_admin from app_user where id = :id").param("id", Auth.userId())
                        .query(Boolean.class).optional().orElse(false);
                if (!admin) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
                TenantScope.setBypass(true);
                org.slf4j.MDC.put("orgId", audit.platformOrg().toString());
                return true;
            }
        }).addPathPatterns("/api/platform/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) {
                return p.getParameterType() == Tenant.class;
            }

            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory f) {
                Object t = r.getNativeRequest(HttpServletRequest.class).getAttribute(TenantInterceptor.ATTR);
                if (t == null) throw new IllegalStateException("Tenant route without {org} path variable");
                return t;
            }
        });
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /media/** is served only through signed, expiring URLs (playout.MediaFileControllerV1)
        // Built frontend (npm run build copies to static/); unknown routes fall back to index.html.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource r = location.createRelative(path);
                        if (r.exists() && r.isReadable()) return r;
                        if (path.startsWith("api/") || path.startsWith("play/") || path.startsWith("media/")) return null;
                        Resource index = location.createRelative("index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
