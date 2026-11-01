# Backend folder architecture

Status: **adopted 2026-10-31; applied to the whole backend in restructure step 1 (same day).** Every class lives under `v1/` (except `BackendApplication`). Mixed-audience controllers and SQL inside controllers are being split in step 1b (see PROGRESS.md).

The layout follows the user's reference microservice structure (Enveu build-manager service) and adapts it to this single Spring Boot backend. Where FAST Studio deviates from the reference, the reason is stated below.

## Target tree

```text
backend/src/main/java/com/faststudio/
│
├── BackendApplication.java                  # @SpringBootApplication; stays at the root so component scan covers com.faststudio.**
│
├── v1/
│   ├── config/                              # Spring configuration and bean definitions
│   │   ├── SecurityConfig.java              #   (today: core/SecurityConfig, WebConfig, DatabaseConfig)
│   │   ├── WebConfig.java
│   │   └── DatabaseConfig.java
│   │
│   ├── core/                                # Shared kernel used by every feature (not in the reference; needed for tenancy/RLS)
│   │   ├── tenant/                          #   Tenant, TenantInterceptor, TenantScope, TenantAwareDataSource
│   │   ├── auth/                            #   Auth, Tokens, TokenKeys, Accounts, PasswordChangeGuard
│   │   └── common/                          #   ApiException, ErrorHandler, Audit, Times, Secrets, Feature
│   │
│   ├── controllers/                         # Grouped by audience, then feature
│   │   ├── studio/                          # Project users: /api/orgs/{org}/**
│   │   │   ├── media/                       #   AssetControllerV1, MediaImportControllerV1, MediaSettingsControllerV1
│   │   │   ├── channels/                    #   ChannelControllerV1
│   │   │   ├── programming/                 #   ScheduleControllerV1, ProgrammingControllerV1
│   │   │   ├── ads/                         #   AdsControllerV1
│   │   │   ├── distribution/                #   DistributionControllerV1, PartnerSharingControllerV1
│   │   │   ├── team/                        #   TeamControllerV1
│   │   │   ├── project/                     #   ProjectDetailsControllerV1
│   │   │   └── configuration/               #   project/tenant settings seen by tenant users
│   │   ├── platform/                        # Platform administrators: /api/platform/**
│   │   │   ├── tenancy/                     #   TenantConfigurationControllerV1, AccessRoleControllerV1
│   │   │   ├── connectors/                  #   ConnectorsControllerV1, MuxConnectionControllerV1
│   │   │   └── email/                       #   EmailControllerV1
│   │   ├── partner/                         # Partner portal and API clients: /api/partners/**, /sources/{owner}/**
│   │   ├── auth/                            # /api/auth/**, JWKS
│   │   ├── viewer/                          # Unauthenticated viewer traffic: playback, ad decisions, beacons
│   │   └── webhook/                         # Inbound provider webhooks (Mux first)
│   │       └── MuxWebhookControllerV1.java
│   │
│   ├── services/                            # Business logic; mirrors controller features
│   │   ├── media/                           #   AssetServiceV1, MediaImportServiceV1, MediaPreparationService …
│   │   ├── tenancy/                         #   TenantConfigurationServiceV1, AccessRoleServiceV1
│   │   ├── programming/ ads/ channels/ …
│   │   └── webhook/
│   │       ├── MediaWebhookServiceV1.java
│   │       └── parsers/                     # Strategy + factory, as in the reference
│   │           ├── IWebhookParser.java
│   │           ├── MuxWebhookParser.java
│   │           └── WebhookParserFactory.java
│   │
│   ├── repositories/                        # Tenant-scoped SQL (JdbcClient); one folder per feature
│   │   └── media/ tenancy/ project/ …
│   │
│   ├── dto/                                 # Request/response records; browser-safe projections only
│   │   └── media/ tenancy/ project/ …
│   │
│   └── util/                                # External client adapters ONLY (reference meaning of util)
│       ├── mux/                             #   MuxClientService, MuxWebhookVerifier
│       ├── vast/                            #   VAST fetch/parse client
│       ├── cloudflare/                      #   EdgeClient, EdgePublisher (Worker/R2/KV)
│       ├── mail/                            #   MailService transport
│       └── mediaProvider/                   #   MediaProviderResolverService, provider capability registry
│
└── messaging/v1/                          # Asynchronous layer (reference: rabbitMQ/v1)
    ├── MessagingAutoConfiguration.java      # Queue/consumer configuration
    └── media/
        ├── MediaJobPublisherV1.java       # Writes the job row (outbox) and dispatches its ID
        └── MediaJobObserverV1.java        # Pull consumer: lease, execute through services, acknowledge
```

## Layer responsibilities

| Layer | Owns | Must not |
| --- | --- | --- |
| `controllers/<audience>/<feature>` | Request binding, `@Valid`, endpoint permission (`Tenant.require`), response mapping, cache headers | Hold SQL, provider HTTP or multi-step business rules |
| `services/<feature>` | Use-case rules, validation shared across endpoints, transactions, idempotency, audit | Call provider HTTP directly; return entities containing secrets |
| `repositories/<feature>` | SQL through `JdbcClient`; relies on PostgreSQL RLS via the tenant-scoped data source | Use `TenantScope.bypass` without a documented reason and a test |
| `dto/<feature>` | Records for requests and allowlisted responses | Expose provider credentials, signed URLs or raw configuration |
| `util/<provider>` | Provider HTTP, auth, pagination, response/webhook translation | Contain tenant business rules; become a grab-bag of helpers |
| `messaging/v1` | Durable job dispatch and consumers that call services | Carry video payloads; treat the queue as the source of truth |
| `config`, `core` | Cross-cutting configuration and the tenant/auth/error kernel | Accumulate feature logic |

## Naming

- Controllers and the primary service of a feature carry the version suffix: `AssetControllerV1`, `AssetServiceV1`. Supporting services, repositories, DTOs and adapters do not (`MediaPreparationService`, `AssetRepository`, `MuxClientService`), matching the reference.
- Interfaces for a real strategy with more than one implementation use the `I` prefix as in the reference (`IWebhookParser`, `IMediaProvider`). Do not create an interface with a single implementation.
- Feature folder names are lowerCamelCase as in the reference (`mediaProvider`, `buildApp`); Java packages remain lower case where the folder is one word.

## Versioning

`v1` is a code-generation marker, not a URL change. Existing API paths (`/api/orgs/{org}/…`, `/api/platform/…`, `/play/…`) stay exactly as they are. A `v2` package is created only for a deliberately breaking API, served on new paths alongside `v1` until clients migrate.

## Deviations from the reference, and why

| Reference | FAST Studio | Reason |
| --- | --- | --- |
| Root `com.enveu.saas.v2.buildManager.service.main` | Root stays `com.faststudio` | Renaming the root is a repository-wide move with no behavior gain; can be done once later as a single mechanical change if the user requests it |
| Entry point in `v1/main/` | `BackendApplication` stays at the root | Moving it narrows Spring's component scan; keeping it avoids a `scanBasePackages` override |
| No `repositories/`, `dto/` folders | Present | AGENTS.md requires controller/service/repository/DTO boundaries; RLS-scoped SQL needs its own layer |
| `controllers/enterprise/studio/...` | `controllers/studio`, `platform`, `partner`, `auth`, `viewer`, `webhook` | This product has distinct audiences with different authorization interceptors; grouping by audience keeps each interceptor's path set obvious |
| `rabbitMQ/v1` | `messaging/v1` | Agreed architecture: PostgreSQL job rows are authoritative (outbox) and Cloudflare Queues transports job IDs (PROGRESS.md). Same publisher/observer shape; transport-neutral name. If the user requires RabbitMQ, only the adapter inside this folder changes |
| `core/` absent | `v1/core/` | Tenant context, RLS scope and auth primitives are shared by every feature |

## Where everything moved (restructure step 1, 2026-10-31)

Old package → new location (all under `com.faststudio.v1`). API paths did not change.

| Old | New |
| --- | --- |
| `core` Spring config: SecurityConfig, WebConfig, DatabaseConfig, AdminBootstrap | `config/` |
| `core/DemoAccounts`, `demo/DemoSeeder` | `config/demo/` |
| `core` tenant context: Tenant, TenantInterceptor, TenantScope, TenantScopeFilter, TenantAwareDataSource | `core/tenant/` |
| `core` auth: Auth, Accounts, Tokens, TokenKeys, PasswordChangeGuard | `core/auth/` |
| `core` common: ApiException, ErrorHandler, Audit, Data, Feature, PublicUrl, Secrets, Times | `core/common/` |
| `partners` PartnerInterceptor, PartnerScope, PartnerScopes | `core/partner/` |
| `core/AuthController`, `JwksController` | `controllers/auth/AuthControllerV1`, `JwksControllerV1` |
| `catalog/AssetController`, `MediaSettingsController` | `controllers/studio/media/AssetControllerV1`, `MediaSettingsControllerV1` |
| `channels/ChannelController` | `controllers/studio/channels/ChannelControllerV1` |
| `schedule/ScheduleController`, `ProgrammingController` | `controllers/studio/programming/…V1` |
| `ads/AdsController` | `controllers/studio/ads/AdsControllerV1` |
| `distribution/DistributionController`, `partners/PartnerSharingController` | `controllers/studio/distribution/…V1` |
| `ops/OpsController` | `controllers/studio/monitoring/OpsControllerV1` |
| `team/TeamController` | `controllers/studio/team/TeamControllerV1` |
| `connectors/ConnectorsController`, `media/MuxController` | `controllers/platform/connectors/…V1` |
| `mail/EmailController` | `controllers/platform/email/EmailControllerV1` |
| `partners/PartnerApiController`, `ApiClientController` | `controllers/partner/…V1` |
| `playout/PlaybackController`, `media/MediaController` | `controllers/viewer/PlaybackControllerV1`, `MediaFileControllerV1` |
| `ads/AdDecisionService`, `AdSourceAdmin`, `ViewerContext` | `services/ads/AdDecisionServiceV1`, `AdSourceAdminService`, `ViewerContextService` |
| `ads/AdSource`, `DirectSoldSource`, `VastSource` | `services/ads/sources/IAdSource`, `DirectSoldSource`, `VastSource` |
| `catalog/MediaSettings`, `media/MediaService`, `MediaSigner`, `MediaStore` | `services/media/MediaSettingsServiceV1`, `MediaServiceV1`, `MediaSigner`, `MediaStore` |
| `media/ContentSource`, `GeneratedSource`, `RemoteSource`, `UploadSource`, `MuxSource` | `services/media/sources/IContentSource`, … |
| Mux connector split (ingestion Stage 1, 2026-11-01) | HTTP client `util/mux/MuxClientService`; credentials `services/media/MuxConnectionService`; sync/upload `services/media/LibraryServiceV1`; webhooks `services/webhook/MediaWebhookServiceV1`; packaging `services/media/sources/MuxSource`; metadata/rights validation shared by edits and imports `services/media/AssetRules` |
| `schedule/ProgrammingService`, `Planner`, `ProgrammingRefresh` | `services/programming/ProgrammingServiceV1`, `Planner`, `ProgrammingRefresh` |
| `playout/PlayoutService`, `Scte35` | `services/playout/PlayoutServiceV1`, `Scte35` |
| `rights/Rights` | `services/rights/Rights` |
| `ops/AlertScanner`, `AvailVerifier` | `services/ops/` |
| `mail/MailService`, `EmailTemplates` | `services/mail/MailServiceV1`, `EmailTemplates` |
| `edge/EdgeClient`, `EdgePublisher` | `util/cloudflare/` |
| tenancy and project bootstrap | already under `v1` (tenant redesign commit) |

Tests moved next to the class they exercise (same package, for package-private access); cross-cutting tests stay in `com.faststudio`.

## Rules from now on

1. New code goes into the matching `controllers/<audience>/<feature>`, `services/<feature>`, `repositories/<feature>`, `dto/<feature>` or `util/<provider>` folder.
2. Keep every public API path, response shape and permission check. Run `CrossTenantMatrixTest` and the feature's tests after each change.
3. Controllers and services do not hold SQL: every query lives in `repositories/<feature>` (done 2026-10-31). Only cross-cutting infrastructure in `core/` and `config/` (tokens, request interceptors, audit, demo seeding, bootstrap) talks to the database directly. Member permissions are resolved by `core/auth/Permissions` over `AccessRoleRepository`, so `core` never depends on a service.
5. Verify backend changes with `./mvnw clean test`. An IDE build can leave stale classes in `target/` that hide compile errors from an incremental build.
4. Do not move files another agent has claimed in PROGRESS.md; coordinate first.
