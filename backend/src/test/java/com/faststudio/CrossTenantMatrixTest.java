package com.faststudio;

import com.faststudio.core.TenantScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Cross-tenant matrix generated from the live route table: every endpoint under /api/orgs/{org} (current and future)
 * is called by a Northwind user (1) on Aurora's org path and (2) on Northwind's own path with Aurora's resource IDs.
 * (1) must be 404; (2) must never return Aurora data; and Aurora's data must be unchanged afterwards.
 * A new endpoint with an unknown path variable fails the test until a value is mapped below, so coverage can't silently lapse.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantMatrixTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path dir = Files.createTempDirectory("fast-studio-test");
        r.add("faststudio.data-dir", dir::toString);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired com.faststudio.core.Tokens tokens;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    record Ids(UUID org, UUID channel, UUID asset, UUID adAsset, UUID campaign, UUID entry, UUID destination, UUID user, UUID alert, UUID link) {}

    Ids aurora() {
        return TenantScope.bypass(() -> {
            UUID org = db.sql("select id from organization where slug = 'aurora'").query(UUID.class).single();
            UUID channel = db.sql("select id from channel where org_id = :o and slug = 'aurora-action'").param("o", org).query(UUID.class).single();
            UUID alert = db.sql("insert into alert(org_id, dedup_key, severity, title, detail) values (:o, 'matrix-test', 'WARNING', 'Aurora only', 'x') on conflict (org_id, dedup_key) where status <> 'RESOLVED' do update set updated_at = now() returning id")
                    .param("o", org).query(UUID.class).single();
            return new Ids(org, channel,
                    db.sql("select id from asset where org_id = :o and kind = 'MOVIE' limit 1").param("o", org).query(UUID.class).single(),
                    db.sql("select id from asset where org_id = :o and kind = 'AD' limit 1").param("o", org).query(UUID.class).single(),
                    db.sql("select id from campaign where org_id = :o limit 1").param("o", org).query(UUID.class).single(),
                    db.sql("select e.id from schedule_entry e join channel c on c.published_version_id = e.version_id where c.id = :c limit 1").param("c", channel).query(UUID.class).single(),
                    db.sql("select id from destination where org_id = :o limit 1").param("o", org).query(UUID.class).single(),
                    db.sql("select id from app_user where email = 'operator@aurora.test'").query(UUID.class).single(),
                    alert,
                    db.sql("select id from partner_link where owner_org_id = :o limit 1").param("o", org).query(UUID.class).single());
        });
    }

    String snapshot(UUID org) {
        return TenantScope.bypass(() -> db.sql("""
                select md5(string_agg(t, '|' order by t)) from (
                  select a.id || a.title || a.status || a.cue_points as t from asset a where org_id = :o
                  union all select c.id || c.name || c.status || c.territories || coalesce(c.published_version_id::text, '') from channel c where org_id = :o
                  union all select e.id || e.start_at::text || e.kind from schedule_entry e where org_id = :o
                  union all select p.id || p.allowed_countries || p.blocked_countries from rights_policy p where org_id = :o
                  union all select id || status || name from campaign where org_id = :o
                  union all select id || name from destination where org_id = :o
                  union all select id || status from alert where org_id = :o and dedup_key = 'matrix-test'
                  union all select user_id || role from membership where org_id = :o
                  union all select id || status || scopes from partner_link where owner_org_id = :o) x""").param("o", org).query(String.class).single());
    }

    String fill(String pattern, String org, Ids a) {
        StringBuilder out = new StringBuilder();
        String prev = "";
        for (String seg : pattern.substring(1).split("/")) {
            String v = seg;
            if (seg.startsWith("{")) {
                String name = seg.substring(1, seg.length() - 1);
                v = switch (name) {
                    case "org" -> org;
                    case "id" -> switch (prev) {
                        case "channels" -> a.channel().toString();
                        case "assets" -> a.asset().toString();
                        case "campaigns" -> a.campaign().toString();
                        case "alerts" -> a.alert().toString();
                        default -> fail("No test value for {id} after /" + prev + " in " + pattern);
                    };
                    case "entryId" -> a.entry().toString();
                    case "channelId" -> a.channel().toString();
                    case "destinationId" -> a.destination().toString();
                    case "userId" -> a.user().toString();
                    case "linkId" -> a.link().toString();
                    default -> fail("No test value for path variable {" + name + "} in " + pattern + " — map it so the matrix covers this endpoint");
                };
            }
            out.append('/').append(v);
            prev = seg;
        }
        return out.toString();
    }

    @Test
    void everyTenantEndpointIsIsolated() throws Exception {
        Ids a = aurora();
        UUID northwindOwner = db.sql("select id from app_user where email = 'owner@northwind.test'").query(UUID.class).single();
        UUID northwind = db.sql("select id from organization where slug = 'northwind'").query(UUID.class).single();
        // a real sign-in token for Northwind's owner; paths use the organizations' stable IDs (UUIDs)
        String bearer = "Bearer " + tokens.mint(northwindOwner, northwind, UUID.randomUUID()).token();
        String before = snapshot(a.org());
        // a body that references Aurora's resources wherever an endpoint accepts IDs
        String body = """
                {"assetId":"%s","channelId":"%s","creativeAssetId":"%s","fallbackAssetId":"%s","assetIds":["%s"],
                 "expectedRevision":0,"country":"IN","title":"x","name":"x","kind":"MOVIE","advertiser":"x","description":"x",
                 "startsAt":"2026-01-01T00:00:00Z","endsAt":"2027-01-01T00:00:00Z","priority":5,"cpm":1,"status":"PAUSED",
                 "resolution":"x","email":"x@matrix.test","role":"ANALYST","versionNumber":1,"allowedCountries":["WW"],
                 "adsAllowed":true,"seconds":30,"hours":1,"territories":["IN"],"adMode":"SSAI","markerFormat":"DATERANGE",
                 "maxAdSecondsPerHour":600,"note":"x"}""".formatted(a.asset(), a.channel(), a.adAsset(), a.asset(), a.asset());
        List<String> leaks = new ArrayList<>(), aurora = List.of(a.org().toString(), a.channel().toString(), a.asset().toString(),
                a.adAsset().toString(), a.campaign().toString(), a.entry().toString(), a.destination().toString(), a.alert().toString(), "Aurora Media");
        int covered = 0;
        for (var info : mapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            MediaType type = info.getConsumesCondition().getConsumableMediaTypes().stream().findFirst().orElse(MediaType.APPLICATION_JSON);
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/orgs/{org}")) continue;
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    covered++;
                    MvcResult foreign = call(m, fill(pattern, a.org().toString(), a), type, body, bearer);
                    if (foreign.getResponse().getStatus() != 404)
                        leaks.add(m + " " + pattern + " on Aurora's path returned " + foreign.getResponse().getStatus() + " (expected 404)");
                    MvcResult own = call(m, fill(pattern, northwind.toString(), a), type, body, bearer);
                    int st = own.getResponse().getStatus();
                    String text = own.getResponse().getContentAsString();
                    if (st >= 200 && st < 300)
                        aurora.stream().filter(text::contains).findFirst()
                                .ifPresent(x -> leaks.add(m + " " + pattern + " with Aurora IDs returned " + st + " containing " + x));
                }
            }
        }
        System.out.println("Cross-tenant matrix covered " + covered + " tenant endpoints");
        assertTrue(covered >= 35, "expected the matrix to cover all tenant routes, covered " + covered);
        assertEquals(List.of(), leaks);
        assertEquals(before, snapshot(a.org()), "Aurora's data changed while another tenant probed it");
    }

    MvcResult call(RequestMethod m, String path, MediaType type, String body, String bearer) throws Exception {
        var req = request(HttpMethod.valueOf(m.name()), path).header("Authorization", bearer).accept(MediaType.ALL);
        if (m != RequestMethod.GET && m != RequestMethod.DELETE) req = req.contentType(type).content(type.equals(MediaType.APPLICATION_JSON) ? body : "");
        if (m == RequestMethod.DELETE) req = req.param("expectedRevision", "0");
        return mvc.perform(req).andReturn();
    }

    /**
     * Partner isolation (demo partner "Partner TV": Aurora shares Aurora Action + Classic Cinema with channels, EPG,
     * stream and markers, not delivery numbers). For every partner read endpoint: another customer's data, an
     * unshared channel or an unshared scope is 404; shared data returns 200 without anything else leaking.
     * Customer pages are closed to the partner, on Aurora's paths and on its own.
     */
    @Test
    void partnersSeeOnlyWhatIsShared() throws Exception {
        UUID aurora = db.sql("select id from organization where slug = 'aurora'").query(UUID.class).single();
        UUID northwind = db.sql("select id from organization where slug = 'northwind'").query(UUID.class).single();
        UUID partner = db.sql("select id from organization where slug = 'partner-tv'").query(UUID.class).single();
        UUID sam = db.sql("select id from app_user where email = 'partner@partnertv.test'").query(UUID.class).single();
        Map<String, UUID> ch = new HashMap<>();
        com.faststudio.core.TenantScope.bypass(() -> { db.sql("select slug, id from channel where org_id in (:a, :n)").param("a", aurora).param("n", northwind)
                .query((rs, i) -> ch.put(rs.getString("slug"), rs.getObject("id", UUID.class))).list(); return null; });
        String bearer = "Bearer " + tokens.mint(sam, partner, UUID.randomUUID()).token();
        Ids a = aurora();
        List<String> problems = new ArrayList<>();
        Set<String> shared = Set.of("channels:read", "epg:read", "stream:read", "markers:read");
        int covered = 0;
        for (var e : mapping.getHandlerMethods().entrySet()) {
            for (String pattern : e.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/partners/{partner}/sources/{owner}")) continue;
                covered++;
                String scope = e.getValue().getMethodAnnotation(com.faststudio.partners.PartnerScope.class).value();
                java.util.function.BiFunction<UUID, UUID, String> path = (owner, channel) -> pattern.replace("{partner}", partner.toString())
                        .replace("{owner}", owner.toString()).replace("{channelId}", channel.toString());
                int other = get(path.apply(northwind, ch.get("sports-classics")), bearer).getResponse().getStatus();
                if (other != 404) problems.add(pattern + ": another customer's data answered " + other);
                if (pattern.contains("{channelId}")) {
                    int unshared = get(path.apply(aurora, ch.get("travel-discovery")), bearer).getResponse().getStatus();
                    if (unshared != 404) problems.add(pattern + ": unshared channel answered " + unshared);
                }
                MvcResult own = get(path.apply(aurora, ch.get("aurora-action")), bearer);
                int expected = shared.contains(scope) ? 200 : 404;
                if (own.getResponse().getStatus() != expected) problems.add(pattern + " (" + scope + "): expected " + expected + " got " + own.getResponse().getStatus());
                String text = own.getResponse().getContentAsString();
                for (String leak : List.of("Northwind", "Travel & Discovery", "Family Entertainment", "campaign", "Zenith"))
                    if (text.contains(leak)) problems.add(pattern + " leaked " + leak);
            }
        }
        for (var info : mapping.getHandlerMethods().keySet())
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/orgs/{org}") || !info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) continue;
                int onAurora = get(fill(pattern, aurora.toString(), a), bearer).getResponse().getStatus();
                if (onAurora != 404) problems.add("customer page " + pattern + " on Aurora answered " + onAurora + " to a partner");
                if (!pattern.contains("/members")) {
                    int onOwn = get(fill(pattern, partner.toString(), a), bearer).getResponse().getStatus();
                    if (onOwn != 404) problems.add("customer page " + pattern + " on the partner's own org answered " + onOwn);
                }
            }
        System.out.println("Partner matrix covered " + covered + " partner read endpoints");
        assertTrue(covered >= 6, "expected all partner read routes, covered " + covered);
        assertEquals(List.of(), problems);
    }

    MvcResult get(String path, String bearer) throws Exception {
        return mvc.perform(request(HttpMethod.GET, path).header("Authorization", bearer).accept(MediaType.ALL)).andReturn();
    }
}
