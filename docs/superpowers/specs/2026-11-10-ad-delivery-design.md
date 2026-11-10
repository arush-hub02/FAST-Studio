# Ad delivery per ad server: Store in R2 or Direct from the ad server

Status: design approved in conversation 2026-11-10; not implemented.

## Goal

New ads from external ad servers (VAST, Google) must earn money in their first break. Today a new creative is
downloaded, re-encoded to every project quality level, split for fMP4 channels and uploaded to R2 before it can play;
until then its break plays house promos or slate. The decision is made up to 60 s before the break (Worker retries
every 8 s), which full preparation often misses.

The user wants both delivery ways available, chosen per ad server connection and gated by a platform feature:

- **Store in R2 and serve** (default, today's path) gains a fast first version.
- **Direct from the ad server**: the ad's own HLS plays from the ad server's CDN; nothing is downloaded or stored.

## Decisions (user)

| Question | Decision |
| --- | --- |
| Main goal | Don't miss first-time ads |
| Options | Both: Store in R2, and Direct from the ad server |
| Who chooses | Platform feature per tenant + a setting per ad server connection |
| Direct ad that can't play directly | Fall back to Store in R2 for that creative |

## Facts the design rests on

- A live HLS stream can only include HLS pieces. Progressive MP4 (what Google and most VAST servers mainly send)
  cannot be listed directly; those creatives always use Store in R2.
- Google signs creative URLs per request: the URL is not a stable identity. `asset.source_ref` (ad system + creative
  id, V9) is. Checks are cached per `source_ref`; the address played is always the one in the current VAST answer.
- The live timeline runs on 5 s steps. A gap shorter than a step is filled with an exact black piece
  (`Snapshot.fillers`, 2026-11-10). A piece listed with the wrong length makes players drift and stall (measured).
- fMP4 channels (Mux programmes) carry audio in a separate audio playlist; TS channels carry muxed audio.
- The Worker already reads external HLS (Mux) and lists pieces by absolute URL (`ovp.ts`, `Seg.url` / `Seg.map`).

## Part 1: settings and decision

1. **Feature** `AD_DIRECT_DELIVERY` (kind FEATURE, category Advertising, built in, off unless the platform enables it
   for the tenant). Added to `core.common.Feature` and the capability catalogue in a new migration.
2. **Connector property** on the `vast` connector: `delivery`, SELECT, options `STORE_R2` / `DIRECT`, default
   `STORE_R2`, scope EXTERNAL. Saving `DIRECT` without the feature is rejected (400). The connection screen shows the
   choice only when the feature is on. Existing connections keep `STORE_R2`. `IAdSource.Config` gains `delivery`.
3. **VastSource.fill**
   - `STORE_R2`: unchanged selection; creatives become playable earlier through the fast first version (Part 3).
   - `DIRECT`: parse HLS MediaFiles (`application/x-mpegURL`, `application/vnd.apple.mpegurl`) beside MP4. If the ad has
     HLS and its cached direct check (Part 2) says it fits the channel, add a slot with `delivery = DIRECT` and the
     current answer's HLS URL. Otherwise use the Store path for that creative and record the reason
     ("Direct not possible: MP4 only" / "… pieces longer than 5 s" / "… no separate audio track", etc.).
4. **Slot** gains `delivery` (`R2` | `DIRECT`) and `hlsUrl` (DIRECT only; `mediaKey` null). Delivery events and the
   decision summary record the delivery way; Ads reporting shows it.

## Part 2: direct check and playback

1. **Direct check, once per creative** (server, on first sight, re-checked after 24 h): fetch the master and its
   variant playlists through `PublicUrl` (SSRF checks, https only). Store on the creative: variants (height,
   bandwidth), container (TS / fMP4), separate audio track yes/no, longest piece, total length, checked time, result.
   While the first check runs the creative is not yet direct-ready: that answer falls back to Store in R2 (so the first
   break is covered by the fast first version).
2. **Fit rules** (all must hold, per channel format):
   - container matches the channel (TS channel: TS pieces; fMP4 channel: fMP4 pieces);
   - fMP4 channels: a separate audio rendition;
   - its pieces are 5 s each (±0.1 s), like our R2 ads; a shorter last piece is dropped, exactly as R2 ads drop
     their short tail (`fullSegments`), so the slot length is the whole pieces × 5 s (amended 2026-11-10: inside a
     break the edge swaps one ad piece per 5 s step; variable piece lengths would need breaks rebuilt like spans);
   - total length within 0.5 s of the VAST duration and ≤ time left in the break.
3. **Worker playlist**: a DIRECT slot fills the same 5 s steps an R2 ad does: read the ad's master with the existing
   OVP reader (cached per URL), pick per channel level the nearest variant (`ovp.pick`), and list piece n of that
   variant (absolute URL, `EXT-X-MAP` for fMP4) in step n, DISCONTINUITY before and after as for R2 ads, and on
   fMP4 channels the ad's audio piece n in the audio playlist. Pieces not yet read play slate. SGAI interstitial asset lists use the ad's HLS URL.
4. **Tracking**: impression and quartile beacons fire as today (Worker, when the viewer loads the ad's pieces).
5. **Failure**: if reading the ad's HLS fails when the playlist is built, the Worker plays the slot as slate. Once a
   playlist is out it can't be changed: an ad CDN failure mid-break shows as a short stall (accepted risk).

## Part 3: fast first version (Store in R2)

On first sight of a creative: if its video is H.264 with AAC audio and its keyframes fall on 5 s boundaries, re-package
it without re-encoding as the creative's single first level; otherwise encode one level (the project's highest
at or below the source height, fast preset). Upload, split for fMP4, mark READY with that level, and queue the full
ladder in the background; the creative's `levels` grow when it finishes. A channel level the creative lacks plays the
nearest lower level (existing behaviour).

## Out of scope

- Ads with pieces other than 5 s (they use Store in R2); player-side ads (IMA SDK); converting MP4 for direct play (that is Store in R2); changing the 5 s timeline step;
  swapping an ad after its playlist was served.

## Testing

- Backend: HLS MediaFile parsing; fit rules (each failing case falls back with its reason); `DIRECT` rejected without
  the feature; slot carries `delivery`/`hlsUrl`; fast first version produces one READY level, then the full ladder.
- Worker: playlist for a DIRECT slot on a TS and an fMP4 channel (pieces, durations, DISCONTINUITY, audio, filler);
  unreadable ad HLS plays slate.
- Local end to end: a fake ad server serving a VAST answer with an HLS ad (fixture made with ffmpeg, 5 s pieces) and
  one with MP4 only; Watch plays both through a break; the stall probe shows no stalls; a brand-new creative airs in
  its first break (timed).
- Real ad servers: record how many Google/VAST answers carry HLS and their piece lengths before relying on Direct.

## Docs

FEATURES entry with animated flow, API reference (connector property), FAQ entry, visualizeDB (new migration), docs/html
regenerated.
