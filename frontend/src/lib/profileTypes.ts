import { z } from 'zod';

const JsonLikeSchema = z.any();

export const ProfileDocumentSchema = z.object({
  profileId: z.string(),
  namespace: z.string(),
  reference: z.string(),
  displayName: z.string(),
  locale: z.string(),
  timezone: z.string(),
  communicationMode: z.string(),
  habits: z.array(z.string()),
  antiPatterns: z.array(z.string()),
  workHours: JsonLikeSchema.optional(),
  metadata: JsonLikeSchema.optional(),
  identities: z.array(
    z.object({
      provider: z.string(),
      externalId: z.string(),
      attributes: JsonLikeSchema.optional(),
      scopes: z.array(z.string()).optional(),
    }),
  ),
  channels: z.array(
    z.object({
      channel: z.string(),
      settings: JsonLikeSchema.optional(),
    }),
  ),
  roles: z.array(z.string()),
  updatedAt: z.string(),
  version: z.number(),
});

export type UserProfileDocument = z.infer<typeof ProfileDocumentSchema>;

export const ProfileAuditEntrySchema = z.object({
  id: z.string(),
  eventType: z.string(),
  source: z.string().nullable().optional(),
  channel: z.string().nullable().optional(),
  metadata: JsonLikeSchema.optional(),
  createdAt: z.string(),
});

export type ProfileAuditEntry = z.infer<typeof ProfileAuditEntrySchema>;

export const DevLinkResponseSchema = z.object({
  code: z.string(),
  profileId: z.string(),
  namespace: z.string(),
  reference: z.string(),
  channel: z.string().nullable().optional(),
  expiresAt: z.string(),
});

export type DevProfileLinkResponse = z.infer<typeof DevLinkResponseSchema>;

export const ProfileAdminSummarySchema = z.object({
  profileId: z.string(),
  namespace: z.string(),
  reference: z.string(),
  displayName: z.string(),
  locale: z.string(),
  timezone: z.string(),
  roles: z.array(z.string()),
  updatedAt: z.string(),
});

export type ProfileAdminSummary = z.infer<typeof ProfileAdminSummarySchema>;

export const ProfileAdminPageSchema = z.object({
  content: z.array(ProfileAdminSummarySchema),
  number: z.number(),
  size: z.number(),
  totalElements: z.number(),
  totalPages: z.number(),
});

export type ProfileAdminPage = z.infer<typeof ProfileAdminPageSchema>;

export type ProfileUpdatePayload = {
  displayName: string;
  locale: string;
  timezone: string;
  communicationMode: string;
  habits: string[];
  antiPatterns: string[];
  workHours?: unknown;
  metadata?: unknown;
  channelOverrides?: Array<{ channel: string; settings?: unknown }>;
};
