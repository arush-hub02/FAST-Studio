# How FAST works — primer for the team

Revision 3 — 20 October 2026. Read this before writing prototype code. It explains the product category, the media path, ad insertion models and SCTE-35 at the depth needed to build the [prototype](IMPLEMENTATION_ROADMAP.md). Vendor and device support statements are dated; re-verify at kickoff.

## 1. FAST — what and why

**FAST = Free Ad-Supported Streaming Television.** A linear channel streamed over the internet: one continuous programme schedule, the same for every viewer at the same moment, paid for by ads. A viewer tunes in and something is already playing. They don't pick titles (that's AVOD) and they don't pay (that's SVOD).

| Model | Viewer pays | Viewer picks | Example shape |
| --- | --- | --- | --- |
| SVOD | Subscription | Yes, on demand | Netflix-style library |
| AVOD | Free, with ads | Yes, on demand | Free on-demand library with pre/mid-rolls |
| **FAST** | Free, with ads | **No — scheduled linear** | "Classic Cinema" channel in a TV's channel guide |
| Broadcast/cable | Licence or subscription | No | Traditional TV |

**Why it exists — every party gains:**

- **Content owners** earn money from long-tail library content that has no other outlet. A 1990s detective series can run as a 24/7 channel.
- **Distribution platforms** (smart-TV makers, aggregators, streaming devices) fill their built-in channel guides with free content and sell or share the ad inventory.
- **Viewers** get free lean-back TV with no subscription and no decision fatigue.
- **Cost:** most FAST channels are *virtual linear*. Pre-encoded on-demand files are stitched together by schedule, so there's no 24/7 live encoder. That makes running a channel cheap enough for niche content.

**How money moves:** the channel operator (our customer) delivers a stream, an EPG and ad-break markers to a platform. The two parties split the ad breaks or the ad revenue under contract:

- **Inventory split:** each party sells a share of the break minutes. SCTE-35 marks which breaks belong to whom (see §4.4).
- **Revenue share:** one party sells everything and shares the proceeds.
- **Hybrid:** a mix of the two.

Each platform publishes its own ad-load limits, marker format and EPG format. FAST Studio models these as a *destination profile* (F42).

## 2. End-to-end media path

```
 Mezzanine file (e.g. ProRes/MP4)
   │ 1. Ingest + QC
   ▼
 Transcode → ABR ladder (e.g. 1080p/720p/480p) → package HLS/CMAF segments (VOD)
   │      keyframes forced at cue points so breaks can splice cleanly
   ▼
 2. Schedule (published version) + ad pods + cue points
   │
   ▼
 3. Channel assembly / virtual-linear playout
   │   builds a LIVE sliding-window playlist that points at VOD segments in
   │   schedule order, adds EXT-X-DISCONTINUITY at asset joins and SCTE-35
   │   markers at every break
   ▼
 4. Ad insertion  ── SSAI: server swaps break segments for ad segments per viewer
   │              ── SGAI: server adds an "interstitial" pointer; player fetches ads
   │              ── or pass markers through untouched to a platform that inserts its own ads
   ▼
 5. Origin → CDN → viewer player / distribution platform ingest (+ EPG feed)
   │
   ▼
 6. Measurement: ad beacons/impressions, viewing → reports → reconciliation
```

**Virtual linear vs real playout.** Virtual linear (manifest manipulation) needs no live encoder. It can't burn in graphics, logos or tickers, and it can't do frame-accurate live switching. Real playout decodes and re-encodes a continuous live signal. That supports overlays and live inputs but costs a lot more. **The prototype builds virtual linear**: it's the dominant FAST pattern, and a Java service can do it without any media provider.

### 2.1 How a virtual-linear HLS playlist is computed

Every asset is pre-segmented with the same encoding (codec, profile, resolution ladder, timescale) and fixed-length segments, for example 6 s.

1. **Channel epoch:** each channel has a fixed `epoch` instant. The published schedule maps wall-clock time to `(asset, offset)`.
2. **Find the live edge:** on each playlist request, find which segment is playing at `now − liveDelay` (for example 3 segments behind).
3. **Emit a window:** output the last N segments (for example 10 × 6 s = 60 s window).
4. **`#EXT-X-MEDIA-SEQUENCE`:** the absolute index of the first segment in the window, counted from the epoch. It has to be deterministic and only ever go up. Two requests one second apart must agree, or players stall or skip.
5. **`#EXT-X-DISCONTINUITY`:** goes before the first segment of each new asset, ad or slate.
6. **`#EXT-X-DISCONTINUITY-SEQUENCE`:** counts the discontinuities that have slid out of the window.
7. **`#EXT-X-PROGRAM-DATE-TIME`:** ties each segment to wall-clock time, so EPG entries, ad beacons and as-run logs line up with the stream.
8. **Cue points:** asset cue points are snapped to segment boundaries. A break can only start at a keyframe or segment edge.

Example window crossing from a movie into an ad break (SSAI off, slate in the break):

```m3u8
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:184213
#EXT-X-DISCONTINUITY-SEQUENCE:3071
#EXT-X-PROGRAM-DATE-TIME:2026-10-20T14:29:48.000Z
#EXTINF:6.000,
/media/org-aurora/asset-sintel/720p/seg_00412.ts
#EXTINF:6.000,
/media/org-aurora/asset-sintel/720p/seg_00413.ts
#EXT-X-DATERANGE:ID="brk-7f3a-1",CLASS="urn:fast-studio:ad-break",START-DATE="2026-10-20T14:30:00.000Z",PLANNED-DURATION=120.0,SCTE35-OUT=0xFC30...
#EXT-X-DISCONTINUITY
#EXTINF:6.000,
/media/platform/slate/720p/seg_00000.ts
...
```

## 3. Ad insertion: CSAI vs SSAI vs SGAI

All three start from the same thing: an **ad opportunity**, described as a break of N seconds at time T, signalled by SCTE-35. They differ in *who stitches the ad into the viewing experience*.

| | CSAI (client-side) | **SSAI** (server-side) | **SGAI** (server-guided) |
| --- | --- | --- | --- |
| Who requests ads (VAST) | Player | Server (stitcher) | Server or player (asset list) |
| Who joins ad and content | Player switches between two video sources | Server rewrites the playlist so ad segments look like content | Server puts a pointer in the playlist; player plays ad as a separate asset at the marked time |
| Playlist per viewer? | Shared | **Unique per session** — hard to cache, per-viewer cost | **Shared and cacheable** — same content playlist for everyone |
| Ad-blocker resistance | Low | High | Medium–high (ads are separate requests) |
| Transition quality | Buffering/black frames common | Seamless if ads are transcoded to match the content ladder | Seamless on players with native support |
| Interactivity / rich tracking | Full | Limited; beacons fired server-side or via a client tracking API | Full client-side tracking and interactive overlays possible |
| Creative prep | Player plays creative as delivered | Every creative must be transcoded to match the content ladder. A creative not yet transcoded can't be stitched yet and falls back to slate | Creatives packaged as HLS; less strict matching |
| Scale hot-spot | Ad server | Stitcher + ad server at break start (every session requests at once) | Ad server call moves off the playlist request path |
| Device support | Any player with an ad SDK | Any HLS/DASH player (it's just a playlist) — **this is why FAST platforms and TVs rely on it** | Players supporting HLS Interstitials (Apple AVPlayer, hls.js ≥ 1.6, some commercial players); many TV platforms still lack it |
| Measurement trust | Client-side beacons | Server beacons must forward device IP/UA; buyers scrutinise server-side counts | Client-side beacons like CSAI |

**FAST Studio decision:**

1. **SSAI is the default for partner destinations**, because it works on every device.
2. **SGAI is the option for owned-and-operated players that support it.** It cuts per-viewer playlist cost and keeps the CDN cache warm.
3. **Marker pass-through** covers a platform that inserts its own ads under an inventory split. We deliver correct SCTE-35 and they fill their breaks.

Ad-insertion mode is configured per **destination**, not per channel. One channel can feed SSAI to a TV platform and SGAI to our web player at the same time. [AWS MediaTailor](https://docs.aws.amazon.com/mediatailor/latest/ug/server-guided.html) offers both, which shows the model is real: SGAI runs under the `PLAYER_SELECT` insertion mode, and players request it with `aws.insertionMode=GUIDED`.

### 3.1 SSAI break, step by step

1. Player requests `/play/{org}/{channel}/{session}/720p.m3u8`.
2. The stitcher builds the virtual-linear window (§2.1) and sees an upcoming break (`brk-7f3a-1`, 120 s).
3. It calls the ad decision server (ADS) once per session per break. The VAST request carries macros: device IP, user agent, consent and allowed identifiers.
4. The ADS returns VAST. The stitcher keeps only creatives that are already transcoded to the ladder, and fits them to 120 s, for example 30 + 30 + 15 + 30 = 105 s plus 15 s of slate.
5. The stitcher swaps the slate segments for ad segments, with `#EXT-X-DISCONTINUITY` at each join.
6. As the player fetches each ad segment, the server fires the impression and quartile beacons from VAST, or a client tracking API reports them.
7. **Fallbacks:** ADS timeout, empty VAST or malformed XML each mean house ad or slate. The break never runs longer than 120 s.

### 3.2 SGAI break, step by step (HLS Interstitials)

1. Content playlist is the same for every viewer and carries the interstitial:

```m3u8
#EXT-X-DATERANGE:ID="brk-7f3a-1",CLASS="com.apple.hls.interstitial",START-DATE="2026-10-20T14:30:00.000Z",DURATION=120.0,X-ASSET-LIST="/ads/asset-list?break=brk-7f3a-1",X-RESUME-OFFSET=120.0,X-RESTRICT="SKIP,JUMP"
```

   **Announce it ahead of time.** In a live playlist the interstitial must appear *before* the player's playhead reaches it. FAST Studio adds it up to 30 s ahead of the live edge. If the tag first appears at its own start time, hls.js buffers the primary content up to the boundary and stalls without loading the asset list. We hit exactly this during testing.
2. Shortly before the break, the player fetches `X-ASSET-LIST`. The server runs the ad decision and returns JSON with `ASSETS` (an HLS URI and duration for each ad).
3. The player plays the ads and then resumes content. `X-RESUME-OFFSET=120` keeps the viewer aligned with the linear timeline, so the ads replace the 120 s break rather than shifting the channel later.
4. The player fires the tracking beacons.

## 4. SCTE-35 — the ad-break marker

### 4.1 What it is

**ANSI/SCTE 35, "Digital Program Insertion Cueing Message"** is the cable/broadcast standard for marking splice points: break starts and ends, programme starts and ends, and chapter points. FAST inherited it because ad servers, stitchers and distribution platforms all already speak it. A marker is a small binary structure, `splice_info_section` (`table_id 0xFC`). It's carried as its own PID in MPEG-TS, or as hex/base64 inside HLS/DASH manifests.

### 4.2 Structure (the fields we generate)

| Field | Meaning | Prototype value |
| --- | --- | --- |
| `table_id` | Always `0xFC` | `0xFC` |
| `pts_adjustment` | Offset applied to all PTS (33-bit, 90 kHz clock) | 0 |
| `splice_command_type` | `0x05` splice_insert (older, simple) or `0x06` time_signal (modern, used with descriptors) | `0x06` |
| `time_signal.pts_time` | When it happens, 90 kHz ticks (seconds × 90 000, mod 2³³) | Segment boundary PTS |
| `segmentation_descriptor` | Says *what* the point is | see 4.3 |
| `segmentation_event_id` | Unique ID that pairs a start with its end | Derived from ad-break UUID |
| `segmentation_duration` | Break length in 90 kHz ticks | Planned pod duration |
| `segmentation_upid` | Optional content/ad identifier (e.g. ad ID, house ID) | Asset or break external ID |
| `CRC_32` | **MPEG-2 CRC-32**: poly `0x04C11DB7`, init `0xFFFFFFFF`, not reflected, no final XOR. **Not** `java.util.zip.CRC32` | Computed |

`splice_insert` (`0x05`) carries `out_of_network_indicator`, `break_duration`, `auto_return`, `avail_num` and `avails_expected`. Some older platforms still require it, so the destination profile picks between `0x05` and `0x06`.

### 4.3 Segmentation types that matter for FAST

| Type ID | Meaning | Used for |
| --- | --- | --- |
| `0x10` / `0x11` | Program Start / End | Programme boundaries (lets platforms align EPG) |
| `0x22` / `0x23` | Break Start / End | Generic break |
| `0x30` / `0x31` | Provider Advertisement Start / End | An ad the **content provider** placed |
| `0x32` / `0x33` | Distributor Advertisement Start / End | An ad the **distributor/platform** placed |
| `0x34` / `0x35` | **Provider Placement Opportunity** Start / End | Break the **provider (us)** may fill |
| `0x36` / `0x37` | **Distributor Placement Opportunity** Start / End | Break the **platform** may fill |

### 4.4 Why this maps directly to the business

Under an inventory split, every ad pod has an **owner**:

- Pods **we** fill are signalled `0x34`/`0x35`, and our SSAI fills them.
- Pods the **platform** fills are signalled `0x36`/`0x37`, and we pass them through with slate.

A wrong segmentation type is a revenue bug, not a cosmetic one. So `AdPod.ownership` in the [data model](DATA_MODEL.md) drives the SCTE-35 type, and destination conformance (F42) checks it.

### 4.5 How SCTE-35 appears in manifests

| Format | Syntax | Notes |
| --- | --- | --- |
| HLS DATERANGE (RFC 8216/8216bis) | `#EXT-X-DATERANGE:...,SCTE35-OUT=0xFC30...` then a matching `SCTE35-IN` | Preferred; carries the full binary as hex |
| HLS legacy cue tags | `#EXT-X-CUE-OUT:DURATION=120` … `#EXT-X-CUE-OUT-CONT:ElapsedTime=30,Duration=120` … `#EXT-X-CUE-IN` | Still demanded by some platforms; no binary |
| HLS base64 variant | `#EXT-OATCLS-SCTE35:<base64>` | Seen in some encoder outputs |
| DASH | `<EventStream schemeIdUri="urn:scte:scte35:2014:xml+bin">` with a base64 `<Binary>` | For DASH destinations (later) |
| MPEG-TS | Separate SCTE-35 PID (stream_type `0x86`) | Only with real playout/encoding (R3) |

The destination profile selects the format. The prototype emits DATERANGE by default, plus CUE-OUT/IN when a profile asks for it.

### 4.6 Marker pitfalls the prototype tests

- A break start without a matching end, or with a mismatched `segmentation_event_id`, means the platform never returns to content.
- A marker placed mid-segment means splice artefacts. Snap cue points to segment boundaries and force keyframes at transcode.
- PTS wrap-around at 2³³ ticks (≈ 26.5 h) has to wrap cleanly.
- Duration in the marker must equal the pod length on the timeline, and the timeline must equal the EPG.
- Total ad seconds per hour must not exceed the destination's ad-load limit.

## 5. Glossary

| Term | Meaning |
| --- | --- |
| ABR ladder | Set of renditions at different bitrates/resolutions |
| ADS | Ad decision server; answers a VAST request |
| As-run log | Record of what *actually* played, and when — used for reconciliation |
| Avail / pod | One ad break; a pod holds several ads in sequence |
| Cue point | Offset inside an asset where a break may be inserted |
| EPG / XMLTV | Programme guide; XMLTV is a common XML feed format |
| Fill rate | Filled ad seconds (or slots) ÷ available, under a stated definition |
| House ad / slate | Our own promo or a neutral card used when no paid ad fills the break |
| Impression | Ad started playing, as counted by the measurement authority |
| Stitcher | SSAI component that rewrites playlists per session |
| VAST | IAB XML ad response format |
| Virtual linear | Linear channel assembled from VOD segments by manifest manipulation |

References: [RESEARCH_AND_SOURCES.md](RESEARCH_AND_SOURCES.md).
