# Backend folder architecture

Status: **adopted target layout, 2026-10-31.** Tenancy and project-bootstrap code already live under `v1_0/` (see mapping); everything else is still in its legacy package. New and touched backend code follows this document; legacy packages move only when their use case is being changed (see [Migration rules](#migration-rules)).

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

## Current → target mapping

Moves happen when the use case is touched, not in bulk.

| Current package | Target |
| --- | --- |
| `core/*Config`, `WebConfig` | `v1_0/config/` |
| `core/` (Tenant*, Auth, Tokens, Audit, ApiException …) | `v1_0/core/{tenant,auth,common}/` |
| `core/AuthController`, `JwksController` | `v1_0/controllers/auth/` |
| `catalog/AssetController`, `media/MediaController`, `catalog/MediaSettingsController` | `v1_0/controllers/studio/media/` + `services/media/` + `repositories/media/` |
| `media/MuxController` (platform part) | `v1_0/controllers/platform/connectors/`; webhook part → `controllers/webhook/` |
| `media/MuxSource`, `RemoteSource`, `UploadSource`, `ContentSource` | `v1_0/util/mux/`, `util/mediaProvider/`; use-case logic → `services/media/` |
| `media/MediaService` (in-memory executor) | `services/media/` + `messaging/v1_0/media/` (Stage 2) |
| `channels/`, `schedule/`, `rights/`, `ads/`, `distribution/`, `ops/`, `team/`, `mail/` | `v1_0/controllers/studio/<feature>/` + `services/<feature>/` (+ `util/` for VAST, mail transport) |
| `partners/` | `v1_0/controllers/partner/` + `services/partner/` |
| `playout/PlaybackController`, viewer ad decision/beacon endpoints | `v1_0/controllers/viewer/` |
| `edge/EdgeClient`, `EdgePublisher` | `v1_0/util/cloudflare/` |
| `controller/project`, `service/project`, `repository/project`, `dto/project`, `dto/media` | **Done 2026-10-31:** `v1_0/controllers/studio/project/`, `controllers/platform/project/`, `services/project/`, `repositories/project/`, `dto/project/`, `dto/media/` |
| `controller/tenancy`, `service/tenancy`, `repository/tenancy`, `dto/tenancy` | **Done 2026-10-31:** `v1_0/controllers/platform/tenancy/`, `controllers/studio/configuration/`, `controllers/studio/team/`, `services/tenancy/`, `repositories/tenancy/`, `dto/tenancy/` |
| `demo/DemoSeeder` | `v1_0/config/demo/` |

## Migration rules

1. Move a use case only while changing it; move its tests and callers in the same change. No package-wide rename commit.
2. Keep every public API path, response shape and permission check. Run `CrossTenantMatrixTest` and the feature's tests after each move.
3. Do not move files another agent has claimed in PROGRESS.md; coordinate first.
4. Stage 1 of [MEDIA_INGESTION_PLAN.md](MEDIA_INGESTION_PLAN.md) is the first use of this layout.
5. Record each move in PROGRESS.md so the mapping table above can be marked done.
