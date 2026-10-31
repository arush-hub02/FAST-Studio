package com.faststudio;

import com.faststudio.core.*;
import com.faststudio.edge.EdgePublisher;
import com.faststudio.schedule.ProgrammingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProgrammingFeaturesTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) throws Exception {
        String dir=Files.createTempDirectory("fast-programming").toString();
        r.add("faststudio.data-dir",()->dir); r.add("faststudio.db.port",()->0);
        r.add("faststudio.ffmpeg",()->"disabled"); r.add("spring.datasource.url",()->"embedded");
        r.add("faststudio.edge.secret",()->"test-edge-secret-at-least-32-characters");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired Secrets secrets;
    @Autowired JsonMapper json;
    @MockitoBean EdgePublisher publisher;
    UUID org() { return db.sql("select id from organization where slug='aurora'").query(UUID.class).single(); }
    @BeforeEach void reset() {
        when(publisher.stored(anyString())).thenReturn(true);
        TenantScope.bypass(()->db.sql("delete from tenant_settings where org_id=:o").param("o",org()).update());
        TenantScope.bypass(()->db.sql("update campaign set status='PAUSED' where org_id=:o and name='Audience ad' and advertiser like 'Advertiser-%'").param("o",org()).update());
    }
    MvcResult call(MockHttpServletRequestBuilder request,String email,Object body) throws Exception {
        UUID user=db.sql("select id from app_user where email=:e").param("e",email).query(UUID.class).single();
        if(body!=null) request=request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request.header("Authorization","Bearer "+tokens.mint(user,null,UUID.randomUUID()).token())).andReturn();
    }
    JsonNode ok(MvcResult r) throws Exception {
        assertTrue(r.getResponse().getStatus()>=200 && r.getResponse().getStatus()<300,r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }
    UUID channel() {
        return TenantScope.bypass(()->{
            UUID ch=db.sql("insert into channel(org_id,slug,name,timezone,territories) values (:o,:s,'Test','Asia/Kolkata','IN') returning id")
                    .param("o",org()).param("s","test-"+UUID.randomUUID()).query(UUID.class).single();
            db.sql("insert into schedule_version(org_id,channel_id,number,status) values (:o,:c,0,'DRAFT')").param("o",org()).param("c",ch).update();
            return ch;
        });
    }
    UUID episode(String series,int season,int episode) {
        return TenantScope.bypass(()->{
            UUID id=db.sql("insert into asset(org_id,title,kind,media_type,series,season,episode,duration_ms,status) values (:o,:t,'EPISODE','EPISODE',:s,:se,:ep,60000,'READY') returning id")
                    .param("o",org()).param("t",series+" "+season+"/"+episode).param("s",series).param("se",season).param("ep",episode).query(UUID.class).single();
            db.sql("insert into rights_policy(org_id,asset_id,allowed_countries,ads_allowed) values (:o,:a,'WW',false)").param("o",org()).param("a",id).update();
            return id;
        });
    }
    Map<String,Object> rule(String series,LocalDate start,LocalDate end) {
        return Map.of("series",series,"startDate",start.toString(),"endDate",end.toString(),"premiereTime","20:00","repeatTime","23:00",
                "weekdays",List.of(1,2,3,4,5,6,7),"startSeason",1,"startEpisode",1,"loop",false);
    }
    @Test void dailyProgressionEncoresSeasonRolloverAndRegeneration() throws Exception {
        UUID ch=channel(); String series="Show-"+UUID.randomUUID(); episode(series,1,1); episode(series,1,2); episode(series,2,1);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        String base="/api/orgs/aurora/channels/"+ch+"/programming";
        var body=rule(series,day,day.plusDays(2));
        var preview=ok(call(post(base+"/preview"),"owner@aurora.test",body));
        assertEquals(6,preview.path("placements").size());
        assertEquals(1,preview.path("placements").get(0).path("episode").asInt());
        assertEquals(1,preview.path("placements").get(1).path("episode").asInt());
        assertTrue(preview.path("placements").get(1).path("encore").asBoolean());
        assertEquals(2,preview.path("placements").get(4).path("season").asInt());
        ok(call(post(base),"owner@aurora.test",body));
        var generated=ok(call(post(base+"/generate"),"owner@aurora.test",Map.of("expectedRevision",0,"days",14)));
        assertEquals(6,generated.path("placements").size());
        var repeated=ok(call(post(base+"/generate"),"owner@aurora.test",Map.of("expectedRevision",1,"days",14)));
        assertEquals(0,repeated.path("placements").size()); assertEquals(1,repeated.path("revision").asInt());
        assertEquals(409,call(post(base+"/generate"),"owner@aurora.test",Map.of("expectedRevision",0,"days",14)).getResponse().getStatus());
        assertEquals(404,call(get(base),"owner@northwind.test",null).getResponse().getStatus());
    }
    @Test void missingEpisodesHoldLaterDaysAndExistingPlacementsAreNotMoved() throws Exception {
        UUID ch=channel(); String series="Missing-"+UUID.randomUUID(); episode(series,1,1); episode(series,1,3);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        String base="/api/orgs/aurora/channels/"+ch+"/programming";
        var preview=ok(call(post(base+"/preview"),"owner@aurora.test",rule(series,day,day.plusDays(2))));
        assertEquals(2,preview.path("placements").size()); assertTrue(preview.path("notices").toString().contains("S1 E2"));
        UUID a=episode("Occupied-"+UUID.randomUUID(),1,1);
        TenantScope.bypass(()->db.sql("insert into schedule_entry(org_id,version_id,kind,asset_id,start_at,end_at) select :o,id,'PROGRAM',:a,:s,:e from schedule_version where channel_id=:c and number=0")
                .param("o",org()).param("a",a).param("s",day.atTime(20,0).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime())
                .param("e",day.atTime(20,1).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime()).param("c",ch).update());
        var blocked=ok(call(post(base+"/preview"),"owner@aurora.test",rule(series,day,day.plusDays(2))));
        assertEquals(0,blocked.path("placements").size()); assertTrue(blocked.path("notices").toString().contains("conflicts"));
    }
    @Test void wallClockSchedulingDoesNotDriftAcrossDstAndRejectsAmbiguousTimes() {
        assertEquals(Duration.ofHours(23),Duration.between(ProgrammingService.wallClock(LocalDate.of(2027,3,13),LocalTime.of(20,0),"America/New_York"),ProgrammingService.wallClock(LocalDate.of(2027,3,14),LocalTime.of(20,0),"America/New_York")));
        assertEquals(Duration.ofHours(25),Duration.between(ProgrammingService.wallClock(LocalDate.of(2027,11,6),LocalTime.of(20,0),"America/New_York"),ProgrammingService.wallClock(LocalDate.of(2027,11,7),LocalTime.of(20,0),"America/New_York")));
        assertThrows(ApiException.class,()->ProgrammingService.wallClock(LocalDate.of(2027,3,14),LocalTime.of(2,30),"America/New_York"));
        assertThrows(ApiException.class,()->ProgrammingService.wallClock(LocalDate.of(2027,11,7),LocalTime.of(1,30),"America/New_York"));
    }
    @Test void settingsAreEnforcedAndEpisodeMetadataIsRequired() throws Exception {
        String base="/api/orgs/aurora";
        assertEquals(422,call(post(base+"/assets"),"owner@aurora.test",Map.of("title","Incomplete","kind","EPISODE")).getResponse().getStatus());
        String name="New-"+UUID.randomUUID();
        var metadata=Map.of("title","Episode","kind","EPISODE","series",name,"season",1,"episode",1);
        ok(call(post(base+"/assets"),"owner@aurora.test",metadata));
        assertEquals(409,call(post(base+"/assets"),"owner@aurora.test",metadata).getResponse().getStatus());
        String imported=ok(call(post(base+"/assets"),"owner@aurora.test",Map.of("title","Imported video","kind","MOVIE"))).path("asset").path("id").asString();
        JsonNode classified=ok(call(patch(base+"/assets/"+imported),"owner@aurora.test",Map.of("mediaType","EPISODE","series",name,"season",1,"episode",2)));
        assertEquals("EPISODE",classified.path("asset").path("kind").asString());
        assertEquals(422,call(patch(base+"/assets/"+imported),"owner@aurora.test",Map.of("mediaType","AD")).getResponse().getStatus());
        var settings=Map.of("enabledTypes",List.of("MOVIE"),"defaultType","MOVIE","premiereTime","20:00","weekdays",List.of(1,2,3,4,5),"personalizedAds",false,"expectedRevision",0);
        assertEquals(403,call(put(base+"/settings"),"content@aurora.test",settings).getResponse().getStatus());
        ok(call(put(base+"/settings"),"owner@aurora.test",settings));
        assertEquals(422,call(post(base+"/assets"),"owner@aurora.test",Map.of("title","Disabled","kind","SHORT")).getResponse().getStatus());
        assertEquals(403,call(post("/api/platform/media-types"),"owner@aurora.test",Map.of("key","CUSTOM","label","Custom","kind","MOVIE","enabled",true)).getResponse().getStatus());
    }

    @Test void platformMediaTypesRequireExplicitTenantEntitlement() throws Exception {
        String type="MUSIC_"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        ok(call(post("/api/platform/media-types"),"admin@platform.test",Map.of("key",type,"label","Music video","kind","SHORT","enabled",true)));
        var asset=Map.of("title","Music video","kind","SHORT","mediaType",type);
        assertEquals(422,call(post("/api/orgs/aurora/assets"),"owner@aurora.test",asset).getResponse().getStatus());
        ok(call(put("/api/platform/orgs/"+org()+"/media-types"),"admin@platform.test",Map.of("types",List.of("MOVIE","EPISODE","SHORT","AD","PROMO",type))));
        assertEquals(type,ok(call(post("/api/orgs/aurora/assets"),"owner@aurora.test",asset)).path("asset").path("mediaType").asString());
        assertEquals(422,call(post("/api/orgs/northwind/assets"),"owner@northwind.test",asset).getResponse().getStatus());
    }

    String configureViewerKey() {
        String key="viewer-test-secret-"+UUID.randomUUID();
        TenantScope.bypass(()->db.sql("insert into tenant_settings(org_id,enabled_types,default_type,personalized_ads,viewer_secret) values (:o,'MOVIE,EPISODE,SHORT,AD,PROMO','MOVIE',true,:s)")
                .param("o",org()).param("s",secrets.seal(key)).update());
        return key;
    }
    String viewerToken(String key,String subject,String audience,boolean consent,long exp) throws Exception {
        long now=Instant.now().getEpochSecond();
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("org",org().toString(),"sub",subject,"audiences",List.of(audience),"consent",consent,"iat",now,"exp",exp)));
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    record Live(UUID channel,UUID breakId,UUID campaign) {}
    Live live() {
        UUID ch=channel();
        return TenantScope.bypass(()->{
            UUID v=db.sql("insert into schedule_version(org_id,channel_id,number,status) values (:o,:c,1,'PUBLISHED') returning id").param("o",org()).param("c",ch).query(UUID.class).single();
            db.sql("update channel set status='LIVE',published_version_id=:v where id=:c").param("v",v).param("c",ch).update();
            UUID b=db.sql("insert into schedule_entry(org_id,version_id,kind,start_at,end_at,ownership) values (:o,:v,'AD_BREAK',now(),now()+interval '30 seconds','PROVIDER') returning id").param("o",org()).param("v",v).query(UUID.class).single();
            UUID creative=db.sql("insert into asset(org_id,title,kind,duration_ms,status,media_key) values (:o,'Test ad','AD',30000,'READY',:key) returning id").param("o",org()).param("key","test/"+UUID.randomUUID()).query(UUID.class).single();
            UUID campaign=db.sql("insert into campaign(org_id,name,advertiser,creative_asset_id,starts_at,ends_at,territories,priority,cpm,status,audiences,frequency_cap) values (:o,'Audience ad',:a,:cr,now()-interval '1 day',now()+interval '1 day','IN',10,10,'ACTIVE','SPORTS',1) returning id")
                    .param("o",org()).param("a","Advertiser-"+UUID.randomUUID()).param("cr",creative).query(UUID.class).single();
            return new Live(ch,b,campaign);
        });
    }
    MvcResult start(Live live,String token) throws Exception {
        String slug=TenantScope.bypass(()->db.sql("select slug from channel where id=:id").param("id",live.channel()).query(String.class).single());
        Map<String,Object> body=new HashMap<>(Map.of("org","aurora","channel",slug,"country","IN"));
        if(token!=null) body.put("viewerToken",token);
        return mvc.perform(post("/play/sessions").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }
    JsonNode decision(String sid,UUID breakId) throws Exception { return ok(mvc.perform(get("/play/"+sid+"/decisions/"+breakId)).andReturn()); }
    boolean selected(JsonNode d,UUID campaign) {
        for(JsonNode slot:d.path("slots")) if(slot.path("campaignId").asString().equals(campaign.toString())) return true;
        return false;
    }
    @Test void viewerAudienceTargetingCapsAndStableDecisionsAcrossSessions() throws Exception {
        String key=configureViewerKey(), subject="viewer-"+UUID.randomUUID(); Live live=live();
        String token=viewerToken(key,subject,"SPORTS",true,Instant.now().plusSeconds(600).getEpochSecond());
        String sid=ok(start(live,token)).path("sessionId").asString();
        JsonNode first=decision(sid,live.breakId()); assertTrue(selected(first,live.campaign()),first.toString());
        JsonNode channels=ok(call(get("/api/orgs/aurora/channels"),"owner@aurora.test",null));
        assertTrue(channels.size()>0,"channel summaries must render programme/ad-break metadata");
        Live otherChannel=live();
        assertEquals(404,mvc.perform(get("/play/"+sid+"/decisions/"+otherChannel.breakId())).andReturn().getResponse().getStatus());
        assertEquals(first.toString(),decision(sid,live.breakId()).toString(),"refresh must retain the committed decision");
        String second=ok(start(live,token)).path("sessionId").asString();
        JsonNode capped=decision(second,live.breakId()); assertFalse(selected(capped,live.campaign()));
        assertTrue(capped.path("excluded").toString().contains("frequency cap"));
        String contextual=ok(start(live,null)).path("sessionId").asString();
        assertFalse(selected(decision(contextual,live.breakId()),live.campaign()),"unsigned viewers must not enter an audience campaign");
        assertEquals(1,TenantScope.bypass(()->db.sql("select count(*) from ad_decision where session_id=:s").param("s",UUID.fromString(sid)).query(Integer.class).single()));
        assertEquals(0,TenantScope.bypass(()->db.sql("select count(*) from ad_event where session_id=:s and event='IMPRESSION'").param("s",UUID.fromString(sid)).query(Integer.class).single()),"a decision is not a billable impression");
    }
    @Test void forgedExpiredAndWithdrawnViewerContextCannotTargetAnAudience() throws Exception {
        String key=configureViewerKey(); Live live=live();
        assertEquals(403,start(live,viewerToken(key,"person","SPORTS",true,Instant.now().minusSeconds(1).getEpochSecond())).getResponse().getStatus());
        String token=viewerToken(key,"person","SPORTS",true,Instant.now().plusSeconds(600).getEpochSecond());
        assertEquals(403,start(live,token+"x").getResponse().getStatus());
        String denied=ok(start(live,viewerToken(key,"no-consent","SPORTS",false,Instant.now().plusSeconds(600).getEpochSecond()))).path("sessionId").asString();
        assertFalse(selected(decision(denied,live.breakId()),live.campaign()));
        String sid=ok(start(live,token)).path("sessionId").asString();
        assertEquals(204,mvc.perform(post("/play/"+sid+"/privacy/contextual")).andReturn().getResponse().getStatus());
        assertFalse(selected(decision(sid,live.breakId()),live.campaign()));
    }

    @Test void olderCampaignClientsKeepExistingAudienceControls() throws Exception {
        Live live=live();
        UUID creative=TenantScope.bypass(()->db.sql("select creative_asset_id from campaign where id=:id").param("id",live.campaign()).query(UUID.class).single());
        Map<String,Object> body=Map.of("name","Audience ad","advertiser","Advertiser-compatibility","creativeAssetId",creative.toString(),
                "startsAt",Instant.now().minusSeconds(86400).toString(),"endsAt",Instant.now().plusSeconds(86400).toString(),"territories",List.of("IN"),"priority",10,"cpm",10);
        ok(call(put("/api/orgs/aurora/campaigns/"+live.campaign()),"owner@aurora.test",body));
        assertEquals("SPORTS",TenantScope.bypass(()->db.sql("select audiences from campaign where id=:id").param("id",live.campaign()).query(String.class).single()));
        assertEquals(1,TenantScope.bypass(()->db.sql("select frequency_cap from campaign where id=:id").param("id",live.campaign()).query(Integer.class).single()));
        ok(call(post("/api/orgs/aurora/campaigns"),"owner@aurora.test",body));
        assertEquals(422,call(post("/api/orgs/aurora/campaigns"),"owner@aurora.test",Map.of()).getResponse().getStatus());
    }
}
