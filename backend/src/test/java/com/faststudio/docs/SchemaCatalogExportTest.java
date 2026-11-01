package com.faststudio.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generates docs/html/visualizeDB.html — the visual database guide — from the REAL schema.
 *
 * <p>Boots an empty embedded database, applies every Flyway migration, reads the PostgreSQL catalog (tables, columns,
 * defaults, constraints, indexes, row-level security, triggers), scans the backend source for the SQL that touches each
 * table, merges docs/db/table-usage.json (plain-language purpose, feature, pages, APIs) and fills
 * docs/db/visualizeDB.template.html.
 *
 * <p>Run: {@code ./mvnw test -Dtest=SchemaCatalogExportTest -Ddbdocs=true} (skipped in normal test runs).
 * Fails when a table has no usage entry (or an entry names a table that no longer exists), so the guide cannot go stale.
 * See .claude/skills/visualize-db/SKILL.md.
 */
@SpringBootTest
class SchemaCatalogExportTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-dbdocs").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
        r.add("faststudio.demo", () -> false);
    }

    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    static final Path DOCS = Path.of("..", "docs");
    static final Path SOURCE = Path.of("src", "main", "java");

    @Test
    void exportVisualDatabaseGuide() throws Exception {
        assumeTrue(Boolean.getBoolean("dbdocs"), "Run with -Ddbdocs=true to regenerate docs/html/visualizeDB.html");

        List<Map<String, Object>> tables = db.sql("""
                select c.relname as name, c.relrowsecurity as rls, c.relforcerowsecurity as force_rls,
                       coalesce(obj_description(c.oid, 'pg_class'), '') as comment
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history' order by c.relname""")
                .query().listOfRows();
        List<Map<String, Object>> columns = db.sql("""
                select c.relname as table_name, a.attname as name, format_type(a.atttypid, a.atttypmod) as type, a.attnotnull as not_null,
                       pg_get_expr(d.adbin, d.adrelid) as default_value, a.attidentity as identity, a.attnum as position
                from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where n.nspname = 'public' and c.relkind = 'r' and a.attnum > 0 and not a.attisdropped order by c.relname, a.attnum""")
                .query().listOfRows();
        List<Map<String, Object>> constraints = db.sql("""
                select c.relname as table_name, k.conname as name, k.contype as kind, pg_get_constraintdef(k.oid) as definition,
                       f.relname as references_table
                from pg_constraint k join pg_class c on c.oid = k.conrelid join pg_namespace n on n.oid = c.relnamespace
                left join pg_class f on f.oid = k.confrelid
                where n.nspname = 'public' order by c.relname, k.contype, k.conname""")
                .query().listOfRows();
        List<Map<String, Object>> indexes = db.sql("select tablename as table_name, indexname as name, indexdef as definition from pg_indexes where schemaname = 'public' order by tablename, indexname")
                .query().listOfRows();
        List<Map<String, Object>> policies = db.sql("select tablename as table_name, policyname as name, cmd as command, qual as using_expr, with_check from pg_policies where schemaname = 'public' order by tablename, policyname")
                .query().listOfRows();
        List<Map<String, Object>> triggers = db.sql("""
                select c.relname as table_name, t.tgname as name, pg_get_triggerdef(t.oid) as definition
                from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and not t.tgisinternal order by c.relname, t.tgname""")
                .query().listOfRows();
        List<Map<String, Object>> functions = db.sql("""
                select p.proname as name, pg_get_function_identity_arguments(p.oid) as arguments, pg_get_functiondef(p.oid) as definition
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' order by p.proname""")
                .query().listOfRows();
        int migration = db.sql("select coalesce(max(installed_rank), 0) from flyway_schema_history where success").query(Integer.class).single();
        String version = db.sql("select version from flyway_schema_history where success order by installed_rank desc limit 1").query(String.class).single();

        Set<String> names = new TreeSet<>();
        tables.forEach(t -> names.add((String) t.get("name")));

        // Plain-language usage, maintained by hand next to the template.
        JsonNode usage = json.readTree(Files.readString(DOCS.resolve("db/table-usage.json")));
        Set<String> documented = new TreeSet<>();
        usage.path("tables").propertyNames().forEach(documented::add);
        Set<String> missing = new TreeSet<>(names);
        missing.removeAll(documented);
        Set<String> stale = new TreeSet<>(documented);
        stale.removeAll(names);
        assertTrue(missing.isEmpty(), "Add these tables to docs/db/table-usage.json: " + missing);
        assertTrue(stale.isEmpty(), "Remove these tables from docs/db/table-usage.json (no longer in the schema): " + stale);

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("generatedFrom", "Flyway migrations up to V" + version + " (" + migration + " applied), embedded PostgreSQL");
        catalog.put("tables", tables);
        catalog.put("columns", columns);
        catalog.put("constraints", constraints);
        catalog.put("indexes", indexes);
        catalog.put("policies", policies);
        catalog.put("triggers", triggers);
        catalog.put("functions", functions);
        catalog.put("code", codeReferences(names));

        String template = Files.readString(DOCS.resolve("db/visualizeDB.template.html"));
        assertTrue(template.contains("/*__CATALOG__*/") && template.contains("/*__USAGE__*/"), "Template placeholders missing");
        String html = template
                .replace("/*__CATALOG__*/null", safeScript(json.writeValueAsString(catalog)))
                .replace("/*__USAGE__*/null", safeScript(json.writeValueAsString(usage)));
        Files.createDirectories(DOCS.resolve("html"));
        Files.writeString(DOCS.resolve("html/visualizeDB.html"), html, StandardCharsets.UTF_8);
        assertFalse(html.contains("/*__CATALOG__*/"), "Catalog was not injected");
    }

    /** JSON inside a script tag must not be able to close it. */
    private static String safeScript(String jsonText) {
        return jsonText.replace("</", "<\\/");
    }

    /**
     * For each table, the backend classes whose SQL reads or writes it (select/insert/update/delete/join), with the layer
     * (controller, service, repository, core, config) taken from the package.
     */
    private static Map<String, List<Map<String, String>>> codeReferences(Set<String> tables) throws Exception {
        Map<String, List<Map<String, String>>> out = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCE)) { files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
        for (Path f : files) {
            String src = Files.readString(f);
            String rel = SOURCE.relativize(f).toString().replace('\\', '/');
            String layer = rel.contains("/controllers/") ? "controller" : rel.contains("/services/") ? "service"
                    : rel.contains("/repositories/") ? "repository" : rel.contains("/config/") ? "config" : rel.contains("/core/") ? "core" : "other";
            for (String t : tables) {
                var m = Pattern.compile("(?i)\\b(from|join|into|update|delete\\s+from)\\s+" + t + "\\b").matcher(src);
                Set<String> ops = new TreeSet<>();
                while (m.find()) {
                    String op = m.group(1).toLowerCase(Locale.ROOT);
                    ops.add(op.startsWith("into") ? "insert" : op.startsWith("update") ? "update" : op.startsWith("delete") ? "delete" : "read");
                }
                if (!ops.isEmpty())
                    out.computeIfAbsent(t, k -> new ArrayList<>()).add(Map.of(
                            "file", "backend/src/main/java/" + rel,
                            "class", f.getFileName().toString().replace(".java", ""),
                            "layer", layer,
                            "operations", String.join(", ", ops)));
            }
        }
        return out;
    }
}
