package com.faststudio.v1.config;

import com.faststudio.v1.core.auth.TokenKeys;
import com.faststudio.v1.core.auth.Tokens;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.UUID;

/**
 * Sign-in with signed tokens (see {@link Tokens}): HttpOnly cookies for the browser, "Authorization: Bearer" for scripts.
 * Browser calls that change something also need the SPA CSRF token (XSRF-TOKEN cookie + X-XSRF-TOKEN header); calls
 * carrying no cookies at all (Bearer scripts, token requests with the credentials in the body) can't be forged
 * cross-site, so they skip it. faststudio.auth.mode=session keeps the previous server-session sign-in for one release.
 * Passwords are bcrypt hashes; production adds the organisation's single sign-on (OIDC) in front.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, Tokens tokens) throws Exception {
        var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http.authorizeHttpRequests(a -> a
                        .requestMatchers(Tokens.COOKIE_FREE.toArray(String[]::new)).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())          // SPA assets, public playback (/play/**), media, /.well-known/jwks.json
                .csrf(c -> c.spa().ignoringRequestMatchers("/play/**", "/oauth/token", "/hooks/**").ignoringRequestMatchers(req ->
                        Tokens.cookie(req, Tokens.ACCESS_COOKIE) == null && Tokens.cookie(req, Tokens.REFRESH_COOKIE) == null
                                && Tokens.cookie(req, "JSESSIONID") == null
                                && (req.getHeader("Authorization") != null || req.getRequestURI().startsWith("/api/auth/token") || req.getRequestURI().equals("/api/auth/logout"))))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable());
        if (tokens.enabled())
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .logout(l -> l.disable())                 // AuthControllerV1 closes the sign-in and clears the cookies
                    .oauth2ResourceServer(o -> o.bearerTokenResolver(tokens::resolve).jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized));
        else
            http.logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q, r, a) -> r.setStatus(204)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenKeys keys, Tokens tokens) {
        NimbusJwtDecoder d = keys.decoder();
        d.setJwtValidator(tokens.validator());
        return d;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    record Cred(UUID id, String passwordHash) {}

    @Bean
    UserDetailsService users(JdbcClient db) {
        return email -> db.sql("select id, password_hash from app_user where lower(email) = lower(:e)")
                .param("e", email).query(Cred.class).optional()
                .map(c -> User.withUsername(c.id().toString()).password(c.passwordHash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("unknown"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(users);
        p.setPasswordEncoder(encoder);
        return new ProviderManager(p);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
