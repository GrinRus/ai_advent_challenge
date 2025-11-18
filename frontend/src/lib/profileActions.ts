import { fetchProfileDocument } from './apiClient';
import { buildProfileKey, useProfileStore } from './profileStore';
import type { UserProfileDocument } from './profileTypes';

export async function loadActiveProfile(): Promise<UserProfileDocument> {
  const state = useProfileStore.getState();
  const namespace = state.namespace;
  const reference = state.reference;
  const channel = state.channel;
  const profileKey = buildProfileKey(namespace, reference);
  useProfileStore.setState({ isLoading: true, error: undefined });
  try {
    const { profile, etag } = await fetchProfileDocument(namespace, reference, profileKey, channel);
    useProfileStore.getState().applyProfileSnapshot(profile, {
      etag,
      source: 'profile:load',
      channel,
    });
    return profile;
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Не удалось загрузить профиль';
    useProfileStore.setState({ isLoading: false, error: message });
    throw error;
  }
}
