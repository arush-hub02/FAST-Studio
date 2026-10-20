# FAST Studio research and sources

Reviewed 19 October 2026. Evidence is public first-party product/documentation material, not hands-on verification or a commercial offer. Vendor capability and reliability claims remain vendor claims. No competitor private interfaces, pricing quotes, partnership access or contracts were inspected.

## Competitor evidence

| Source | Publicly described capabilities | Roadmap implication |
| --- | --- | --- |
| [SoFast](https://www.sofast.tv/) | Channel creation, cloud playout, distribution, EPG, localization and white-label offerings | Treat channel operations and distribution workflows as category expectations |
| [SoFast monetization](https://www.sofast.tv/monetization/index.html) | SSAI, SCTE-35 markers, ad serving, programmatic/direct demand, yield controls and reporting; revenue-share, inventory-split and hybrid options | Plan inventory ownership and financial reconciliation alongside insertion |
| [Evrideo](https://www.evrideo.com/) | Cloud broadcast operations spanning playout, distribution and monetization | A functional control workflow needs media adapters and observed operations |
| [Evrideo AdBoost](https://www.evrideo.com/products/adboost/) | SSAI with own/customer VAST tags, inventory splits, reporting, live ad replacement, overlays and programmatic fill; advertises flat SaaS without ad-revenue share | Support bring-your-own demand and an optional managed-monetization model; validate buyer preference |

Inference: rights-aware readiness, reliable operation and understandable channel economics are useful product hypotheses. These sources do not prove competitors lack any proposed FAST Studio feature. Do not present the backlog as a verified exclusive advantage or repeat vendor claims about guaranteed performance, universal ad-block avoidance or revenue.

## Primary implementation references

| Source | Verified use in this plan |
| --- | --- |
| [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) | Check supported Java/build/runtime compatibility before selecting the exact project versions |
| [AWS MediaTailor overview](https://docs.aws.amazon.com/mediatailor/latest/ug/what-is.html) | Candidate service for channel assembly and SSAI, with distinct workflow/origin requirements |
| [IAB VAST](https://iabtechlab.com/standards/vast/) | Ad-response standard reference; supported versions/features must be provider-tested |
| [IAB ads.txt](https://iabtechlab.com/ads-txt/) | Authorized seller declarations and app-ads.txt context; surface ownership requires partner review |
| [Stripe webhooks](https://docs.stripe.com/webhooks) | Example provider guidance for signed callback handling, retries and asynchronous processing |

### Ad insertion and marker standards (added in revision 3)

| Source | Use in this plan |
| --- | --- |
| [AWS MediaTailor server-guided ad insertion](https://docs.aws.amazon.com/mediatailor/latest/ug/server-guided.html) | SGAI via HLS Interstitials: `PLAYER_SELECT` insertion mode, players request `aws.insertionMode=GUIDED`; confirms SSAI+SGAI per-player choice is a real pattern |
| [AWS: HLS Interstitials in MediaTailor](https://aws.amazon.com/blogs/media/support-for-hls-interstitials-in-aws-elemental-mediatailor) | SGAI for VOD and live |
| [Apple: Getting started with HLS Interstitials](https://developer.apple.com/streaming/GettingStartedWithHLSInterstitials.pdf) | `CLASS="com.apple.hls.interstitial"`, `X-ASSET-URI` / `X-ASSET-LIST`, `X-RESUME-OFFSET`, `X-RESTRICT` |
| [hls.js 1.6.0 release](https://newreleases.io/project/yarn/hls.js/release/1.6.0) | HLS Interstitials support in hls.js (prototype uses 1.7.3) |
| HLS: RFC 8216 and draft-pantos-hls-rfc8216bis | `EXT-X-DATERANGE` with `SCTE35-OUT/IN`, `EXT-X-DISCONTINUITY-SEQUENCE`, `EXT-X-PROGRAM-DATE-TIME` |
| ANSI/SCTE 35 (Digital Program Insertion Cueing Message) | splice_info_section, time_signal, segmentation descriptor types. The prototype's decoder is verified against the standard's published time_signal sample (`/DA0AAAAAAAA///wBQb+…`). |

These references inform interoperability requirements. They do not select vendors, prove access, establish launch-market legal compliance or certify that an integration works. Technical proposals and commercial examples in the other documents are FAST Studio recommendations, explicitly separate from the facts above.
