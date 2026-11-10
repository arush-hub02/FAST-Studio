package com.faststudio.v1.services.ads.sources;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VastSourceTest {
    static final String POD = """
            <VAST version="4.0">
              <Ad id="b" sequence="2"><InLine><AdSystem>SpringServe</AdSystem><AdTitle>Second</AdTitle>
                <Impression><![CDATA[https://t.example/imp2]]></Impression>
                <Creatives><Creative id="cb"><Linear><Duration>00:00:15</Duration><MediaFiles>
                  <MediaFile type="video/mp4" height="1080">https://cdn.example/b1080.mp4</MediaFile>
                  <MediaFile type="video/mp4" height="360">https://cdn.example/b360.mp4</MediaFile>
                  <MediaFile type="video/mp4" height="720">https://cdn.example/b720.mp4</MediaFile>
                </MediaFiles>
                <TrackingEvents><Tracking event="midpoint">https://t.example/mid2</Tracking><Tracking event="mute">https://t.example/x</Tracking></TrackingEvents>
                </Linear></Creative></Creatives></InLine></Ad>
              <Ad id="a" sequence="1"><InLine><AdSystem>GAM</AdSystem><Advertiser>Glow Cola</Advertiser><AdTitle>First</AdTitle>
                <Creatives><Creative><Linear><Duration>00:00:30.000</Duration><MediaFiles>
                  <MediaFile type="application/javascript">https://cdn.example/vpaid.js</MediaFile>
                  <MediaFile type="video/mp4" height="1080">https://cdn.example/a1080.mp4</MediaFile>
                </MediaFiles></Linear></Creative></Creatives></InLine></Ad>
            </VAST>""";

    static List<Element> pod() throws IOException {
        return VastSource.ads(VastSource.parse(POD.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void podPlaysInSequenceOrder() throws IOException {
        assertEquals(List.of("a", "b"), pod().stream().map(e -> e.getAttribute("id")).toList());
    }

    @Test
    void picksTheMp4ClosestTo720p() throws IOException {
        List<Element> ads = pod();
        assertEquals("https://cdn.example/b720.mp4", VastSource.pickMedia(VastSource.first(ads.get(1), "Linear")));
        assertEquals("https://cdn.example/a1080.mp4", VastSource.pickMedia(VastSource.first(ads.get(0), "Linear")));   // only one mp4
    }

    @Test
    void trackingMapsVastEventsToOurs() throws IOException {
        var t = VastSource.tracking(VastSource.first(pod().get(1), "InLine"));
        assertEquals(List.of("https://t.example/imp2"), t.get("IMPRESSION"));
        assertEquals(List.of("https://t.example/mid2"), t.get("MIDPOINT"));
        assertFalse(t.containsKey("mute"));
    }

    @Test
    void creativesAreKeyedByAdIdNotBySignedUrl() throws IOException {
        List<Element> ads = pod();
        Element a = ads.get(0), b = ads.get(1);
        assertEquals("SpringServe|creative:cb", VastSource.ref(b, VastSource.first(b, "InLine"), VastSource.first(b, "Linear"), "https://cdn.example/b720.mp4?sig=1"));
        String google = """
                <VAST version="3.0"><Ad id="5925573263"><InLine><AdSystem>GDFP</AdSystem><Creatives>
                  <Creative id="138381721867" AdID="H0Hrk8zCNZI"><UniversalAdId idRegistry="googlevideo">H0Hrk8zCNZI</UniversalAdId><Linear/></Creative>
                </Creatives></InLine></Ad></VAST>""";
        Element g = VastSource.ads(VastSource.parse(google.getBytes(StandardCharsets.UTF_8))).get(0);
        assertEquals("GDFP|googlevideo:H0Hrk8zCNZI", VastSource.ref(g, VastSource.first(g, "InLine"), VastSource.first(g, "Linear"), "https://x/a.mp4?cpn=random"));
        assertEquals("GAM|ad:a", VastSource.ref(a, VastSource.first(a, "InLine"), VastSource.first(a, "Linear"), "u"));
    }

    static VastSource.Ad one(String mediaFiles) throws IOException {
        String xml = """
                <VAST version="4.0"><Ad id="h"><InLine><AdSystem>GAM</AdSystem><AdTitle>Hls</AdTitle>
                  <Creatives><Creative id="ch"><Linear><Duration>00:00:30</Duration><MediaFiles>%s</MediaFiles></Linear></Creative></Creatives>
                </InLine></Ad></VAST>""".formatted(mediaFiles);
        Element ad = VastSource.ads(VastSource.parse(xml.getBytes(StandardCharsets.UTF_8))).getFirst();
        return VastSource.toAd(ad, VastSource.first(ad, "InLine"), java.util.Map.of());
    }

    @Test
    void hlsMediaFileIsReadBesideMp4() throws IOException {
        var ad = one("""
                <MediaFile type="video/mp4" height="720">https://cdn.example/a.mp4</MediaFile>
                <MediaFile type="application/x-mpegURL">https://cdn.example/a/master.m3u8</MediaFile>""");
        assertEquals("https://cdn.example/a.mp4", ad.mediaUrl());
        assertEquals("https://cdn.example/a/master.m3u8", ad.hlsUrl());
    }

    @Test
    void hlsOnlyAdIsKeptWithoutMp4() throws IOException {
        var ad = one("<MediaFile type=\"application/vnd.apple.mpegurl\">https://cdn.example/b/master.m3u8</MediaFile>");
        assertNotNull(ad);
        assertNull(ad.mediaUrl());
        assertEquals("https://cdn.example/b/master.m3u8", ad.hlsUrl());
        assertEquals("GAM|creative:ch", ad.ref(), "keyed by the creative, not the HLS address");
    }

    @Test
    void plainHttpHlsIsIgnored() throws IOException {
        var ad = one("""
                <MediaFile type="video/mp4" height="720">https://cdn.example/a.mp4</MediaFile>
                <MediaFile type="application/x-mpegURL">http://cdn.example/a/master.m3u8</MediaFile>""");
        assertNull(ad.hlsUrl());
    }

    @Test
    void hlsOnlyAdUnderStoreIsExcludedWithoutPreparing() {
        var repository = org.mockito.Mockito.mock(com.faststudio.v1.repositories.ads.AdSourceRepository.class);
        var source = new VastSource(repository, null, null);
        var src = new IAdSource.Config(UUID.randomUUID(), "GAM", "VAST", "https://x", 1000, 1, true, "PLATFORM");
        var req = new IAdSource.Request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 60_000, 60_000, "IN",
                "ch", "", null, IAdSource.Viewer.NONE, new HashSet<>());
        var ad = new VastSource.Ad("Hls", "GAM", 30_000, null, "GAM|creative:ch", java.util.Map.of(), "https://cdn.example/b/master.m3u8");
        var fill = source.place(src, req, List.of(ad));
        assertTrue(fill.slots().isEmpty());
        assertEquals("No MP4 file to prepare", fill.excluded().getFirst().reason());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void adWithNeitherFileIsNotPlayable() throws IOException {
        assertNull(one("<MediaFile type=\"application/javascript\">https://cdn.example/vpaid.js</MediaFile>"));
    }

    @Test
    void durations() {
        assertEquals(30_000, VastSource.duration("00:00:30.000"));
        assertEquals(3_723_500, VastSource.duration("01:02:03.5"));
        assertEquals(0, VastSource.duration("30"));
    }

    @Test
    void macrosAreFilledAndEncoded() {
        var req = new IAdSource.Request(UUID.randomUUID(), null, null, Instant.EPOCH, 30_000, 25_000, "IN", "news", "Sports & Games",
                null, new IAdSource.Viewer("203.0.113.9", "Smart TV/1.0"), new HashSet<>());
        String url = VastSource.expand("https://ads.example/v?d=[POD_DURATION]&ch=[CHANNEL_ID]&ua=[USER_AGENT]&g=[CONTENT_GENRE]&ip=[CLIENT_IP]", req);
        assertEquals("https://ads.example/v?d=25&ch=news&ua=Smart+TV%2F1.0&g=Sports+%26+Games&ip=203.0.113.9", url);
    }

    @Test
    void refusesDoctypes() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE v [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><VAST>&x;</VAST>";
        assertThrows(IOException.class, () -> VastSource.parse(xxe.getBytes(StandardCharsets.UTF_8)));
    }
}
