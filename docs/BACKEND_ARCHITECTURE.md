# Backend folder architecture

Status: **adopted 2026-10-31; applied to the whole backend in restructure step 1 (same day).** Every class lives under `v1_0/` (except `BackendApplication`). Mixed-audience controllers and SQL inside controllers are being split in step 1b (see PROGRESS.md).

The layout follows the user's reference microservice structure (Enveu build-manager service) and adapts it to this single Spring Boot backend. Where FAST Studio deviates from the reference, the reason is stated below.

## Target tree

```text
backend/src/main/java/com/faststudio/
│
├── BackendApplication.java                  # @SpringBootApplication; stays at the root so component scan covers com.faststudio.**
│
├── v1_0/
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
│   │   │   ├── media/                       #   AssetControllerV1_0, MediaImportControllerV1_0, MediaSettingsControllerV1_0
│   │   │   ├── channels/                    #   ChannelControllerV1_0
│   │   │   ├── programming/                 #   ScheduleControllerV1_0, ProgrammingControllerV1_0
│   │   │   ├── ads/                         #   AdsControllerV1_0
│   │   │   ├── distribution/                #   DistributionControllerV1_0, PartnerSharingControllerV1_0
│   │   │   ├── team/                        #   TeamControllerV1_0
│   │   │   ├── project/                     #   ProjectDetailsControllerV1_0
│   │   │   └── configuration/               #   project/tenant settings seen by tenant users
│   │   ├── platform/                        # Platform administrators: /api/platform/**
│   │   │   ├── tenancy/                     #   TenantConfigurationControllerV1_0, AccessRoleControllerV1_0
│   │   │   ├── connectors/                  #   ConnectorsControllerV1_0, MuxConnectionControllerV1_0
│   │   │   └── email/                       #   EmailControllerV1_0
│   │   ├── partner/                         # Partner portal and API clients: /api/partners/**, /sources/{owner}/**
│   │   ├── auth/                            # /api/auth/**, JWKS
│   │   ├── viewer/                          # Unauthenticated viewer traffic: playback, ad decisions, beacons
│   │   └── webhook/                         # Inbound provider webhooks (Mux first)
│   │       └── MuxWebhookControllerV1_0.java
│   │
│   ├── services/                            # Business logic; mirrors controller features
│   │   ├── media/                           #   AssetServiceV1_0, MediaImportServiceV1_0, MediaPreparationService …
│   │   ├── tenancy/                         #   TenantConfigurationServiceV1_0, AccessRoleServiceV1_0
│   │   ├── programming/ ads/ channels/ …
│   │   └── webhook/
│   │       ├── MediaWebhookServiceV1_0.java
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
└── messaging/v1_0/                          # Asynchronous layer (reference: rabbitMQ/v1_0)
    ├── MessagingAutoConfiguration.java      # Queue/consumer configuration
    └── media/
        ├── MediaJobPublisherV1_0.java       # Writes the job row (outbox) and dispatches its ID
        └── MediaJobObserverV1_0.java        # Pull consumer: lease, execute through services, acknowledge
```

## Layer responsibilities

| Layer | Owns | Must not |
| --- | --- | --- |
| `controllers/<audience>/<feature>` | Request binding, `@Valid`, endpoint permission (`Tenant.require`), response mapping, cache headers | Hold SQL, provider HTTP or multi-step business rules |
| `services/<feature>` | Use-case rules, validation shared across endpoints, transactions, idempotency, audit | Call provider HTTP directly; return entities containing secrets |
| `repositories/<feature>` | SQL through `JdbcClient`; relies on PostgreSQL RLS via the tenant-scoped data source | Use `TenantScope.bypass` without a documented reason and a test |
| `dto/<feature>` | Records for requests and allowlisted responses | Expose provider credentials, signed URLs or raw configuration |
| `util/<provider>` | Provider HTTP, auth, pagination, response/webhook translation | Contain tenant business rules; become a grab-bag of helpers |
| `messaging/v1_0` | Durable job dispatch and consumers that call services | Carry video payloads; treat the queue as the source of truth |
| `config`, `core` | Cross-cutting configuration and the tenant/auth/error kernel | Accumulate feature logic |

## Naming

- Controllers and the primary service of a feature carry the version suffix: `AssetControllerV1_0`, `AssetServiceV1_0`. Supporting services, repositories, DTOs and adapters do not (`MediaPreparationService`, `AssetRepository`, `MuxClientService`), matching the reference.
- Interfaces for a real strategy with more than one implementation use the `I` prefix as in the reference (`IWebhookParser`, `IMediaProvider`). Do not create an interface with a single implementation.
- Feature folder names are lowerCamelCase as in the reference (`mediaProvider`, `buildApp`); Java packages remain lower case where the folder is one word.

## Versioning

`v1_0` is a code-generation marker, not a URL change. Existing API paths (`/api/orgs/{org}/…`, `/api/platform/…`, `/play/…`) stay exactly as they are. A `v2_0` package is created only for a deliberately breaking API, served on new paths alongside `v1_0` until clients migrate.

## Deviations from the reference, and why

| Reference | FAST Studio | Reason |
| --- | --- | --- |
| Root `com.enveu.saas.v2.buildManager.service.main` | Root stays `com.faststudio` | Renaming the root is a repository-wide move with no behavior gain; can be done once later as a single mechanical change if the user requests it |
| Entry point in `v1_0/main/` | `BackendApplication` stays at the root | Moving it narrows Spring's component scan; keeping it avoids a `scanBasePackages` override |
| No `repositories/`, `dto/` folders | Present | AGENTS.md requires controller/service/repository/DTO boundaries; RLS-scoped SQL needs its own layer |
| `controllers/enterprise/studio/...` | `controllers/studio`, `platform`, `partner`, `auth`, `viewer`, `webhook` | This product has distinct audiences with different authorization interceptors; grouping by audience keeps each interceptor's path set obvious |
| `rabbitMQ/v1_0` | `messaging/v1_0` | Agreed architecture: PostgreSQL job rows are authoritative (outbox) and Cloudflare Queues transports job IDs (PROGRESS.md). Same publisher/observer shape; transport-neutral name. If the user requires RabbitMQ, only the adapter inside this folder changes |
| `core/` absent | `v1_0/core/` | Tenant context, RLS scope and auth primitives are shared by every feature |

## Where everything moved (restructure step 1, 2026-10-31)

Old package → new location (all under `com.faststudio.v1_0`). API paths did not change.

| Old | New |
| --- | --- |
| `core` Spring config: SecurityConfig, WebConfig, DatabaseConfig, AdminBootstrap | `config/` |
| `core/DemoAccounts`, `demo/DemoSeeder` | `config/demo/` |
| `core` tenant context: Tenant, TenantInterceptor, TenantScope, TenantScopeFilter, TenantAwareDataSource | `core/tenant/` |
| `core` auth: Auth, Accounts, Tokens, TokenKeys, PasswordChangeGuard | `core/auth/` |
| `core` common: ApiException, ErrorHandler, Audit, Data, Feature, PublicUrl, Secrets, Times | `core/common/` |
| `partners` PartnerInterceptor, PartnerScope, PartnerScopes | `core/partner/` |
| `core/AuthController`, `JwksController` | `controllers/auth/AuthControllerV1_0`, `JwksControllerV1_0` |
| `catalog/AssetController`, `MediaSettingsController` | `controllers/studio/media/AssetControllerV1_0`, `MediaSettingsControllerV1_0` |
| `channels/ChannelController` | `controllers/studio/channels/ChannelControllerV1_0` |
| `schedule/ScheduleController`, `ProgrammingController` | `controllers/studio/programming/…V1_0` |
| `ads/AdsController` | `controllers/studio/ads/AdsControllerV1_0` |
| `distribution/DistributionController`, `partners/PartnerSharingController` | `controllers/studio/distribution/…V1_0` |
| `ops/OpsController` | `controllers/studio/monitoring/OpsControllerV1_0` |
| `team/TeamController` | `controllers/studio/team/TeamControllerV1_0` |
| `connectors/ConnectorsController`, `media/MuxController` | `controllers/platform/connectors/…V1_0` |
| `mail/EmailController` | `controllers/platform/email/EmailControllerV1_0` |
| `partners/PartnerApiController`, `ApiClientController` | `controllers/partner/…V1_0` |
| `playout/PlaybackController`, `media/MediaController` | `controllers/viewer/PlaybackControllerV1_0`, `MediaFileControllerV1_0` |
| `ads/AdDecisionService`, `AdSourceAdmin`, `ViewerContext` | `services/ads/AdDecisionServiceV1_0`, `AdSourceAdminService`, `ViewerContextService` |
| `ads/AdSource`, `DirectSoldSource`, `VastSource` | `services/ads/sources/IAdSource`, `DirectSoldSource`, `VastSource` |
| `catalog/MediaSettings`, `media/MediaService`, `MediaSigner`, `MediaStore` | `services/media/MediaSettingsServiceV1_0`, `MediaServiceV1_0`, `MediaSigner`, `MediaStore` |
| `media/ContentSource`, `GeneratedSource`, `RemoteSource`, `UploadSource`, `MuxSource` | `services/media/sources/IContentSource`, … |
| `schedule/ProgrammingService`, `Planner`, `ProgrammingRefresh` | `services/programming/ProgrammingServiceV1_0`, `Planner`, `ProgrammingRefresh` |
| `playout/PlayoutService`, `Scte35` | `services/playout/PlayoutServiceV1_0`, `Scte35` |
| `rights/Rights` | `services/rights/Rights` |
| `ops/AlertScanner`, `AvailVerifier` | `services/ops/` |
| `mail/MailService`, `EmailTemplates` | `services/mail/MailServiceV1_0`, `EmailTemplates` |
| `edge/EdgeClient`, `EdgePublisher` | `util/cloudflare/` |
| tenancy and project bootstrap | already under `v1_0` (tenant redesign commit) |

Tests moved next to the class they exercise (same package, for package-private access); cross-cutting tests stay in `com.faststudio`.

## Rules from now on

1. New code goes into the matching `controllers/<audience>/<feature>`, `services/<feature>`, `repositories/<feature>`, `dto/<feature>` or `util/<provider>` folder.
2. Keep every public API path, response shape and permission check. Run `CrossTenantMatrixTest` and the feature's tests after each change.
3. Controllers do not hold SQL; step 1b moves remaining SQL from controllers into repositories/services feature by feature (tracked in PROGRESS.md).
4. Do not move files another agent has claimed in PROGRESS.md; coordinate first.
