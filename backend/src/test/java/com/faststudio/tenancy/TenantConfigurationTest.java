package com.faststudio.tenancy;

import com.faststudio.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class TenantConfigurationTest {
    static final String ADMIN="admin@platform.test",OWNER="owner@aurora.test";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir=Files.createTempDirectory("fast-tenancy").toString();
        r.add("faststudio.data-dir",()->dir);r.add("faststudio.db.port",()->0);
        r.add("faststudio.ffmpeg",()->"disabled");r.add("spring.datasource.url",()->"embedded");
    }
    @Autowired MockMvc mvc;@Autowired JdbcClient db;@Autowired Tokens tokens;@Autowired JsonMapper json;
    UUID org(String slug){return db.sql("select id from organization where slug=:s").param("s",slug).query(UUID.class).single();}
    UUID user(String email){return db.sql("select id from app_user where email=:e").param("e",email).query(UUID.class).single();}
    String unique(){return "test-"+UUID.randomUUID().toString().substring(0,8);}
    MvcResult call(MockHttpServletRequestBuilder request,String email,Object body)throws Exception{
        if(body!=null)request=request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request.header("Authorization","Bearer "+tokens.mint(user(email),null,UUID.randomUUID()).token())).andReturn();
    }
    JsonNode ok(MvcResult result)throws Exception{assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());return json.readTree(result.getResponse().getContentAsString());}
    String base(){return "/api/platform/tenants/"+org("aurora");}
    Map<String,Object> property(String key,String type,Object value,boolean secret){
        Map<String,Object> p=new HashMap<>();p.put("key",key);p.put("label",key);p.put("valueType",type);p.put("secret",secret);
        p.put("projectOverride",true);p.put("enabled",true);p.put("options",List.of());p.put("defaultValue",value);return p;
    }
    JsonNode effective(String path,String key,String as)throws Exception{
        for(JsonNode p:ok(call(get(path),as,null)).path("properties"))if(p.path("definition").path("key").asString().equals(key))return p;
        throw new AssertionError("Missing property "+key);
    }
    @Test void customerMigrationExcludesPartnersAndPreservesExistingProjectIds()throws Exception{
        JsonNode tenants=ok(call(get("/api/platform/tenants"),ADMIN,null));
        assertTrue(tenants.valueStream().anyMatch(t->t.path("id").asString().equals(org("aurora").toString())));
        assertFalse(tenants.valueStream().anyMatch(t->t.path("slug").asString().equals("partner-tv")));
        assertEquals(0,db.sql("select count(*) from organization where kind in ('PARTNER','PLATFORM') and tenant_id is not null").query(Integer.class).single());
        JsonNode bootstrap=ok(call(get("/api/orgs/aurora/project-details"),OWNER,null));
        assertEquals(org("aurora").toString(),bootstrap.path("configuration").path("tenant").path("id").asString());
        assertEquals(2,bootstrap.path("schemaVersion").asInt());
        JsonNode claims=json.readTree(Base64.getUrlDecoder().decode(tokens.mint(user("partner@partnertv.test"),null,UUID.randomUUID()).token().split("\\.")[1]));
        assertFalse(claims.has("tid"),"A partner organization must not claim a customer tenant");
    }
    @Test void globalTenantAndProjectPropertiesResolveValidateAndReset()throws Exception{
        String key=unique();ok(call(put("/api/platform/property-definitions"),ADMIN,property(key,"BOOLEAN",true,false)));
        String path="/api/orgs/aurora/configuration";
        assertEquals("GLOBAL",effective(path,key,OWNER).path("source").asString());
        ok(call(put(base()+"/properties/"+key),ADMIN,Map.of("value",false)));
        assertFalse(effective(path,key,OWNER).path("value").asBoolean());
        ok(call(put(path+"/properties/"+key),OWNER,Map.of("value",true)));
        assertEquals("PROJECT",effective(path,key,OWNER).path("source").asString());
        assertTrue(effective(path,key,OWNER).path("value").asBoolean());
        ok(call(put(path+"/properties/"+key),OWNER,Map.of("reset",true)));
        assertEquals("TENANT",effective(path,key,OWNER).path("source").asString());
        assertFalse(effective(path,key,OWNER).path("value").asBoolean());
        assertEquals(422,call(put(path+"/properties/"+key),OWNER,Map.of("value","not-a-boolean")).getResponse().getStatus());
        assertEquals(403,call(put(path+"/properties/"+key),"analyst@aurora.test",Map.of("value",true)).getResponse().getStatus());
        assertEquals(404,call(get(path),"owner@northwind.test",null).getResponse().getStatus());
    }
    @Test void storedSecretsNeverReturnAndOverridesAreEncrypted()throws Exception{
        String key=unique(),secret="fixture-global-secret-"+UUID.randomUUID(),replacement="fixture-project-secret-"+UUID.randomUUID();
        MvcResult created=call(put("/api/platform/property-definitions"),ADMIN,property(key,"STRING",secret,true));ok(created);
        assertFalse(created.getResponse().getContentAsString().contains(secret));
        String path=base()+"/projects/"+org("aurora")+"/properties/"+key;
        MvcResult updated=call(put(path),ADMIN,Map.of("value",replacement));ok(updated);
        assertFalse(updated.getResponse().getContentAsString().contains(replacement));
        JsonNode p=effective("/api/orgs/aurora/configuration",key,OWNER);
        assertTrue(p.path("configured").asBoolean());assertTrue(p.path("value").isNull());
        String stored=TenantScope.bypass(()->db.sql("select value from project_property_value where org_id=:p and property_key=:k").param("p",org("aurora")).param("k",key).query(String.class).single());
        assertFalse(stored.contains(replacement));
        assertEquals(403,call(put("/api/orgs/aurora/configuration/properties/"+key),OWNER,Map.of("value",replacement)).getResponse().getStatus());
        MvcResult bootstrap=call(get("/api/orgs/aurora/project-details"),OWNER,null);ok(bootstrap);
        assertFalse(bootstrap.getResponse().getContentAsString().contains(secret));assertFalse(bootstrap.getResponse().getContentAsString().contains(replacement));
        assertTrue(bootstrap.getResponse().getHeader("Cache-Control").contains("no-store"));
    }
    @Test void siblingProjectsStayIsolatedAndForeignParentCannotBeUsed()throws Exception{
        String slug=unique();UUID original=org("aurora");
        JsonNode project=ok(call(post(base()+"/projects"),ADMIN,Map.of("name","Second project","slug",slug,"country","IN","timezone","Asia/Kolkata","ownerUserId",user(OWNER))));
        UUID sibling=UUID.fromString(project.path("id").asString());
        assertEquals(original.toString(),project.path("tenantId").asString());
        assertEquals(404,call(get("/api/orgs/"+slug+"/configuration"),"owner@northwind.test",null).getResponse().getStatus());
        assertEquals(404,call(get("/api/platform/tenants/"+org("northwind")+"/projects/"+sibling+"/settings"),ADMIN,null).getResponse().getStatus());
        UUID asset=TenantScope.bypass(()->db.sql("insert into asset(org_id,title,kind) values(:o,'Sibling movie','MOVIE') returning id").param("o",sibling).query(UUID.class).single());
        assertEquals(0,TenantScope.as(original,()->db.sql("select count(*) from asset where id=:a").param("a",asset).query(Integer.class).single()));
        assertEquals(404,call(get("/api/orgs/aurora/assets/"+asset),OWNER,null).getResponse().getStatus());
    }
    @Test void connectorGatesCannotBeBypassedByDirectLibraryApi()throws Exception{
        String project="/api/orgs/aurora/configuration/connectors/mux";
        ok(call(put(project),OWNER,Map.of("enabled",false)));
        MvcResult response=call(post("/api/orgs/aurora/library/sync"),OWNER,null);
        assertEquals(403,response.getResponse().getStatus());
        ok(call(put(project),OWNER,Map.of("reset",true)));
        ok(call(put(base()+"/connectors/mux"),ADMIN,Map.of("enabled",false)));
        assertEquals(422,call(put(project),OWNER,Map.of("enabled",true)).getResponse().getStatus());
        ok(call(put(base()+"/connectors/mux"),ADMIN,Map.of("enabled",true)));
    }
    @Test void subscriptionLimitsAndStaleUpdatesAreEnforced()throws Exception{
        String slug=unique(),plan=unique();
        UUID id=UUID.fromString(ok(call(post("/api/platform/orgs"),ADMIN,Map.of("name","Limit fixture","slug",slug,"country","IN","timezone","UTC","ownerName","Asha","ownerEmail",OWNER,"kind","CUSTOMER"))).path("id").asString());
        ok(call(put("/api/platform/plans"),ADMIN,Map.of("key",plan,"name","Small","enabled",true,"maxProjects",1,"maxChannels",0,"monthlyPrice",5,"currency","USD","features",List.of())));
        String base="/api/platform/tenants/"+id;
        Map<String,Object> change=Map.of("planKey",plan,"status","ACTIVE","expectedRevision",0);
        ok(call(put(base+"/subscription"),ADMIN,change));
        assertEquals(409,call(put(base+"/subscription"),ADMIN,change).getResponse().getStatus());
        assertEquals(409,call(post(base+"/projects"),ADMIN,Map.of("name","Too many","slug",unique(),"country","IN","timezone","UTC","ownerUserId",user(OWNER))).getResponse().getStatus());
        assertEquals(409,call(post("/api/orgs/"+slug+"/channels"),OWNER,Map.of("name","Over quota")).getResponse().getStatus());
    }
    @Test void customCustomerAndPartnerRolesApplyActualPermissions()throws Exception{
        for(String kind:List.of("CUSTOMER","PARTNER")){
            String key=unique(),email=unique()+"@example.test",slug=kind.equals("CUSTOMER")?"aurora":"partner-tv",owner=kind.equals("CUSTOMER")?OWNER:"partner@partnertv.test";
            String baseRole=kind.equals("CUSTOMER")?"ADMIN":"PARTNER_ADMIN";
            ok(call(put("/api/platform/access-roles"),ADMIN,Map.of("key",key,"name","Limited role","kind",kind,"baseRole",baseRole,"permissions",List.of("VIEW"),"enabled",true)));
            ok(call(post("/api/orgs/"+slug+"/members"),owner,Map.of("email",email,"name","Limited member","role",baseRole,"customRoleKey",key)));
            db.sql("update app_user set must_change_password=false where email=:e").param("e",email).update();
            assertEquals(403,call(post("/api/orgs/"+slug+"/members"),email,Map.of("email",unique()+"@example.test","name","No escalation","role",baseRole)).getResponse().getStatus());
            JsonNode me=ok(call(get("/api/auth/me"),email,null));
            assertEquals(1,me.path("memberships").get(0).path("permissions").size());
            if(kind.equals("CUSTOMER")) assertEquals(403,call(post("/api/orgs/aurora/channels"),email,Map.of("name","Forbidden")).getResponse().getStatus());
            else assertEquals(403,call(post("/api/partners/partner-tv/clients"),email,Map.of("name","Forbidden","scopes",List.of("channels:read"))).getResponse().getStatus());
        }
        assertEquals(422,call(put("/api/platform/access-roles"),ADMIN,Map.of("key",unique(),"name","Escalated","kind","PARTNER","baseRole","PARTNER_VIEWER","permissions",List.of("VIEW","TEAM_MANAGE"),"enabled",true)).getResponse().getStatus());
    }
    /** Built-in programming settings (V15) resolve like custom properties, keep domain validation and cannot be disabled. */
    @Test void builtInSettingsAreDynamicPropertiesThatInherit()throws Exception{
        String key="programming.premiere_time",project=base()+"/projects/"+org("aurora")+"/properties/"+key;
        ok(call(put(project),ADMIN,Map.of("reset",true)));                 // drop any migrated project override
        ok(call(put(base()+"/properties/"+key),ADMIN,Map.of("value","21:00")));
        try {
            JsonNode view=ok(call(get("/api/orgs/aurora/settings"),OWNER,null));
            assertEquals("21:00",view.path("premiereTime").asString(),"project inherits the tenant value");
            assertEquals(422,call(put(base()+"/properties/"+key),ADMIN,Map.of("value","21:00:03")).getResponse().getStatus(),"five-second boundary still enforced");
            // Saving the settings form with an unchanged premiere time must not pin it as a project override.
            Map<String,Object> form=new HashMap<>();
            form.put("enabledTypes",json.convertValue(view.path("enabledTypes"),List.class));form.put("defaultType",view.path("defaultType").asString());
            form.put("premiereTime","21:00");form.put("weekdays",json.convertValue(view.path("weekdays"),List.class));
            form.put("personalizedAds",view.path("personalizedAds").asBoolean());form.put("expectedRevision",view.path("revision").asInt());
            ok(call(put("/api/orgs/aurora/settings"),OWNER,form));
            assertEquals("TENANT",effective("/api/orgs/aurora/configuration",key,OWNER).path("source").asString());
            Map<String,Object> disable=property(key,"STRING","20:00",false);disable.put("enabled",false);
            assertEquals(422,call(put("/api/platform/property-definitions"),ADMIN,disable).getResponse().getStatus());
        } finally { ok(call(put(base()+"/properties/"+key),ADMIN,Map.of("reset",true))); }
    }
    /** Tenant-wide defaults change every project, so a project ADMIN may only override their own project. */
    @Test void tenantDefaultsNeedTenantOwnerAndPlanFeaturesAreNotSilentlyDropped()throws Exception{
        String key=unique(),email=unique()+"@example.test";
        ok(call(put("/api/platform/property-definitions"),ADMIN,property(key,"BOOLEAN",false,false)));
        ok(call(post("/api/orgs/aurora/members"),OWNER,Map.of("email",email,"name","Project admin","role","ADMIN")));
        db.sql("update app_user set must_change_password=false where email=:e").param("e",email).update();
        assertEquals(403,call(put("/api/orgs/aurora/tenant-settings/properties/"+key),email,Map.of("value",true)).getResponse().getStatus());
        ok(call(put("/api/orgs/aurora/configuration/properties/"+key),email,Map.of("value",true)));
        ok(call(put("/api/orgs/aurora/tenant-settings/properties/"+key),OWNER,Map.of("value",true)));
        assertEquals(422,call(put("/api/platform/orgs/"+org("aurora")+"/features"),ADMIN,Map.of("features",List.of("NOT_IN_ANY_PLAN"))).getResponse().getStatus());
    }
    @Test void tenantSuspensionAndPlatformGuardsAreIndependentOfProjectFlags()throws Exception{
        assertEquals(403,call(get("/api/platform/tenants"),OWNER,null).getResponse().getStatus());
        ok(call(post(base()+"/status"),ADMIN,Map.of("status","SUSPENDED")));
        try {assertEquals(403,call(get("/api/orgs/aurora/configuration"),OWNER,null).getResponse().getStatus());}
        finally {ok(call(post(base()+"/status"),ADMIN,Map.of("status","ACTIVE")));}
    }
}
