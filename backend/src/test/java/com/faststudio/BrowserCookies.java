package com.faststudio;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Tests act like a browser: the MockHttpSession a test passes with .session(...) doubles as that browser's cookie
 * jar, so the sign-in cookies (fs_at, fs_rt) set by one answer are sent with the next request, and cleared ones go.
 */
@Configuration
class BrowserCookies {
    static final String JAR = "test.cookie-jar";

    @SuppressWarnings("unchecked")
    static Map<String, Cookie> jar(HttpSession s) {
        Object j = s.getAttribute(JAR);
        if (j == null) s.setAttribute(JAR, j = new LinkedHashMap<String, Cookie>());
        return (Map<String, Cookie>) j;
    }

    @Bean
    MockMvcBuilderCustomizer cookieJar() {
        return builder -> builder
                .defaultRequest(get("/").with(request -> {
                    HttpSession s = request.getSession(false);
                    if (s == null || jar(s).isEmpty()) return request;
                    Map<String, Cookie> all = new LinkedHashMap<>(jar(s));
                    if (request.getCookies() != null) for (Cookie c : request.getCookies()) all.put(c.getName(), c);
                    request.setCookies(all.values().toArray(Cookie[]::new));
                    return request;
                }))
                .alwaysDo(result -> {
                    HttpSession s = result.getRequest().getSession(false);
                    if (s == null) return;
                    for (Cookie c : result.getResponse().getCookies()) {
                        if (c.getMaxAge() == 0 || c.getValue().isEmpty()) jar(s).remove(c.getName());
                        else jar(s).put(c.getName(), new Cookie(c.getName(), c.getValue()));
                    }
                });
    }
}
