# Ad delivery per ad server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each VAST ad server connection chooses Store in R2 (default, now with a fast first version) or Direct from the ad server (its HLS played from its CDN), gated by the platform feature `AD_DIRECT_DELIVERY`.

**Architecture:** The backend decides per creative: Direct when the ad has HLS whose cached check fits the channel, else the existing R2 path (conditioned asset). Decision slots carry `delivery` and `hlsUrl`; the edge Worker swaps one ad piece per 5 s step exactly as for R2 ads, taking the piece URL from the ad's own playlist through the existing OVP reader. New R2 creatives publish one quality level first, then the full ladder.

**Tech Stack:** Java 21 / Spring Boot, PostgreSQL + Flyway, Cloudflare Worker (TypeScript, vitest), React/TypeScript, ffmpeg.

**Spec:** `docs/superpowers/specs/2026-11-10-ad-delivery-design.md`

## Global Constraints

- Feature key `AD_DIRECT_DELIVERY` (FEATURE, category Advertising, built in). Off unless the platform enables it.
- Connector property `vast.delivery`: SELECT, options `STORE_R2`, `DIRECT`; default `STORE_R2`; scope EXTERNAL.
- Direct only for HLS MediaFiles (`application/x-mpegURL`, `application/vnd.apple.mpegurl`); MP4-only ads use Store in R2.
- Fit rules: container matches the channel (`TS`/`FMP4`); FMP4 channels need a separate audio rendition; every piece
  except the last is 5 s ±0.1 s (a shorter last piece is dropped, as R2 ads drop their tail); the sum of all piece
  durations is within 0.5 s of the VAST duration; slot length = whole 5 s pieces × 5000 ms, ≥ 5000 and ≤ time left.
- Slot `delivery` values are `R2` / `DIRECT` (how this ad plays); connection `delivery` values are `STORE_R2` / `DIRECT` (the setting).
- Direct check cached per creative (`asset.source_ref`), re-checked after 24 h; played URL is always the current answer's.
- Outbound fetches go through `PublicUrl` (SSRF checks); https only for Direct URLs.
- Every task: `node tools/build-docs.mjs` when docs/API/DB change; new endpoints/fields in `docs/api/api.json`;
  every SQL statement names the organization or carries `/* all tenants: reason */` (`node tools/check-tenant-filters.mjs`).
- No push or merge without the user's explicit approval (CLAUDE.md).

## Review Focus

1. Ad server answers with both MP4 and HLS while the creative's direct check is still running: the break must still fill (R2 path or fast first version), never stall or leave a hole. → Task 4 test `firstSightOfDirectAdUsesStoreAndStartsCheck`.
2. A tenant whose feature is later switched off keeps `delivery = DIRECT` saved: decisions must treat it as STORE_R2. → Task 1 test `directWithoutFeatureIsReadAsStore`.
3. The ad's HLS playlist cannot be read by the Worker when the playlist is built (CDN down, 403 on signed URL): those steps play slate, no 404 piece is listed. → Task 5 test `unreadDirectAdPlaysSlate`.
4. Channel switches container (TS ↔ FMP4) between decision and break: the slot was fitted to the old format; the Worker must not list mismatched pieces. → Task 5 test `directSlotOfOtherContainerPlaysSlate`.
5. A creative whose fast first version is READY at one level while a channel level is higher: playback uses the nearest lower level (existing `levelFolder`), and the full-ladder job replaces it without a gap. → Task 6 test `firstVersionIsOneLevelThenFullLadderIsQueued`.

---

### Task 1: Feature, connector property and config plumbing

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__ad_direct_delivery.sql`
- Modify: `backend/src/main/java/com/faststudio/v1/core/common/Feature.java`
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/sources/IAdSource.java` (Config)
- Modify: `backend/src/main/java/com/faststudio/v1/repositories/ads/AdSourceRepository.java` (waterfall, insert, update)
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/AdSourceAdminService.java` (AdSourceRequest, add, update)
- Modify: `backend/src/main/java/com/faststudio/v1/services/connectors/ConnectorCatalogueServiceV1.java` (value save)
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/AdDecisionServiceV1.java` (read gate)
- Test: `backend/src/test/java/com/faststudio/v1/services/ads/AdDirectDeliverySettingTest.java`

**Interfaces:**
- Produces: `Feature.AD_DIRECT_DELIVERY`; `IAdSource.Config(..., String managedBy, String delivery)` with `delivery` ∈ {`STORE_R2`,`DIRECT`} (keep an 8-argument constructor defaulting `STORE_R2`); `AdSourceRequest(..., boolean enabled, String delivery)` (null → `STORE_R2`); `AdDecisionServiceV1.effectiveDelivery(UUID org, IAdSource.Config c): String`.
- Migration V37: capability row `AD_DIRECT_DELIVERY`; `connector_property ('vast','delivery','Delivery','Store in R2 and serve, or play the ad server''s own HLS from its CDN (needs the Direct ad delivery feature)','SELECT', options '["STORE_R2","DIRECT"]', position 3, required false, encrypted false, 'EXTERNAL', default 'STORE_R2')`; `alter table asset add column direct_check jsonb, add column direct_checked_at timestamptz`.

- [ ] **Step 1: Write the failing tests** (Spring test with the embedded DB, like `AdDecisionServiceTest`)

```java
@Test void savingDirectWithoutFeatureIsRejected() {
    // tenant without AD_DIRECT_DELIVERY, AdSourceAdminService.add(... delivery "DIRECT")
    ApiException e = assertThrows(ApiException.class, () -> admin.add(org, actor, request("DIRECT"), "TENANT"));
    assertEquals(403, e.status().value()); assertEquals("FEATURE_OFF", e.code());
}
@Test void directIsSavedAndReadWhenFeatureIsOn() {
    enableFeature(org, "AD_DIRECT_DELIVERY");
    UUID id = admin.add(org, actor, request("DIRECT"), "TENANT");
    assertEquals("DIRECT", repository.waterfall(org).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().delivery());
}
@Test void existingConnectionsDefaultToStore() { assertEquals("STORE_R2", repository.waterfall(org).getFirst().delivery()); }
@Test void directWithoutFeatureIsReadAsStore() {
    // saved DIRECT, then feature switched off
    assertEquals("STORE_R2", decisions.effectiveDelivery(org, configWith("DIRECT")));
}
@Test void connectorValueDirectIsRejectedWithoutFeature() { /* ConnectorCatalogueServiceV1 value save for key 'delivery' = DIRECT → 403 FEATURE_OFF */ }
```

- [ ] **Step 2: Run to verify they fail** — `cd backend && ./mvnw -q test -Dtest=AdDirectDeliverySettingTest` → compile errors (no `delivery`).
- [ ] **Step 3: Implement** migration V37, `Feature.AD_DIRECT_DELIVERY("Ad server connections may play the ad server's own HLS directly (Direct delivery)")`, `delivery` in Config/request/repository (`put(org, id, "delivery", ...)`, waterfall left-joins `connector_value d on property_key = 'delivery'`, `coalesce(d.value,'STORE_R2')`), the feature check in `AdSourceAdminService.add/update` and in the connector value save when connector driver is VAST and key is `delivery` (both throw `ApiException(FORBIDDEN, "FEATURE_OFF", "This isn't switched on for your company. Ask the platform administrator.")`), and `effectiveDelivery` (DIRECT only if the org has the feature).
- [ ] **Step 4: Run** the test class and `AdDecisionServiceTest`, `VastSourceTest`, `RowLevelSecurityTest`; `node tools/check-tenant-filters.mjs` → all pass.
- [ ] **Step 5: Docs** — `docs/api/api.json`: `delivery` on ad source request/response; `node tools/build-docs.mjs`.
- [ ] **Step 6: Commit** `feat(ads): Direct ad delivery feature and per-ad-server delivery setting`

### Task 2: HLS MediaFile in VAST answers

**Files:**
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/sources/VastSource.java` (`Ad`, `fetch`, new `pickHls`)
- Test: `backend/src/test/java/com/faststudio/v1/services/ads/sources/VastSourceTest.java`

**Interfaces:**
- Produces: `VastSource.Ad(String title, String advertiser, long durationMs, String mediaUrl, String ref, Map<String,List<String>> tracking, String hlsUrl)` (keep the 6-argument constructor, `hlsUrl` null); `static String pickHls(Element linear)` — first MediaFile whose `type` is `application/x-mpegURL` or `application/vnd.apple.mpegurl` (case-insensitive) with an https URL, else null.
- An ad with only HLS (no MP4) is kept: `mediaUrl` null, `hlsUrl` set; `ref` falls back to the HLS URL path. `fill` treats a null `mediaUrl` under STORE_R2 as "No MP4 file to prepare" (excluded).

- [ ] **Step 1: Failing tests**

```java
@Test void hlsMediaFileIsReadBesideMp4() {
    Ad ad = parseOne(vast(mp4("https://cdn/a.mp4", 720), hls("https://cdn/a/master.m3u8")));
    assertEquals("https://cdn/a.mp4", ad.mediaUrl()); assertEquals("https://cdn/a/master.m3u8", ad.hlsUrl());
}
@Test void hlsOnlyAdIsKeptWithoutMp4() { Ad ad = parseOne(vast(hls("https://cdn/b/master.m3u8"))); assertNull(ad.mediaUrl()); assertNotNull(ad.hlsUrl()); }
@Test void plainHttpHlsIsIgnored() { assertNull(parseOne(vast(mp4("https://cdn/a.mp4", 720), hls("http://cdn/a/master.m3u8"))).hlsUrl()); }
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=VastSourceTest` → FAIL (no `hlsUrl`).
- [ ] **Step 3: Implement** `pickHls`, the `Ad` field and the `fetch` change (skip an ad only when both URLs are null or duration ≤ 0).
- [ ] **Step 4: Run** `VastSourceTest`, `AdDecisionServiceTest` → PASS.
- [ ] **Step 5: Commit** `feat(ads): read HLS media files from VAST answers`

### Task 3: Direct check (profile of an ad's HLS) and fit rules

**Files:**
- Create: `backend/src/main/java/com/faststudio/v1/services/ads/sources/DirectAdCheck.java`
- Modify: `backend/src/main/java/com/faststudio/v1/repositories/ads/AdSourceRepository.java` (read/write `direct_check`)
- Test: `backend/src/test/java/com/faststudio/v1/services/ads/sources/DirectAdCheckTest.java`

**Interfaces:**
- Produces:
  - `record Profile(String container /* TS|FMP4 */, boolean separateAudio, List<Integer> heights, List<Double> pieces /* durations of the first variant */)`
  - `static Profile parse(String master, Function<String,String> fetchByAbsoluteUrl)` — pure given the fetcher; container FMP4 when the variant has `#EXT-X-MAP`, else TS; separateAudio when the master has `#EXT-X-MEDIA:TYPE=AUDIO` with a URI.
  - `static long slotMs(Profile p)` — count of leading pieces within 5.0 ±0.1 s, × 5000 (a shorter last piece dropped; any other off-length piece → 0).
  - `static String misfit(Profile p, String channelFmt, long vastDurationMs, long leftMs)` — null when it fits, else one of: `"Direct not possible: pieces are not 5 s"`, `"Direct not possible: TS/FMP4 container differs from the channel"`, `"Direct not possible: no separate audio track"`, `"Direct not possible: shorter than one 5 s piece"`, `"Direct not possible: length differs from the ad server's"`, `"Direct not possible: doesn't fit the time left"`.
  - `Profile check(String masterUrl) throws IOException` — fetches via `PublicUrl` with the VAST source's `HttpClient`, 5 s timeout.
  - Repository: `Optional<DirectCheck> directCheck(UUID org, String ref)` / `void saveDirectCheck(UUID org, String ref, Profile p)`; `record DirectCheck(Profile profile, Instant checkedAt)`.

- [ ] **Step 1: Failing tests** (playlist fixtures as strings)

```java
@Test void fmp4WithSeparateAudioAndFivesecondPiecesFits() {
    Profile p = DirectAdCheck.parse(MASTER_FMP4_AUDIO, FIXTURES::get);
    assertEquals("FMP4", p.container()); assertTrue(p.separateAudio()); assertEquals(30_000, DirectAdCheck.slotMs(p));
    assertNull(DirectAdCheck.misfit(p, "FMP4", 30_000, 60_000));
}
@Test void shortLastPieceIsDropped() { assertEquals(30_000, DirectAdCheck.slotMs(profile(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 2.3))); }
@Test void sixSecondPiecesDoNotFit() { assertEquals("Direct not possible: pieces are not 5 s", DirectAdCheck.misfit(profile(6.0, 6.0, 6.0), "TS", 18_000, 60_000)); }
@Test void tsAdOnFmp4ChannelDoesNotFit() { /* container message */ }
@Test void muxedAudioOnFmp4ChannelDoesNotFit() { /* "no separate audio track" */ }
@Test void longerThanLeftDoesNotFit() { assertEquals("Direct not possible: doesn't fit the time left", DirectAdCheck.misfit(profile(5,5,5,5), "TS", 20_000, 15_000)); }
@Test void checkIsSavedAndReadBack() { /* repository round trip for (org, ref) */ }
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DirectAdCheckTest` → FAIL.
- [ ] **Step 3: Implement** `DirectAdCheck` (Spring `@Component`, reuses VastSource's `HttpClient` bean) and the repository methods (`update asset set direct_check = cast(:j as jsonb), direct_checked_at = now() where org_id = :o and source_ref = :r`; creates no asset row — the check is saved on the creative's asset, inserted by the existing `insertCreative` when missing).
- [ ] **Step 4: Run** → PASS; `node tools/check-tenant-filters.mjs` → OK.
- [ ] **Step 5: Commit** `feat(ads): check an ad's HLS for direct playout`

### Task 4: Decide Direct or R2 per creative

**Files:**
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/sources/VastSource.java` (`fill`)
- Modify: `backend/src/main/java/com/faststudio/v1/services/ads/AdDecisionServiceV1.java` (`Slot`)
- Modify: `backend/src/main/java/com/faststudio/v1/util/cloudflare/EdgePublisher.java` (channel format)
- Test: `backend/src/test/java/com/faststudio/v1/services/ads/sources/VastSourceTest.java`, `AdDecisionServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `Config.delivery()`, `effectiveDelivery`; Task 2 `Ad.hlsUrl()`; Task 3 `DirectAdCheck`, repository `directCheck/saveDirectCheck`.
- Produces: `Slot(..., List<Integer> levels, String delivery /* R2|DIRECT */, String hlsUrl)` — existing constructors default `R2`, null. DIRECT slots: `mediaKey` null, `durationMs = slotMs(profile)`, `source = src.name() + " · Direct"`, `campaignId` = the creative asset id. `EdgePublisher.channelFormat(UUID org, String channelSlug): String` — `fmt` of the last snapshot sent for that channel, `"TS"` when none.
- Behaviour in `fill` when `delivery = DIRECT` and `ad.hlsUrl() != null`: cached check younger than 24 h → `misfit(...)` null → DIRECT slot; misfit → R2 path with excluded note = the misfit reason only if the R2 copy is also unavailable (otherwise just the R2 slot, reason logged at debug); no/old check → start `check` on a single-thread executor (one in flight per ref), save the result, and use the R2 path for this answer.

- [ ] **Step 1: Failing tests**

```java
@Test void fittingHlsAdBecomesDirectSlot() {
    givenCheck(ref, fmp4Profile(30_000)); givenChannelFormat("FMP4");
    Slot s = only(fill(directSource(), answerWithHls()));
    assertEquals("DIRECT", s.delivery()); assertNull(s.mediaKey()); assertEquals(30_000, s.durationMs());
    assertEquals("https://cdn/a/master.m3u8", s.hlsUrl()); assertTrue(s.source().endsWith(" · Direct"));
}
@Test void firstSightOfDirectAdUsesStoreAndStartsCheck() {
    Fill f = fill(directSource(), answerWithHlsAndMp4());
    assertTrue(f.slots().isEmpty() || "R2".equals(f.slots().getFirst().delivery()));
    verify(checker, timeout(2000)).check("https://cdn/a/master.m3u8");
}
@Test void mp4OnlyAdUnderDirectUsesStore() { /* no hlsUrl → R2 path, creative enqueued as today */ }
@Test void misfitFallsBackToR2WhenReady() { givenCheck(ref, tsProfile(6.0)); givenReadyR2Creative(ref); assertEquals("R2", only(fill(directSource(), answerWithHlsAndMp4())).delivery()); }
@Test void storeSourceNeverPlaysDirect() { givenCheck(ref, fmp4Profile(30_000)); assertNotEquals("DIRECT", only(fill(storeSource(), answerWithHlsAndMp4())).delivery()); }
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=VastSourceTest,AdDecisionServiceTest` → FAIL.
- [ ] **Step 3: Implement** the `Slot` fields (JSON names `delivery`, `hlsUrl`), `channelFormat` (store `fmt` per `org/channel` key alongside `sent`), and the `fill` branch.
- [ ] **Step 4: Run** the full backend suite `./mvnw -q clean test` → 0 failures.
- [ ] **Step 5: Commit** `feat(ads): play fitting HLS ads directly from the ad server`

### Task 5: Worker plays DIRECT slots

**Files:**
- Modify: `worker/src/playout.ts` (`Slot`, `grid`, `mediaPlaylist` audio, `assetList`)
- Modify: `worker/src/ovp.ts` (ad reads)
- Modify: `worker/src/index.ts` (preload direct slots of decided breaks)
- Test: `worker/src/playout.test.ts`, `worker/src/ovp.test.ts`

**Interfaces:**
- Consumes: decision JSON slots with `delivery`, `hlsUrl` (Task 4).
- Produces: `Slot = { campaignId; campaign; mediaKey: string | null; durationMs; levels?; delivery?: 'R2' | 'DIRECT'; hlsUrl?: string }`; `ovp.loadAd(hlsUrl: string, variant: number, rendition: Rendition | null): Promise<void>` and `ovp.adSegment(hlsUrl: string, variant: number, n: number): { url: string; map?: string } | null` (same memo/Cache API as programmes, keyed by URL + variant; `variant === AUDIO` reads the audio rendition); `resolver(..., adSeg = ovp.adSegment)`.
- Rules in `grid`: DIRECT slot, piece `n = floor(off / SEG)`; `adSeg(hlsUrl, variant, n)` null → standby; snapshot `fmt` FMP4 with layout split → audio playlist lists the ad's AUDIO piece n; container mismatch is detected from the read playlist (has `map` ⇔ FMP4) and plays standby.

- [ ] **Step 1: Failing tests**

```ts
it('lists the ad server piece for a DIRECT slot, with DISCONTINUITY around it', () => {
  const text = mediaPlaylist(snapTs, session, 0, breakStart + 1000, base, decidedDirect, adSeg)
  expect(text).toMatch(/#EXT-X-DISCONTINUITY\n#EXTINF:5\.000,?\nhttps:\/\/ads\.cdn\/a\/v720\/seg0\.ts/)
})
it('unreadDirectAdPlaysSlate', () => { /* adSeg returns null → standby slate url in that step */ })
it('directSlotOfOtherContainerPlaysSlate', () => { /* fmt FMP4 snapshot, adSeg without map → slate */ })
it('fmp4 split channel lists the ad audio piece in the audio playlist', () => { /* variant AUDIO */ })
it('SGAI asset list uses the ad HLS URL for DIRECT slots', () => { expect(assetList(...).ASSETS[0].URI).toBe('https://ads.cdn/a/master.m3u8') })
// ovp.test.ts
it('adSegment returns piece n of the variant nearest our level after loadAd', async () => { /* fetch mocked */ })
```

- [ ] **Step 2: Run** `cd worker && npx vitest run` → FAIL.
- [ ] **Step 3: Implement** the type, `loadAd`/`adSegment` (reuse `read`, `pick`, `parseAudio`), the `grid` branch, audio handling, `assetList`, and preloading in `index.ts` where streamed programmes are loaded (`breaksToDecide` decisions with DIRECT slots).
- [ ] **Step 4: Run** `npx vitest run` and `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(worker): play DIRECT ad slots from the ad server's HLS`

### Task 6: Fast first version for R2 creatives

**Files:**
- Modify: `backend/src/main/java/com/faststudio/v1/services/media/MediaServiceV1.java` (`pack` levels)
- Modify: `backend/src/main/java/com/faststudio/messaging/v1/media/MediaJobObserverV1.java` (queue full ladder after publish)
- Test: `backend/src/test/java/com/faststudio/v1/services/media/MediaPruneTest.java`, `backend/src/test/java/com/faststudio/messaging/v1/media/MediaJobObserverTest.java` (create if absent)

**Interfaces:**
- Produces: `static List<QualityLevels.Level> firstLevels(ProcessingJob j, List<QualityLevels.Level> project)` — when `"AD".equals(j.kind()) && j.sourceRef() != null && j.mediaKey() == null`: the single highest project level with height ≤ 720, else the lowest project level; otherwise `project`. After a successful publish of such an AD asset, if `media.levels(newKey).size() < qualities.of(org).size()`, `jobs.enqueue(org, assetId, "AD")`.

- [ ] **Step 1: Failing tests**

```java
@Test void firstVersionIsOneLevelThenFullLadderIsQueued() {
    var project = QualityLevels.parse("360p,540p,720p,1080p");
    assertEquals(List.of("720p"), MediaServiceV1.firstLevels(adJob(null), project).stream().map(QualityLevels.Level::name).toList());
    assertEquals(project, MediaServiceV1.firstLevels(adJob("acme/ad.1"), project));      // second run: full ladder
    assertEquals(project, MediaServiceV1.firstLevels(uploadedAdJob(), project));          // our own uploads: unchanged
}
@Test void publishOfOneLevelCreativeQueuesFullLadder() { /* observer: publish → jobs.enqueue(org, asset, "AD") called once */ }
@Test void publishOfFullLadderQueuesNothing() { /* levels == project levels → no enqueue */ }
```

- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** `firstLevels` used in `pack` instead of `qualities.of(org)`, and the observer follow-up.
- [ ] **Step 4: Run** the full backend suite → 0 failures. Time one 30 s creative locally (ffmpeg): record first-version and full-ladder seconds in the PROGRESS handoff.
- [ ] **Step 5: Commit** `feat(media): new ad server creatives publish one quality level first`

### Task 7: Re-package without re-encoding when keyframes allow

**Files:**
- Modify: `backend/src/main/java/com/faststudio/v1/services/media/sources/RemoteSource.java`, `MediaServiceV1.java` (copy path)
- Test: `backend/src/test/java/com/faststudio/v1/services/media/MediaPruneTest.java`

**Interfaces:**
- Produces: `boolean copyable(Path source)` — ffprobe: video `h264`, audio `aac`, every video keyframe time within 0.05 s of a multiple of 5 s up to the last whole 5 s; `String copyFirst(Path source, Path out, QualityLevels.Level level)` — `-c copy -f hls -hls_time 5` into the level folder plus the fMP4 split (existing `fmp4(out)`), loudness untouched (documented limitation). Used only when `firstLevels` returned one level.

- [ ] **Step 1: Failing tests** — ffmpeg-made fixtures (skip without ffmpeg): `-g 125` 25 fps H.264/AAC → `copyable` true and the level playlist has `#EXTINF:5.0` pieces; `-g 48` → false.
- [ ] **Step 2: Run** → FAIL.  **Step 3: Implement.**  **Step 4: Run** full suite → PASS.
- [ ] **Step 5: Commit** `feat(media): re-package aligned ad creatives without re-encoding`

### Task 8: Screens

**Files:**
- Modify: `frontend/src/shared/configuration/ConnectorPanel.tsx` (hide `delivery` without the feature; option labels)
- Modify: `frontend/src/features/ads/Ads.tsx` (`AdSources` form: Delivery select; list badge "Direct")
- Modify: `frontend/src/features/ads/adTypes.ts`
- Test: `frontend/e2e/ad-delivery.spec.ts`

**Interfaces:**
- Labels: `STORE_R2` → "Store in R2 and serve", `DIRECT` → "Direct from the ad server". The select shows only when `org.features.includes('AD_DIRECT_DELIVERY')`.

- [ ] **Step 1: Failing e2e** — platform admin enables `AD_DIRECT_DELIVERY` for the demo tenant; owner opens Ads → ad sources, sets a VAST source to "Direct from the ad server", saves, reloads, sees it selected and a "Direct" badge; with the feature off the select is absent.
- [ ] **Step 2: Run** against an isolated demo copy (`E2E_BASE_URL=http://127.0.0.1:8191 npx playwright test e2e/ad-delivery.spec.ts`) → FAIL.
- [ ] **Step 3: Implement.**  **Step 4: Run** all e2e + `npx tsc -b` + `npx vite build` → PASS.
- [ ] **Step 5: Commit** `feat(ads-ui): delivery choice per ad server`

### Task 9: End-to-end check, docs and handoff

**Files:**
- Create (ignored): `data/runtime/fake-ad-server.mjs` — serves a VAST answer with an HLS ad (TS and fMP4-split fixtures made with ffmpeg at 5 s pieces) and one MP4-only answer.
- Modify: `docs/features/features.json` (new feature `ad-delivery` with animated flow, scenarios, screenshots recipe), `docs/faq/faq.json` (`ad-delivery-r2-or-direct` → built status), `docs/api/api.json`, `PROGRESS.md`.

- [ ] **Step 1:** Isolated demo copy (backend 8191, Worker 8187, never the pilot) with a VAST source pointing at the fake ad server, feature on, delivery DIRECT.
- [ ] **Step 2:** Watch plays a break with the direct ad: the playlist shows ad-server piece URLs; stall probe (`data/runtime/stall-probe.mjs` against the copy) shows 0 stalls through two breaks on a TS and an fMP4 channel.
- [ ] **Step 3:** MP4-only answer under DIRECT plays via R2; a brand-new MP4 creative airs in its first break (log the time from first sight to READY and the break start).
- [ ] **Step 4:** Feature off → the same source plays via R2.
- [ ] **Step 5:** Record how many real Google/VAST answers from the pilot's sources carry HLS and their piece lengths (read-only tag tester), in PROGRESS.
- [ ] **Step 6:** `node tools/build-docs.mjs`, docs screenshots with `npm run docs:shots` against the copy; update PROGRESS (row, verification, handoff, next action).
- [ ] **Step 7: Commit** `docs: ad delivery per ad server` — then ask the user before any push.
