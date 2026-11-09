# Programmes from the video platform, ads and slate from us: plan

Status (8 November 2026): **S2-S5 built and tested locally against a stand-in for Mux; not yet run against a real Mux account or on real players** (S1, which needs the user's free Mux account). S6 (JW Player, mixed platforms) and S7 (costs) are not built. Written 3 November 2026 after the user's decision (docs/PRODUCTION_ARCHITECTURE_AND_COSTS.md, top section).

## As built (8 November 2026)

- **S2:** `IPlayback` capability (`util/mediaProvider`), Mux implementation (`MuxProvider.playback`, `util/mediaProvider/Hls`), `asset_playback` (V31). Preparing a library video reads its playlists (`StreamingServiceV1`); nothing is downloaded, and the Mux master-file download path (`MuxSource`) is gone. Uploads straight to FAST Studio still go to R2. One library connection per project (several: with S6).
- **S3:** the schedule stays on the 5 s grid. A streaming programme occupies whole slots; inside them the edge plays the platform's segments at their real lengths and fills the rest of the slot with slate (`util/cloudflare/Timeline`), so listed lengths always equal the clock. Numbering comes from a per-channel anchor (`channel_timeline`), carried in the snapshot as t0/seq0 plus each programme's seq/n. The Worker (`worker/src/ovp.ts`) reads the platform playlists once per location and quality level (memory, then Cache API), keeps the last good copy on failures, and plays slate for the same lengths when it has none.
- **S4:** our ads and slate keep our ladder (360p/540p/720p); each of our quality levels takes the platform rendition nearest in height. Platform 1080p needs a 1080p rung for ads and slate (not built). fMP4 (CMAF) platform streams are supported since 9 November 2026: every video, ad and slate we package also gets an fMP4 copy (a repackage of the 5 s MPEG-TS segments, no re-encode; older media is copied at start-up), and a channel whose library programmes stream as fMP4 plays all our media from those copies, with an `EXT-X-MAP` for each source (HLS can not switch from fMP4 back to TS in one playlist). On such a channel, anything without an fMP4 copy, and any MPEG-TS library video, plays slate. Real Mux videos stream as fMP4 today (the user's first Mux video, 9 November 2026), unlike the older public sample measured on 3 November.
- **S5:** Mux signed playback: optional signing key on the connection (V31 connector fields), RS256 tokens per video (`MuxSigner`), valid until 6 hours after the snapshot window; the library screen gets 6-7 hour links. Tokens are per video, not per viewer.
- **Screens:** Library page panel (Sync library, Get N ready to stream), Delivery card in the video drawer, signing key fields in the platform Mux form.
- **Known limits:** viewers' players fetch programme segments from the platform's own domain (stream.mux.com), so the platform is visible in network traffic; a part can lose up to one platform segment at a mid-roll break; DSN memo per Worker isolate (as before).

## Goal

- A viewer's live playlist takes **programme segments from the online video platform (OVP)** the programme lives on: Mux today, JW Player soon, and any other platform (e.g. Brightcove, Watchity), or several in one project.
- **Ads and slate** are FAST Studio's own: transcoded by us, stored in R2, delivered by the Cloudflare CDN, and spliced into the playlist at the breaks.
- Scheduling, rights, ad decisions, beacons and measurement keep working as they do now.

## How it works today (what changes)

The Worker (`worker/src/playout.ts`) computes every playlist from the schedule snapshot and the clock:
- Every segment is exactly `segMs` (5 s) long. Segment *i* always covers `EPOCH + i × 5 s`, so `EXT-X-MEDIA-SEQUENCE` is simply *i*.
- Every segment URL is ours: `{MEDIA_BASE}/{mediaKey}/{variant}/seg_NNNNN.ts` (R2).
- One ladder per channel (`snapshot.ladder`), which our FFmpeg packaging produces for programmes, ads and slate alike.

For programmes, all three assumptions break: their segments live on the OVP, may not be 5 s, may use a different container, and are encoded with the OVP's ladder. Ads and slate keep the current path.

## Target design

**1. The adapter supplies playback (Java, `util/mediaProvider`).**
- A new `PLAYBACK_MANIFEST` capability. Given a library connection and an asset's external id, the adapter returns the master playlist URL, its renditions and, for signed playback, a way to make a viewer token.
- Only the adapter knows the platform. The snapshot and the Worker see neutral fields.

**2. Each programme remembers where it plays from.**
- `asset` gains its source connection (the library connection instance) next to the existing `source_ref`.
- A project can hold several library connections. Today `MediaConnectionService.find` uses only the first, so it has to change.
- Assets uploaded directly to FAST Studio with no OVP behind them keep the R2 path.

**3. The snapshot carries playback references.**
- PROGRAM items gain a playback reference (provider, playback id, rendition list) instead of an R2 media key when the asset is OVP-delivered.
- No credentials go into the snapshot. The control plane publishes it.

**4. The Worker splices (`worker/src`).**
- It fetches the OVP's rendition playlists for an asset (VOD playlists) and caches them per asset, rendition and token lifetime.
- The programme timeline is built from the real `#EXTINF` durations rather than a fixed 5 s grid (decided: follow the OVP's lengths).
- Media sequence numbers must still only ever go up, and every Worker location must compute the same playlist.
- Ad and slate segments stay on R2 and are inserted at breaks with `#EXT-X-DISCONTINUITY`. Markers (CUE-OUT, DATERANGE, SGAI interstitials) and server-side beacons keep their current logic.
- If the OVP can't be reached, the Worker plays the channel's standby slate rather than failing.

**Any platform, one contract.** Adding a platform (JW Player, Brightcove, Watchity or another) means writing one adapter, with no changes to the Worker or the schedule. The contract assumes as little as possible:
- segment lengths can be anything (the Worker follows them);
- the format can be MPEG-TS or fMP4/CMAF (we keep ads and slate in both and pick per platform);
- signed URLs are optional, and so are a fixed quality ladder and a catalogue API;
- the platform must give an HLS playback playlist per asset. A platform that can't is not supported for delivery.

What each platform offers is declared as adapter capabilities, as today, so the screens only show what that platform can do.

**5. Ads and slate match the OVP's renditions.**
- Ad and slate conditioning encodes to the channel's playback ladder, which now comes from the OVP: resolutions, codecs, frame rate, audio layout and loudness, and keyframes aligned to segment boundaries.
- Ads and slate are prepared in the format each platform uses. If it serves fMP4/CMAF, ours are fMP4 too, with `EXT-X-MAP` after each discontinuity. Mixing formats inside one rendition is a known stall risk. A channel that mixes platforms with different formats is checked in S6.

**6. Signed playback.**
- When the OVP requires signed URLs, the control plane issues a token per viewer session through the adapter.
- The Worker uses it to fetch playlists, and the viewer's player uses it to fetch the OVP's segments.

## First look at Mux (3 November 2026, one public demo asset)

Read from Mux's public demo playback (a 24-second clip), so this is a first indication, not proof for long-form programmes:
- **Segments are 5 s.** Four segments of exactly 5 s, then a shorter last one (3.857 s). `EXT-X-TARGETDURATION:6`, so Mux may use segments up to 6 s.
- **Format is MPEG-TS** (`video/MP2T`, no `EXT-X-MAP`), H.264 video and AAC audio: the same container as our ad and slate segments, so no fMP4 mixing.
- **Five quality levels** on this asset: 270p, 360p, 540p, 720p and 1080p.
- **Playlist and segment URLs are signed** and carry an expiry, about a week ahead on this asset. Segments are cacheable for 7 days (`max-age=604800`).

How the Worker handles it:
- Programme segments are mostly 5 s, so breaks fall on the same 5 s boundaries as today, and our 5 s ad and slate segments fit them.
- A programme's last segment is usually shorter. The Worker plays it at its real length and then goes straight on to the next item. The timeline runs on real durations, so the schedule uses each programme's exact length (the sum of its segments) rather than rounding up to 5 s.
- If a long-form asset has segments of other lengths (up to 6 s), the Worker still uses their real durations. A break then starts at the programme segment boundary nearest its scheduled time.
- Signed URLs expire, so the Worker re-reads an asset's playlists before the expiry and never caches them longer than that.

## What has to be measured first (Mux, on a real asset)

These decide the design and can't be assumed:
- Segment duration and whether it is constant per asset.
- Container (TS or fMP4) and codecs per rendition.
- Whether the ladder differs per asset, and whether it can be fixed per project or channel.
- How signed playback tokens reach rendition playlists and segments, and how long they last.
- Whether segment URLs are stable enough to cache.
- Whether a programme → ad → programme join plays cleanly in hls.js, Safari/AVPlayer and one TV player, with rendition switching across the join.

## Stages (each tested and pushed on its own)

| Stage | Scope |
| --- | --- |
| S1 Feasibility spike (needs the user's Mux account) | One real Mux asset and one JW Player asset: record the facts above; condition one ad and the slate to its ladder; hand-build a spliced playlist and play it in hls.js, iOS Safari and Android TV (ExoPlayer). Output: measurements and a go/no-go per question, written into this file. No product code. |
| S2 Playback in the adapter | `PLAYBACK_MANIFEST` capability and the Mux implementation; library sync stops downloading and packaging OVP programmes into R2 (decision 2); asset source connection; several library connections per project; tests with a fake provider |
| S3 Snapshot and Worker splicing | Playback references in the snapshot; the Worker reads OVP playlists, builds the variable-duration timeline, splices R2 ads and slate, keeps sequence numbers and markers right, falls back to standby; Worker tests for joins, sequence continuity and outages |
| S4 Ad and slate conditioning to the OVP ladder | Conditioning reads the channel's playback ladder; container per the S1 result; re-prepare existing creatives and slate |
| S5 Signed playback | Per-session tokens issued by the control plane through the adapter; expiry and refresh |
| S6 Combined OVPs | The JW Player adapter (library sync and playback) and channels mixing Mux and JW Player programmes; ad and slate ladder rules when the two platforms' quality levels differ |
| S7 Costs and docs | Re-cost delivery with the OVP's per-viewer-minute pricing; update the architecture, feature and API docs |

## Decisions needed from the user

1. ~~Segment lengths~~ **Decided 3 November 2026: follow the OVP's segment lengths.** The Worker builds the programme timeline from the OVP's real `#EXTINF` durations, whatever they are; no OVP setting is required. Ads and slate stay on our 5 s grid, and a break starts and ends at a programme segment boundary.
2. ~~R2 copies~~ **Decided 3 November 2026: no programme content in R2.** OVP programmes are not downloaded or packaged into R2; if the OVP is down, the channel plays standby slate. Only ads and slate live in R2.
3. ~~Players~~ **Decided 3 November 2026: web, iOS and one TV.** A channel goes live only after programme → ad → programme joins and quality switching play cleanly in a web player (hls.js), on iOS (Safari/AVPlayer) and on one smart-TV platform. The TV platform is **Android TV** (ExoPlayer/Media3), tested on the Android TV emulator or a stick. S1 tests all three.
4. ~~Second OVP~~ **Decided 3 November 2026: JW Player, soon.** S1 measures a JW Player asset alongside Mux (segment lengths, format, quality levels, signed URLs), and S2 builds the playback capability for both, so nothing in the design is Mux-shaped. Needs a JW Player property's API credentials and one test asset. If JW Player's segments are longer than 5 s, breaks there move to the nearest JW segment edge.

## Ads and slate at a break (built, unchanged by this plan)

Confirmed with the user on 8 November 2026. This is how breaks already work (`AdDecisionServiceV1`, `VastSource`, `worker/src/playout.ts`):
- **Ads come straight from the ad server.** For each viewer and break, the ad server's tag (Google Ad Manager or any VAST server) is called live, within its timeout (default 1 s).
- **Each creative is transcoded by our FFmpeg** into 5 s segments on R2, once per creative. After that, the copy is reused.
- **Slate covers every gap.** The standby slate from R2 plays when:
  - the ad server times out, fails or returns no ads (the whole break is slate);
  - the ads are shorter than the break (slate fills the rest);
  - the decision isn't ready when the break starts (the whole break is slate, and it never switches to ads halfway).
- **A creative seen for the first time** isn't converted yet when its break starts, so that break plays slate and the creative airs from the next break. Converting it inside the ~1 s decision window isn't possible.

## Not affected

Scheduling and rules, rights and geo, ad decisions (one per session and break), campaigns, beacons, partner distribution, the connector marketplace.
