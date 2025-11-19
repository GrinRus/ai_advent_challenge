import {
  createDevProfileLink,
  fetchProfileAudit,
  fetchProfileDocument,
} from './apiClient';
import { buildProfileKey, useProfileStore } from './profileStore';
import type {
  DevProfileLinkResponse,
  ProfileAuditEntry,
  UserProfileDocument,
} from './profileTypes';

export async function loadActiveProfile(): Promise<UserProfileDocument> {
  const state = useProfileStore.getState();
  const namespace = state.namespace;
  const reference = state.reference;
  const channel = state.channel;
  const devToken = state.devToken;
  const profileKey = buildProfileKey(namespace, reference);
  useProfileStore.setState({ isLoading: true, error: undefined });
  try {
    const { profile, etag } = await fetchProfileDocument(
      namespace,
      reference,
      profileKey,
      channel,
      devToken,
    );
    useProfileStore.getState().applyProfileSnapshot(profile, {
      etag,
    });
    return profile;
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Не удалось загрузить профиль';
    useProfileStore.setState({ isLoading: false, error: message });
    throw error;
  }
}

export async function loadActiveProfileAudit(): Promise<ProfileAuditEntry[]> {
  const state = useProfileStore.getState();
  const namespace = state.namespace;
  const reference = state.reference;
  const channel = state.channel;
  const devToken = state.devToken;
  const profileKey = buildProfileKey(namespace, reference);
  useProfileStore.setState({ isAuditLoading: true, auditError: undefined });
  try {
    const events = await fetchProfileAudit(namespace, reference, profileKey, channel, devToken);
    useProfileStore.setState({ auditEvents: events, isAuditLoading: false });
    return events;
  } catch (error) {
    const message =
      error instanceof Error ? error.message : 'Не удалось загрузить историю профиля';
    useProfileStore.setState({ isAuditLoading: false, auditError: message });
    throw error;
  }
}

export async function createDevLink(): Promise<DevProfileLinkResponse> {
  const state = useProfileStore.getState();
  const namespace = state.namespace;
  const reference = state.reference;
  const channel = state.channel;
  const devToken = state.devToken;
  const profileKey = buildProfileKey(namespace, reference);
  if (!devToken) {
    throw new Error('Dev token is not configured');
  }
  return createDevProfileLink(namespace, reference, profileKey, channel, devToken);
}
