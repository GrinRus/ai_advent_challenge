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
