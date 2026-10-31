export type Plan = { key: string; name: string; enabled: boolean; maxProjects: number; maxChannels: number; monthlyPrice: number; currency: string; features: string[] }
export type CustomerTenant = { id: string; slug: string; name: string; status: string; planKey: string; subscriptionStatus: string; revision: number; projects: number; channels: number }
export type Project = { id: string; tenantId: string; slug: string; name: string; status: string; members: number }
export type Connector = { key: string; name: string; category: string; driver: string; enabled: boolean; description: string }
export type Property = { key: string; label: string; valueType: string; connectorKey: string | null; secret: boolean; projectOverride: boolean; enabled: boolean; options: string[]; defaultValue: unknown; defaultConfigured: boolean; revision: number; system?: boolean }
export type EffectiveProperty = { definition: Property; value: unknown; configured: boolean; source: string; overridden: boolean }
export type Configuration = { tenant: CustomerTenant; project: Project | null; plan: Plan; projects: Project[]; editable: boolean; properties: EffectiveProperty[]; connectors: { definition: Connector; allowed: boolean; enabled: boolean; overridden: boolean }[] }
export type AccessRole = { key: string; name: string; kind: string; baseRole: string; permissions: string; enabled: boolean; revision: number }
